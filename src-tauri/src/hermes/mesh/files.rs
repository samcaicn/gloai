// Copyright (c) 2026 tupAI
//
// 文档/文件传送（blobs 内容寻址）。
//
// P0：接口占位，返回 NotImplemented。blobs 的 store/download API 接线列入 P1
// （需对齐 blobs 0.103 的 ALPN 协议处理器注册 + BLAKE3 hash 流式进度）。
// FileOffer 消息已在 ainl.rs 定义；P1 流程：
//   send_file(path) → blobs store.add → 得 blob_hash → 广播 FileOffer{hash,size,name,mime}
//   接收侧 → FileOffer → blobs download(hash) → 校验 BLAKE3 → 落盘

#[derive(Debug, thiserror::Error)]
pub enum FilesError {
    #[error("blobs wiring is P1 work; not implemented in P0")]
    NotImplemented,
}

pub async fn send_file(_path: &str) -> Result<String, FilesError> {
    Err(FilesError::NotImplemented)
}

pub async fn download(_blob_hash: &str, _dest: &str) -> Result<(), FilesError> {
    Err(FilesError::NotImplemented)
}
