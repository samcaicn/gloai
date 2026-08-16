// Copyright (c) 2026 MeeJoy
//

use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use serde::{Deserialize, Serialize};

use super::adapter_base::{IMAdapter, IMBinding};
use super::dingtalk_adapter::DingTalkAdapter;
use super::feishu_adapter::FeishuAdapter;
use super::telegram_adapter::TelegramAdapter;
use super::websocket_adapter::{LongConnAdapter, LongConnAdapterOptions};
use super::wecom_adapter::WecomAdapter;

#[derive(Serialize, Deserialize, Clone, Debug, Default)]
pub struct ChannelRegistrySnapshot {
    pub channels: HashMap<String, Vec<IMBinding>>,
}

pub struct ChannelRegistry {
    inner: RwLock<ChannelRegistryInner>,
}

struct ChannelRegistryInner {
    by_channel: HashMap<String, Vec<IMBinding>>,
    /// binding id → binding 索引，供 find_binding_by_id O(1) 查找；
    /// 与 by_channel 在同一把锁下同步更新。
    by_id: HashMap<String, IMBinding>,
}

impl Default for ChannelRegistry {
    fn default() -> Self {
        Self {
            inner: RwLock::new(ChannelRegistryInner {
                by_channel: HashMap::new(),
                by_id: HashMap::new(),
            }),
        }
    }
}

impl ChannelRegistry {
    pub fn new() -> Self { Self::default() }

    pub async fn bind(&self, channel: impl Into<String>, binding: IMBinding) {
        let mut g = self.inner.write().await;
        g.by_id.insert(binding.id.clone(), binding.clone());
        g.by_channel.entry(channel.into()).or_default().push(binding);
    }

    pub async fn unbind(&self, channel: &str, binding_id: &str) -> bool {
        let mut g = self.inner.write().await;
        let removed = if let Some(list) = g.by_channel.get_mut(channel) {
            let before = list.len();
            list.retain(|b| b.id != binding_id);
            list.len() != before
        } else {
            false
        };
        if removed {
            // 同 id 可能存在于其他 channel，重新扫描以保持 by_id 索引一致。
            // 先 cloned() 拿到 owned 值以结束对 g.by_channel 的不可变借用，
            // 再对 g.by_id 做可变操作，避免借用冲突。
            let still_present = g.by_channel
                .values()
                .flat_map(|v| v.iter())
                .find(|b| b.id == binding_id)
                .cloned();
            match still_present {
                Some(b) => { g.by_id.insert(binding_id.to_string(), b); }
                None => { g.by_id.remove(binding_id); }
            }
        }
        removed
    }

    pub async fn bindings_for(&self, channel: &str) -> Vec<IMBinding> {
        self.inner.read().await.by_channel.get(channel).cloned().unwrap_or_default()
    }

    /// 按 binding id 查找（跨所有 channel）。
    pub async fn find_binding_by_id(&self, binding_id: &str) -> Option<IMBinding> {
        self.inner.read().await.by_id.get(binding_id).cloned()
    }

    /// 返回所有已注册的 binding（跨所有 channel）。
    pub async fn all_bindings(&self) -> Vec<IMBinding> {
        self.inner.read().await.by_channel.values().flat_map(|v| v.iter().cloned()).collect()
    }

    pub async fn snapshot(&self) -> ChannelRegistrySnapshot {
        ChannelRegistrySnapshot { channels: self.inner.read().await.by_channel.clone() }
    }
}

pub type SharedChannelRegistry = Arc<ChannelRegistry>;

// ---------------------------------------------------------------------------
// Adapter factory (统一入口，供 im_config / im_bridge / AdapterPool 共用)
// ---------------------------------------------------------------------------

/// 按 `IMBinding.provider` 标签构造适配器。所有渠道统一走长连接中继，
/// provider tag 仅用于中继网关路由区分。
pub fn build_adapter_from_binding(binding: &IMBinding) -> Option<Arc<dyn IMAdapter>> {
    let options = LongConnAdapterOptions::default();
    match binding.provider.as_str() {
        "wecom" | "wecom_bot" => Some(Arc::new(WecomAdapter::new(binding.clone()))),
        "feishu" | "feishu_bot" | "feishu_lark" | "lark" => Some(Arc::new(FeishuAdapter::new(binding.clone()))),
        "dingtalk" | "dingtalk_bot" => Some(Arc::new(DingTalkAdapter::new(binding.clone()))),
        "telegram" | "tg" => Some(Arc::new(TelegramAdapter::new(binding.clone()))),
        // 微信、QQ Bot、WhatsApp 及通用长连接渠道统一走 LongConnAdapter，
        // provider tag 仅用于中继网关路由区分。
        "weixin" | "qqbot" | "whatsapp" | "long_conn" | "web_socket" | "websocket" | "" => {
            Some(Arc::new(LongConnAdapter::new(binding.clone(), options)))
        }
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// AdapterPool — 按 channel_id 持久化已连接的适配器，避免现场反复构造
// ---------------------------------------------------------------------------

/// 按 `IMBinding.id` (channel_id) 缓存已连接的 `Arc<dyn IMAdapter>`。
/// 线程安全（`Arc + RwLock`），供 `im_config_*` / `im_bridge` 共享。
pub struct AdapterPool {
    inner: RwLock<HashMap<String, Arc<dyn IMAdapter>>>,
}

impl Default for AdapterPool {
    fn default() -> Self { Self { inner: RwLock::new(HashMap::new()) } }
}

impl AdapterPool {
    pub fn new() -> Self { Self::default() }

    /// 读取已缓存的适配器（不触发连接）。
    pub async fn get(&self, channel_id: &str) -> Option<Arc<dyn IMAdapter>> {
        self.inner.read().await.get(channel_id).cloned()
    }

    /// 获取已连接的适配器；不存在则构造并 `connect`，成功后缓存。
    /// 并发安全：double-check 避免重复连接。
    ///
    /// S2 修复：build + connect 移到锁外执行，避免跨 `connect().await`
    /// 持写锁导致所有渠道操作被全局串行化（最坏阻塞数十秒）。
    pub async fn get_or_connect(&self, binding: IMBinding) -> Result<Arc<dyn IMAdapter>, String> {
        // 快速路径：读锁命中
        {
            let g = self.inner.read().await;
            if let Some(a) = g.get(&binding.id) {
                return Ok(a.clone());
            }
        }
        // 锁外 build + connect，避免跨 await 持写锁。
        let adapter = build_adapter_from_binding(&binding)
            .ok_or_else(|| format!("no adapter for provider={}", binding.provider))?;
        adapter.connect().await?;
        // 写锁 double-check：并发时另一个任务可能已插入同 id 的 adapter。
        let mut g = self.inner.write().await;
        if let Some(a) = g.get(&binding.id) {
            // 已有并发插入：克隆已有 adapter，丢弃我们新建的。
            // 显式 disconnect 新 adapter 以停止其 spawn 出来的后台任务，
            // 避免僵尸重连（新 adapter 不在池中，无人会再调 disconnect）。
            let existing = a.clone();
            drop(g);
            let _ = adapter.disconnect().await;
            return Ok(existing);
        }
        g.insert(binding.id.clone(), adapter.clone());
        Ok(adapter)
    }

    /// 更新渠道：先 disconnect 并移除旧适配器，再 `get_or_connect` 插入新的。
    pub async fn replace(&self, binding: IMBinding) -> Result<Arc<dyn IMAdapter>, String> {
        let old = {
            let mut g = self.inner.write().await;
            g.remove(&binding.id)
        };
        if let Some(a) = old {
            let _ = a.disconnect().await;
        }
        self.get_or_connect(binding).await
    }

    /// 删除渠道：移除并 disconnect 该 channel 的适配器。
    pub async fn remove_and_disconnect(&self, channel_id: &str) {
        let old = {
            let mut g = self.inner.write().await;
            g.remove(channel_id)
        };
        if let Some(a) = old {
            let _ = a.disconnect().await;
        }
    }

    /// 仅从缓存移除适配器（不 disconnect）。用于 send 失败后让下次
    /// `get_or_connect` 重建适配器——此时适配器本身已不可用，
    /// 再调用 disconnect 意义不大且可能阻塞（Bug 6A）。
    pub async fn remove(&self, channel_id: &str) {
        self.inner.write().await.remove(channel_id);
    }
}

pub type SharedAdapterPool = Arc<AdapterPool>;
