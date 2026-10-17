# -*- coding: utf-8 -*-

# ***********************************************************************
# Modified based on the KouriChat project
# Copyright of this modification: Copyright (C) 2025, iwyxdxl
# Licensed under GNU GPL-3.0 or higher, see the LICENSE file for details.
# 
# This file is part of WeAuto, which includes modifications to the KouriChat project.
# The original KouriChat project's copyright and license information are preserved in the LICENSE file.
# For any further details regarding the license, please refer to the LICENSE file.
# ***********************************************************************

"""
自动更新模块
提供程序自动更新功能，包括:
- GitHub版本检查
- 更新包下载（增加下载进度指示）
- 文件更新
- 备份和恢复
- 更新回滚
- 对 config.py 进行更新时合并用户原有的配置选项（保留原有注释和格式，仅追加新增项）
"""

import os
import re
import ast
import requests
import zipfile
import shutil
import json
import hashlib
import logging
from typing import Tuple
import sys
import datetime
import time

logger = logging.getLogger(__name__)

# 直连代理表：显式置 None，绕开 requests trust_env 默认读取的
# 环境变量 / Windows 注册表系统代理。残留死代理（如已卸载的加速器
# 留下 127.0.0.1:xxxxx）会让所有请求静默黑洞，必须提供直连回退。
_DIRECT_PROXIES = {"http": None, "https": None}


def _base_dir():
    """运行根目录（落盘用）：冻结(EXE)时为 EXE 所在目录，源码态为 updater.py 所在目录。

    ⚠️ PyInstaller onefile 下模块的 __file__ 指向 EXE 内虚拟路径（磁盘上并不存在），
    若直接拿它当落盘根目录，状态文件 .weauto_artifact.json、temp_update、更新备份
    全会写到无效位置（静默失效：更新每次都"成功"却永远记不住版本）。
    冻结态必须回退到 sys.executable 所在目录，即用户放 WeAuto.exe 的那个目录。
    """
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def _http_get(url, *, headers=None, timeout=15, stream=False):
    """带代理故障回退的 GET。

    第一轮按 requests 默认（trust_env：环境变量 + 系统代理）走，
    失败后强制直连重试一轮——确保"残留死代理"用户也能完成更新。
    仍失败则抛出最后一个 RequestException。
    """
    last_exc = None
    for proxies in (None, _DIRECT_PROXIES):
        try:
            return requests.get(url, headers=headers, timeout=timeout,
                                stream=stream, proxies=proxies)
        except requests.RequestException as e:
            last_exc = e
    raise last_exc

class Updater:
    # GitHub 仓库信息（分发源：samcaicn/gloai 的 weauto 分支，CI 构建加密 exe 并保留 90 天 Artifact）
    REPO_OWNER = "samcaicn"
    REPO_NAME = "gloai"
    REPO_BRANCH = "weauto"
    GITHUB_API = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"
    ARTIFACT_NAME = "weauto-windows-exe"   # CI 上传的加密 exe 构件名

    # EXE 自动更新代理（Cloudflare Worker）：EXE 只问本端点，Worker 用服务端 GITHUB_TOKEN
    # 解析出免鉴权的 azure 直链返回。EXE 零密钥升级，GitHub token 仅在服务端、可随时轮换。
    UPDATE_WORKER_URL = "https://weauto-app.pages.dev"

    @staticmethod
    def _update_worker_url() -> str:
        """发行版更新代理地址：以 blob GATEWAY_HOST 为准（用户改 config 无效）；开发态用常量。"""
        try:
            from weauto_license import secrets as _sec
            if _sec.is_release():
                host = _sec.get("GATEWAY_HOST", "")
                if host:
                    return host.rstrip("/")
        except Exception:
            pass
        return "https://weauto-app.pages.dev"

    # 只读令牌（可选）：用于访问私有仓库的 Actions Artifact / Release。
    # 仅需 read-only 权限（actions:read、contents:read）。
    # 解析顺序：环境变量 GITHUB_TOKEN/GH_TOKEN → 本地文件 .weauto_gh_token → gh CLI。
    GH_TOKEN_FILE = ".weauto_gh_token"

    # Gitee 备选源（同源镜像，失败回退）
    GITEE_REPO_OWNER = "samcaicn"
    GITEE_REPO_NAME = "gloai"
    GITEE_API = f"https://gitee.com/api/v5/repos/{GITEE_REPO_OWNER}/{GITEE_REPO_NAME}"
    
    # 需要跳过的文件和文件夹（不会被更新）
    SKIP_FILES = [
        "prompts",      # 聊天提示词
        "CoreMemory",  # 核心记忆文件夹
        "Memory_Temp",  # 临时记忆文件
        "emojis",      # 表情包
        "forum_data",  # 论坛数据
        "recurring_reminders.json",  # 定时提醒
        "chat_contexts.json", # 聊天上下文文件
        "config.py",    # 配置文件(单独处理)
        "数据备份",  # 数据备份
        ".git",        # Git仓库文件（避免权限问题）
        "__pycache__", # Python缓存文件
        "*.pyc",       # Python编译文件
        "temp_update", # 临时更新文件夹
        "backup",      # 备份文件夹
    ]

    # GitHub代理列表 - 第一组: 支持API Zipball的镜像 (优先使用)
    PROXY_SERVERS = [
        "",  # 空字符串表示直接使用原始GitHub地址
        # 支持API Zipball的镜像 (测试成功率4/4)
        "https://gh.noki.eu.org/",      # 899ms,4/4成功
        "https://gh-proxy.com/",        # 1350ms,4/4成功
        "https://gitpro.a3e.top/",      # 1350ms,4/4成功,官方维护
        "https://hub.tcpmini.news/",    # 1972ms,4/4成功,官方维护
        "https://tvv.tw/",              # 最快812ms,4/4成功
        "https://gh.noki.icu/",         # 1432ms,4/4成功
        "https://gh.catmak.name/",      # 2242ms,4/4成功
    ]
    
    # GitHub代理列表 - 第二组: 支持Tag/Main下载的镜像 (备用)
    PROXY_SERVERS_FALLBACK = [
        # 速度最快的镜像 (不支持API但支持Tag/Main)
        "https://y.whereisdoge.work/",  # 最快668ms,3/4成功
        "https://github.xxlab.tech/",   # 761ms,3/4成功
        "https://hub.gitmirror.com/",   # 813ms,3/4成功
        "https://gh.nxnow.top/",        # 931ms,3/4成功
        "https://gh.404cafe.fun/",      # 1010ms,3/4成功
        "https://ghproxy.net/",         # 1059ms,3/4成功
        "https://git.669966.xyz/",      # 1177ms,3/4成功
        "https://gh-proxy.net/",        # 1217ms,3/4成功
        "https://ghfast.top/",          # 1641ms,3/4成功
        "https://ghproxy.cc/",          # 1727ms,3/4成功
    ]

    def __init__(self):
        self.root_dir = _base_dir()
        self.temp_dir = os.path.join(self.root_dir, 'temp_update')
        self.version_file = os.path.join(self.root_dir, 'version.json')
        self.current_proxy_index = 0  # 当前使用的代理索引
        self.use_fallback_proxies = False  # 是否使用备用代理列表

    def get_current_proxy_list(self):
        """获取当前使用的代理列表"""
        return self.PROXY_SERVERS_FALLBACK if self.use_fallback_proxies else self.PROXY_SERVERS

    def get_proxy_url(self, original_url: str, log_info: bool = True) -> str:
        """获取当前代理URL"""
        proxy_list = self.get_current_proxy_list()
        if self.current_proxy_index >= len(proxy_list):
            return original_url
        proxy = proxy_list[self.current_proxy_index]
        
        # 打印当前使用的代理节点信息
        if log_info:
            node_type = "备用更新节点" if self.use_fallback_proxies else "主更新节点"
            node_number = self.current_proxy_index + 1
            
            if proxy:
                msg = f"[检查更新] 使用 {node_type}{node_number}"
                print(msg)
                logger.info(msg)
            else:
                msg = f"[检查更新] 使用直连方式 (主更新节点{node_number})"
                print(msg)
                logger.info(msg)
        
        return f"{proxy}{original_url}" if proxy else original_url

    def try_next_proxy(self) -> bool:
        """尝试切换到下一个代理"""
        proxy_list = self.get_current_proxy_list()
        self.current_proxy_index += 1
        
        # 如果当前列表已用完
        if self.current_proxy_index >= len(proxy_list):
            # 如果还没用过备用列表,切换到备用列表
            if not self.use_fallback_proxies:
                logger.info("主代理列表已全部尝试,切换到备用代理列表...")
                self.use_fallback_proxies = True
                self.current_proxy_index = 0
                return True
            # 备用列表也用完了
            return False
        
        return True

    def get_current_version(self) -> str:
        """获取当前版本号"""
        try:
            if os.path.exists(self.version_file):
                with open(self.version_file, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    return data.get('version', '0.0.0')
        except Exception as e:
            logger.error(f"读取版本文件失败: {str(e)}")
        return '0.0.0'

    def format_version_info(self, current_version: str, update_info: dict = None) -> str:
        """格式化版本信息输出"""
        output = (
            "\n" + "=" * 50 + "\n"
            f"当前版本: {current_version}\n"
        )
        
        if update_info:
            output += (
                f"最新版本: {update_info['version']}\n\n"
                f"更新时间: {update_info.get('last_update', '未知')}\n\n"
                "更新内容:\n"
                f"  {update_info.get('description', '无更新说明')}\n"
                + "=" * 50 + "\n\n"
            )
        else:
            output += (
                "检查结果: 当前已是最新版本\n"
                + "=" * 50 + "\n"
            )
            
        return output

    def format_update_progress(self, step: str, success: bool = True, details: str = "") -> str:
        """格式化更新进度输出"""
        status = "✓" if success else "✗"
        output = f"[{status}] {step}"
        if details:
            output += f": {details}"
        return output

    # ------------------------------------------------------------------
    # 只读 GitHub 令牌（可选）：让 Actions Artifact / Release 通道在私有仓库下
    # 也能自动更新。仅需 read-only 权限（actions:read、contents:read）。
    # ------------------------------------------------------------------
    def _resolve_github_token(self):
        """按顺序解析只读令牌：环境变量 → 本地文件 → gh CLI。"""
        for env in ("GITHUB_TOKEN", "GH_TOKEN"):
            val = os.environ.get(env)
            if val and val.strip():
                return val.strip()
        try:
            p = os.path.join(self.root_dir, self.GH_TOKEN_FILE)
            if os.path.exists(p):
                with open(p, encoding="utf-8") as f:
                    val = f.read().strip()
                    if val:
                        return val
        except Exception:
            pass
        try:
            import subprocess
            out = subprocess.run(
                ["gh", "auth", "token"], capture_output=True, text=True, timeout=10
            )
            if out.returncode == 0:
                val = out.stdout.strip()
                if val:
                    return val
        except Exception:
            pass
        return None

    def _auth_headers(self, base=None):
        """在基础请求头上追加 Bearer 令牌（若有）。"""
        headers = dict(base) if base else {}
        token = self._resolve_github_token()
        if token:
            headers["Authorization"] = f"Bearer {token}"
        return headers

    def check_for_updates(self) -> dict:
        """检查更新"""
        headers = self._auth_headers({
            'Accept': 'application/vnd.github.v3+json',
            'User-Agent': f'{self.REPO_NAME}-UpdateChecker'
        })

        # 优先从 GitHub Actions Artifact 获取（CI 构建产物，保留 90 天；分支/release 已清理）
        artifact_result = self._check_artifact_updates()
        if artifact_result.get('has_update') or not artifact_result.get('error'):
            return artifact_result

        # 回退：从版本文件 / Gitee 检查
        github_result = self._check_github_updates(headers)
        if github_result['has_update'] or not github_result.get('error'):
            return github_result

        # 如果GitHub失败，尝试从Gitee获取更新
        logger.info("GitHub检查失败，尝试从Gitee获取更新...")
        return self._check_gitee_updates()
    
    # ------------------------------------------------------------------
    # GitHub Actions Artifact 更新通道（CI 构建的加密 exe，保留 90 天）
    # CI 已不再发布公开 Release，自动更新以 Artifact 为准（见 build.yml）。
    # ------------------------------------------------------------------
    def _artifact_state_path(self):
        return os.path.join(_base_dir(), ".weauto_artifact.json")

    def _get_local_artifact_updated_at(self):
        try:
            with open(self._artifact_state_path(), encoding="utf-8") as f:
                return json.load(f).get("updated_at")
        except Exception:
            return None

    @staticmethod
    def _sha256_file(path):
        """流式计算文件 sha256（83MB exe 也不必占内存）。"""
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1024 * 1024), b""):
                h.update(chunk)
        return h.hexdigest()

    def _save_local_artifact_updated_at(self, updated_at, artifact_id, exe_sha256=None):
        """写本地构件状态。

        exe_sha256 = 本次将要应用到磁盘的那份 exe 的哈希（暂存/下载时就算好）。
        用途（实测真事）：旧 bat 的 errorlevel 掩盖 bug 会让自替换「回滚式静默
        失败」——状态已写新版本、磁盘却还是旧 exe、pending 已消费 → 该机器永远
        判定「已是最新」，收不到任何后续更新。启动时比对「状态记录的哈希 vs
        磁盘 exe 实际哈希」，不一致即说明上次替换没真正生效 → 视为无状态强制更新。
        """
        try:
            state = {"updated_at": updated_at, "artifact_id": artifact_id}
            if exe_sha256:
                state["exe_sha256"] = exe_sha256
            with open(self._artifact_state_path(), "w", encoding="utf-8") as f:
                json.dump(state, f)
        except Exception:
            pass

    def _disk_exe_matches_state(self) -> bool:
        """冻结态：磁盘 exe 哈希是否与状态文件记录一致。

        - 源码态/无记录哈希 → True（不做判断，避免开发态被强制更新）。
        - 不一致（上次替换回滚/外部替换）→ False，调用方应强制更新。
        """
        if not getattr(sys, "frozen", False):
            return True
        try:
            with open(self._artifact_state_path(), encoding="utf-8") as f:
                want = (json.load(f) or {}).get("exe_sha256")
        except Exception:
            return True
        if not want:
            return True
        try:
            cur = getattr(sys, "executable", "") or ""
            if not cur.lower().endswith(".exe") or not os.path.exists(cur):
                return True
            return self._sha256_file(cur) == want
        except Exception:
            return True

    def _check_artifact_updates(self) -> dict:
        """向 Worker 更新代理查询最新 CI 构件（EXE 零密钥）。"""
        # 线上 Worker（weauto-app）已把 /api/update 划给 Android APK（jev 侧），
        # Windows EXE 更新走专属端点 /api/update-windows（Worker 侧注释明确约定）。
        # 旧版 Worker 无此端点时 404，退回 /api/update + 构件名校验兜底。
        url = f"{self._update_worker_url().rstrip('/')}/api/update-windows"
        headers = {"User-Agent": f"{self.REPO_NAME}-UpdateChecker"}
        # 网络抖动容错：CDN 握手间歇丢包时单发必挂，带退避重试 3 轮
        last_err = None
        for attempt in range(3):
            try:
                resp = _http_get(url, headers=headers, timeout=15)
                if resp.status_code == 404 and url.endswith("/api/update-windows"):
                    # 旧版 Worker 没有 Windows 专属端点，退回通用端点再试
                    url = f"{self._update_worker_url().rstrip('/')}/api/update"
                    continue
                if resp.status_code in (401, 403, 404, 503):
                    try:
                        err = resp.json().get("error", "")
                    except Exception:
                        err = ""
                    return {"has_update": False, "error": f"更新代理不可用 (HTTP {resp.status_code} {err})"}
                resp.raise_for_status()
                data = resp.json()
                if not data.get("ok"):
                    return {"has_update": False, "error": f"更新代理返回: {data.get('error')}"}
                # 防串台：代理可能被其他项目的部署改掉默认 artifact（真实发生过：
                # 返回过 jev APK，下载 83MB 后 zip 里没有 WeAuto.exe）。显式校验。
                ret_artifact = (data.get("artifact") or "").lower()
                if ret_artifact and "weauto" not in ret_artifact:
                    return {"has_update": False,
                            "error": f"更新代理返回了错误项目的构件（{ret_artifact}），已拒绝下载"}
                latest_updated = data.get("version")  # updated_at 时间戳
                local_updated = self._get_local_artifact_updated_at()
                # 防呆：本地状态值必须是 ISO 时间戳（YYYY-MM-…）。测试残留/文件损坏的
                # 非标值（如 "CACHED-…"）字典序会压过真版本号（'C'>'2'），导致该机器
                # 永远判定"已是最新"、永远收不到更新。非标值一律视为"无本地状态"。
                if local_updated and not re.match(r"^\d{4}-\d{2}-\d{2}", str(local_updated)):
                    logger.warning("本地构件状态时间戳非标，已忽略: %r", local_updated)
                    local_updated = None
                # 磁盘真相校验：状态说「已是 V」但磁盘 exe 哈希与状态记录不符 →
                # 上次自替换回滚式失败了（旧 bat errorlevel 掩盖 bug 的实况）→
                # 视为无状态强制更新，把卡死的机器救回来（自愈，不变砖）。
                if local_updated and not self._disk_exe_matches_state():
                    logger.warning(
                        "本地构件状态与磁盘 exe 不一致（上次替换未生效），强制重新更新: %s",
                        local_updated)
                    local_updated = None
                has_update = (local_updated is None) or bool(latest_updated and latest_updated > local_updated)
                return {
                    "has_update": bool(has_update),
                    "version": latest_updated or "",
                    "download_url": data.get("download_url"),
                    "artifact_id": data.get("artifact_id"),
                    "description": "WeAuto 自动更新（CI 构建的加密混淆 WeAuto.exe）",
                    "source": "WeAuto Worker Update Proxy",
                    "output": f"最新构建: {latest_updated}",
                }
            except requests.RequestException as e:
                last_err = e
                logger.warning(f"检查更新代理第 {attempt + 1} 次失败: {e}")
                if attempt < 2:
                    time.sleep(2 * (attempt + 1))
        return {"has_update": False, "error": f"检查更新代理失败: {last_err}"}

    def repair_sig_if_transitional(self) -> bool:
        """更新过渡态自愈：本地 exe 与 Worker 最新版逐字节一致时，用其 sig 覆盖本地。

        场景（已实测发生）：旧版 updater（< 0397405）的自动更新只搬 exe 不搬 sig，
        磁盘出现「新 exe + 旧 sig」→ 新 EXE 自体检 sig 不匹配。若本地 exe 恰与线上
        最新版一致，说明是过渡态而非被 patch；拉取对应 sig 覆盖即可自愈
        （Ed25519 验签保证拉来的 sig 确实对得上本地 exe）。
        返回 True = 已修复；False = 无法确认是过渡态 / 下载失败（交由上层判死刑）。
        """
        try:
            exe = getattr(sys, "executable", "") or ""
            if not exe.lower().endswith(".exe") or not os.path.exists(exe):
                return False
            sig_path = os.path.join(os.path.dirname(os.path.abspath(exe)), "WeAuto.exe.sig")
            info = self.check_for_updates()
            url = info.get("download_url")
            if not url:
                return False
            os.makedirs(self.temp_dir, exist_ok=True)
            zip_path = os.path.join(self.temp_dir, "_sig_repair.zip")
            with _http_get(url, headers={"User-Agent": f"{self.REPO_NAME}-UpdateChecker"},
                           timeout=180, stream=True) as r:
                r.raise_for_status()
                with open(zip_path, "wb") as f:
                    for chunk in r.iter_content(chunk_size=8192):
                        if chunk:
                            f.write(chunk)
            with zipfile.ZipFile(zip_path) as z:
                names = z.namelist()
                exe_name = next((n for n in names if n.lower().endswith("weauto.exe")), None)
                sig_name = next((n for n in names if n.lower().endswith("weauto.exe.sig")), None)
                if not exe_name or not sig_name:
                    return False
                if z.read(exe_name) != open(exe, "rb").read():
                    # 本地 exe 与线上不一致：不是过渡态（被 patch 或本地定制版），不自愈
                    return False
                new_sig = z.read(sig_name)
            # 原子写入：先 .tmp 再 replace，避免写一半留下更坏的状态
            tmp_sig = sig_path + ".tmp"
            with open(tmp_sig, "wb") as f:
                f.write(new_sig)
            os.replace(tmp_sig, sig_path)
            try:
                os.remove(zip_path)
            except Exception:
                pass
            logger.info("EXE sig 过渡态自愈完成: %s", sig_path)
            return True
        except Exception as e:
            logger.warning("sig 过渡态自愈失败: %s", e)
            return False

    def apply_artifact_update(self, update_info: dict) -> dict:
        """下载加密 exe 构件并自替换（自更新通道，不经源码补丁逻辑）。"""
        import subprocess
        progress = []
        try:
            download_url = update_info["download_url"]
            # Worker 已解析为免鉴权直链（azure SAS），不再附加 GitHub token；
            # 仅当链接仍是 github 域名时才补 token（兼容旧行为）。
            headers = {"User-Agent": f"{self.REPO_NAME}-UpdateChecker"}
            if "github.com" in download_url or "api.github.com" in download_url:
                headers = self._auth_headers(headers)
            log_progress = lambda s: progress.append(s)
            log_progress("开始下载加密 exe 构件...")

            last_dl_err = None
            for attempt in range(3):
                try:
                    with _http_get(download_url, headers=headers, timeout=120,
                                   stream=True) as r:
                        r.raise_for_status()

                        os.makedirs(self.temp_dir, exist_ok=True)
                        zip_path = os.path.join(self.temp_dir, "weauto_artifact.zip")
                        with open(zip_path, "wb") as f:
                            for chunk in r.iter_content(chunk_size=8192):
                                if chunk:
                                    f.write(chunk)
                    last_dl_err = None
                    break
                except requests.RequestException as e:
                    last_dl_err = e
                    logger.warning(f"下载构件第 {attempt + 1} 次失败: {e}")
                    if attempt < 2:
                        time.sleep(3 * (attempt + 1))
            if last_dl_err is not None:
                return {"success": False,
                        "output": "\n".join(progress) + f"\n下载更新包失败: {last_dl_err}"}
            log_progress("下载完成，正在解压 WeAuto.exe...")

            extract_dir = os.path.join(self.temp_dir, "weauto_exe")
            os.makedirs(extract_dir, exist_ok=True)
            with zipfile.ZipFile(zip_path) as z:
                cand = [n for n in z.namelist() if n.lower().endswith("weauto.exe")]
                if not cand:
                    return {"success": False, "output": "\n".join(progress) + "\n构件中未找到 WeAuto.exe"}
                z.extract(cand[0], extract_dir)
                src = os.path.join(extract_dir, cand[0])

                # 防破解整改：发行版更新包 Ed25519 签名校验（公钥构建期注入 secrets blob）。
                # 校验对象 = WeAuto.exe 的 SHA-256；签名文件必须与 exe 同名 .sig 同包下发。
                try:
                    from weauto_license import secrets as _sec_up
                    if _sec_up.is_release():
                        _sig_bytes = None
                        for _n in z.namelist():
                            if _n.lower().endswith(".sig"):
                                _sig_bytes = z.read(_n)
                                break
                        if not _sig_bytes:
                            return {"success": False,
                                    "output": "\n".join(progress) + "\n发行版更新缺少签名文件，已拒绝安装。"}
                        _pub = _sec_up.get_bytes("UPDATER_VERIFY_KEY")
                        if not _pub or len(_pub) != 32:
                            return {"success": False,
                                    "output": "\n".join(progress) + "\n发行版缺少验签公钥，已拒绝安装。"}
                        import hashlib as _hl
                        from cryptography.exceptions import InvalidSignature
                        from cryptography.hazmat.primitives.asymmetric import ed25519
                        with open(src, "rb") as _f:
                            _digest = _hl.sha256(_f.read()).digest()
                        ed25519.Ed25519PublicKey.from_public_bytes(_pub).verify(_sig_bytes, _digest)
                        log_progress("更新包签名校验通过")
                except InvalidSignature:
                    return {"success": False,
                            "output": "\n".join(progress) + "\n更新包签名校验失败（内容被篡改），已拒绝安装。"}
                except Exception as _up_e:
                    return {"success": False,
                            "output": "\n".join(progress) + f"\n更新包校验异常，已拒绝安装: {_up_e}"}

            current = os.path.abspath(sys.executable)
            if not current.lower().endswith(".exe"):
                return {"success": False, "output": "\n".join(progress) + "\n当前非 exe 运行环境，无法自更新"}

            # 生成自替换批处理：等待本进程退出后覆盖并重启
            bat = os.path.join(self.temp_dir, "apply_update.bat")
            logf = os.path.join(self.temp_dir, "apply_update.log")
            # ⚠️ 自替换必须重试：PyInstaller onefile 有「引导父进程 + 子进程」两层，
            # 业务进程 os._exit(0) 后父进程还要清理几十 MB 的 _MEI 临时目录，
            # 期间目标 exe 仍被映像锁占用，单次 copy 会「拒绝访问」——
            # 而 bat 默认不中断，会一路走到 del "%~f0"，造成「更新成功却没换文件」的假象。
            # 这里改成最多 40 次重试（约 2 分钟），只有真正覆盖成功才拉起新版本。
            # ⭐ 覆盖后必须校验 + 备份回滚：真实事故——旧版 bat 只做
            # `copy /Y` 就 `start`，一旦磁盘写满/写入中断，目标 exe 会被写成
            # **截断文件**；用户下次启动，PyInstaller 解压到一半就缺
            # base_library.zip（"[Errno 2] No such file or directory"），
            # 软件彻底起不来且无任何提示。这里覆盖前后各做一次校验：
            #   覆盖前：备份当前 exe 为 .bak
            #   覆盖后：大小 + fc /b 二进制比对，不一致立即回滚备份并启动旧版
            # 把 zip 内签名写出为临时 .sig，bat 会一并搬运到新 exe 同目录
            _b = locals().get("_sig_bytes")
            _sig_out = ""
            if _b:
                _sig_out = os.path.join(self.temp_dir, "weauto_update.sig")
                with open(_sig_out, "wb") as _sf:
                    _sf.write(_b)
            with open(bat, "w", encoding="utf-8") as f:
                self._write_replace_bat(f, os.path.abspath(src), _sig_out, current, logf)
            subprocess.Popen(["cmd", "/c", bat], shell=False)
            self._save_local_artifact_updated_at(update_info.get("version"), update_info.get("artifact_id"),
                                                 self._sha256_file(src))
            return {"success": True, "output": "\n".join(progress) + "\n已下载新版本，将在退出后自动替换并重启"}
        except Exception as e:
            return {"success": False, "output": "\n".join(progress) + f"\n更新失败: {e}"}

    def prepare_artifact_update(self, update_info: dict) -> dict:
        """下载 + 验签 + 暂存更新包并写入「待应用」标记，但【不立即替换/重启】。

        与 apply_artifact_update（立即可替换并重启）的区别：把实际文件替换延后到
        下次启动（apply_pending_update），避免用户正在用 bot 时被强制重启。
        返回 success 表示新版本已就绪、待下次启动生效。
        """
        progress = []
        try:
            download_url = update_info["download_url"]
            headers = {"User-Agent": f"{self.REPO_NAME}-UpdateChecker"}
            if "github.com" in download_url or "api.github.com" in download_url:
                headers = self._auth_headers(headers)
            log_progress = lambda s: progress.append(s)
            log_progress("开始下载加密 exe 构件...")

            last_dl_err = None
            for attempt in range(3):
                try:
                    with _http_get(download_url, headers=headers, timeout=120,
                                   stream=True) as r:
                        r.raise_for_status()
                        os.makedirs(self.temp_dir, exist_ok=True)
                        zip_path = os.path.join(self.temp_dir, "weauto_artifact.zip")
                        with open(zip_path, "wb") as f:
                            for chunk in r.iter_content(chunk_size=8192):
                                if chunk:
                                    f.write(chunk)
                    last_dl_err = None
                    break
                except requests.RequestException as e:
                    last_dl_err = e
                    logger.warning(f"下载构件第 {attempt + 1} 次失败: {e}")
                    if attempt < 2:
                        time.sleep(3 * (attempt + 1))
            if last_dl_err is not None:
                return {"success": False,
                        "output": "\n".join(progress) + f"\n下载更新包失败: {last_dl_err}"}
            log_progress("下载完成，正在解压 WeAuto.exe...")

            extract_dir = os.path.join(self.temp_dir, "weauto_exe")
            os.makedirs(extract_dir, exist_ok=True)
            with zipfile.ZipFile(zip_path) as z:
                cand = [n for n in z.namelist() if n.lower().endswith("weauto.exe")]
                if not cand:
                    return {"success": False, "output": "\n".join(progress) + "\n构件中未找到 WeAuto.exe"}
                z.extract(cand[0], extract_dir)
                src = os.path.join(extract_dir, cand[0])

                # 发行版更新包 Ed25519 签名校验（与 apply_artifact_update 同款）
                try:
                    from weauto_license import secrets as _sec_up
                    if _sec_up.is_release():
                        _sig_bytes = None
                        for _n in z.namelist():
                            if _n.lower().endswith(".sig"):
                                _sig_bytes = z.read(_n)
                                break
                        if not _sig_bytes:
                            return {"success": False,
                                    "output": "\n".join(progress) + "\n发行版更新缺少签名文件，已拒绝安装。"}
                        _pub = _sec_up.get_bytes("UPDATER_VERIFY_KEY")
                        if not _pub or len(_pub) != 32:
                            return {"success": False,
                                    "output": "\n".join(progress) + "\n发行版缺少验签公钥，已拒绝安装。"}
                        import hashlib as _hl
                        from cryptography.exceptions import InvalidSignature
                        from cryptography.hazmat.primitives.asymmetric import ed25519
                        with open(src, "rb") as _f:
                            _digest = _hl.sha256(_f.read()).digest()
                        ed25519.Ed25519PublicKey.from_public_bytes(_pub).verify(_sig_bytes, _digest)
                        log_progress("更新包签名校验通过")
                except InvalidSignature:
                    return {"success": False,
                            "output": "\n".join(progress) + "\n更新包签名校验失败（内容被篡改），已拒绝安装。"}
                except Exception as _up_e:
                    return {"success": False,
                            "output": "\n".join(progress) + f"\n更新包校验异常，已拒绝安装: {_up_e}"}

            current = os.path.abspath(sys.executable)
            if not current.lower().endswith(".exe"):
                return {"success": False, "output": "\n".join(progress) + "\n当前非 exe 运行环境，无法暂存更新"}

            # ---- 暂存到 staged_update 区（不在当前进程内替换）----
            stage_dir = os.path.join(self.temp_dir, "staged_update")
            os.makedirs(stage_dir, exist_ok=True)
            staged_exe = os.path.join(stage_dir, "WeAuto.exe")
            staged_sig = os.path.join(stage_dir, "WeAuto.exe.sig")
            shutil.copy2(src, staged_exe)
            if _sig_bytes:
                with open(staged_sig, "wb") as f:
                    f.write(_sig_bytes)
            pending = {
                "version": update_info.get("version"),
                "artifact_id": update_info.get("artifact_id"),
                "staged_exe": staged_exe,
                "staged_sig": staged_sig,
                "updated_at": update_info.get("version"),
            }
            with open(os.path.join(self.temp_dir, "pending_update.json"), "w", encoding="utf-8") as f:
                json.dump(pending, f, ensure_ascii=False, indent=2)
            # 写本地构件状态，避免重复下载（附 exe 哈希：替换回滚可自愈）
            self._save_local_artifact_updated_at(update_info.get("version"), update_info.get("artifact_id"),
                                                 self._sha256_file(staged_exe))
            return {"success": True,
                    "output": "\n".join(progress) + "\n已下载并验签新版本，将在下次启动时应用（不打断当前使用）"}
        except Exception as e:
            return {"success": False, "output": "\n".join(progress) + f"\n更新准备失败: {e}"}

    def apply_pending_update(self) -> bool:
        """启动期调用（冻结态）：若存在已暂存且验签通过的更新包，则在当前进程外
        替换磁盘上的 exe 并拉起新版本，随后当前（旧）进程退出——即「下次重启即可」：
        用户本次打开的若仍是旧版本，会在启动瞬间自动换成新版本并重开，全程不打断
        正在进行的会话（替换发生在启动边界，而非会话中途）。

        - 仅冻结态、且未设置 WEAUTO_NO_AUTO_UPDATE 时生效。
        - 再次验签（纵深防御：暂存文件在磁盘期间可能被篡改）。
        - 复用 _write_replace_bat（默认 relaunch=True）做「让位法」替换 + 拉起新版。
        返回 True 表示已触发替换（调用方应 os._exit(0) 让出文件锁）。
        """
        if not getattr(sys, "frozen", False):
            return False
        if os.environ.get("WEAUTO_NO_AUTO_UPDATE") == "1":
            return False
        import subprocess
        try:
            temp_dir = self.temp_dir
            pending_path = os.path.join(temp_dir, "pending_update.json")
            if not os.path.exists(pending_path):
                return False
            with open(pending_path, "r", encoding="utf-8") as f:
                pending = json.load(f)
            staged_exe = pending.get("staged_exe")
            staged_sig = pending.get("staged_sig")
            if not staged_exe or not os.path.exists(staged_exe):
                logger.warning("待应用更新包缺失，跳过: %s", staged_exe)
                try:
                    os.remove(pending_path)
                except Exception:
                    pass
                return False
            # 再次验签（纵深防御）
            try:
                from weauto_license import secrets as _sec_up
                if _sec_up.is_release():
                    _pub = _sec_up.get_bytes("UPDATER_VERIFY_KEY")
                    if _pub and len(_pub) == 32 and os.path.exists(staged_sig):
                        import hashlib as _hl
                        from cryptography.exceptions import InvalidSignature
                        from cryptography.hazmat.primitives.asymmetric import ed25519
                        with open(staged_exe, "rb") as _f:
                            _digest = _hl.sha256(_f.read()).digest()
                        with open(staged_sig, "rb") as _f:
                            _sig = _f.read()
                        ed25519.Ed25519PublicKey.from_public_bytes(_pub).verify(_sig, _digest)
                        logger.info("待应用更新包二次验签通过")
            except InvalidSignature:
                logger.warning("待应用更新包验签失败，已放弃: %s", staged_exe)
                return False
            except Exception:
                pass  # 非发行版/无公钥时跳过二次验签
            # 写自替换 bat（让位法 + 拉起新版）并启动它
            bat = os.path.join(temp_dir, "apply_update.bat")
            logf = os.path.join(temp_dir, "apply_update.log")
            current = os.path.abspath(sys.executable)
            with open(bat, "w", encoding="utf-8") as f:
                self._write_replace_bat(f, os.path.abspath(staged_exe),
                                        staged_sig or "", current, logf)
            subprocess.Popen(["cmd", "/c", bat], shell=False)
            # 补写构件状态哈希（旧版 updater 暂存时没写；此刻 staged_exe 已验签读过，
            # 哈希就算它将来的落盘形态）：替换成功 → 磁盘与状态一致；替换失败回滚 →
            # 磁盘与状态不符 → 下次启动 _check_artifact_updates 判定强制更新，不死锁。
            try:
                self._save_local_artifact_updated_at(pending.get("version"),
                                                     pending.get("artifact_id"),
                                                     self._sha256_file(staged_exe))
            except Exception:
                pass
            # 删除 pending 标记（bat 完成替换后会自行处理；标记删除避免重复触发）
            try:
                os.remove(pending_path)
            except Exception:
                pass
            logger.info("已触发下次启动生效的更新替换（当前旧进程即将退出，新版将启动）")
            return True
        except Exception as e:
            logger.warning("应用待更新失败: %s", e)
            return False

    def _write_replace_bat(self, f, src, src_sig, current, logf):
        """写入「自替换批处理」内容（可单测：传一个 file-like 即可，不真正执行）。

        src_sig 为随包下发的 WeAuto.exe.sig 路径；bat 会把新 sig 一并搬到
        新 exe 同目录，使「已安装 EXE 被 patch」能被 hardening.self_integrity_ok()
        用同目录 sig 揭穿（纵深防御闭环）。

        分支：
          WAITLOCK  copy 失败（映像锁未释放）→ 重试至 40 次仍失败则放弃（exit 1）
          VERIFY    copy 成功后比对大小 + fc /b 二进制，一致才启动新版本（exit 0）
          ROLLBACK  校验不一致 → 用 .bak 回滚并启动旧版本（exit 2）

        ⚠️ 批处理**不再自删**（旧版 `del "%~f0"`）：实测 cmd 在脚本文件被删除后
        会立刻终止批处理并吞掉退出码（无论哪条分支 rc 恒为 1），那样日志里
        "成功/回滚/放弃"就无从区分。改由下次更新覆盖同名文件，并在开头加
        `if not exist "%SRC%" exit /b 3` 防止被误双击执行。

        退出码：0=覆盖并校验通过；1=目标一直被占用已放弃；2=校验失败已回滚；3=源文件缺失。
        """
        f.write("@echo off\n")
        f.write("setlocal enabledelayedexpansion\n")
        f.write('set "SRC=%s"\n' % src)
        if src_sig:
            f.write('set "SRCSIG=%s"\n' % src_sig)
        f.write('set "DST=%s"\n' % current)
        f.write('set "BAK=%DST%.bak"\n')
        f.write('set "LOGF=%s"\n' % logf)
        f.write("set N=0\n")
        f.write('if not exist "%SRC%" exit /b 3\n')
        f.write('> "%LOGF%" echo WeAuto 自替换开始 %date% %time%\n')
        f.write('copy /Y "%DST%" "%BAK%" >> "%LOGF%" 2>&1\n')
        # ---- 方案 A（首选）：让位法绕开映像锁 ----
        # Windows 允许重命名正在运行的映像文件（不允许删除/覆盖）。先把旧 exe 改名让位，
        # 新文件就能无障碍写入，不必苦等父进程清理完 _MEI（实测需 35~40s，且可能被
        # 杀软扫描进一步拉长，单纯重试很容易全败）。
        f.write('for %%F in ("%DST%") do set "DSTNAME=%%~nxF"\n')
        f.write('for %%F in ("%DST%") do set "DSTDIR=%%~dpF"\n')
        if src_sig:
            f.write('set "DSTSIG=!DSTDIR!WeAuto.exe.sig"\n')
            f.write('set "OLDSIG=!DSTDIR!WeAuto.exe.sig.bak"\n')
            f.write('if exist "!DSTSIG!" copy /Y "!DSTSIG!" "!OLDSIG!" >nul 2>&1\n')
        f.write('set "OLDNAME=!DSTNAME!.old"\n')
        f.write(":RENAME_TRY\n")
        f.write("pushd \"!DSTDIR!\"\n")
        f.write('ren "!DSTNAME!" "!OLDNAME!" >> "%LOGF%" 2>&1\n')
        f.write("if errorlevel 1 goto RNFAIL\n")
        f.write('copy /Y "%SRC%" "!DSTNAME!" >> "%LOGF%" 2>&1\n')
        # ⚠️ exe 写入结果必须【立即】检查：旧写法把 sig copy 紧跟其后，sig 成功会把
        # exe 的 errorlevel 掩盖掉（if not errorlevel 1 看的是最后一条命令），
        # 导致 exe 没换成却直接进 VERIFY → fc 失败回滚（实测发生过：更新静默不生效）。
        f.write("if errorlevel 1 goto RNRESTORE\n")
        if src_sig:
            f.write('copy /Y "%SRCSIG%" "!DSTSIG!" >> "%LOGF%" 2>&1\n')
            # sig 搬运失败只告警：不掩盖 exe 成功、不阻断更新（缺 sig 时自体检
            # 放行+告警，不会变砖）
            f.write('if errorlevel 1 >> "%LOGF%" echo WARN sig 搬运失败（自体检将放行+告警，不阻断更新）\n')
        f.write("goto RNVERIFY\n")
        f.write(":RNRESTORE\n")
        # 新文件写入失败：立刻把旧文件改回来；改不回来就从备份复制回来，绝不让用户无 exe 可用
        f.write('>> "%LOGF%" echo WARN 让位后写入失败，正在还原旧文件\n')
        f.write('ren "!OLDNAME!" "!DSTNAME!" >> "%LOGF%" 2>&1\n')
        f.write('if errorlevel 1 copy /Y "%BAK%" "!DSTNAME!" >> "%LOGF%" 2>&1\n')
        if src_sig:
            f.write('if exist "!OLDSIG!" copy /Y "!OLDSIG!" "!DSTSIG!" >nul 2>&1\n')
        f.write(":RNFAIL\n")
        f.write("popd\n")
        f.write("goto WAITLOCK\n")
        f.write(":RNVERIFY\n")
        f.write('del /F /Q "!OLDNAME!" >> "%LOGF%" 2>&1\n')
        f.write("popd\n")
        f.write('>> "%LOGF%" echo 让位法覆盖完成，正在校验完整性...\n')
        f.write("goto VERIFY\n")
        f.write(":WAITLOCK\n")
        f.write("timeout /t 2 /nobreak >nul\n")
        f.write('copy /Y "%SRC%" "%DST%" >> "%LOGF%" 2>&1\n')
        # exe 写入成功才去搬 sig（与让位路径同修：sig 的 errorlevel 不许掩盖 exe）
        f.write("if not errorlevel 1 goto WLSIG\n")
        f.write("set /a N+=1\n")
        # 实测：onefile 父进程清理 _MEI（约 90MB 解压目录）需 35~40s 才释放映像锁，
        # 重试窗口必须覆盖它并留足余量（60 次 × 约 3s ≈ 3 分钟）。
        f.write("if !N! LSS 60 goto WAITLOCK\n")
        f.write('>> "%LOGF%" echo FAILED 目标文件仍被占用，已重试60次放弃\n')
        f.write("exit /b 1\n")
        f.write(":WLSIG\n")
        if src_sig:
            f.write('copy /Y "%SRCSIG%" "!DSTSIG!" >> "%LOGF%" 2>&1\n')
            f.write('if errorlevel 1 >> "%LOGF%" echo WARN sig 搬运失败（自体检将放行+告警，不阻断更新）\n')
        f.write("goto VERIFY\n")
        f.write(":VERIFY\n")
        f.write('>> "%LOGF%" echo 已覆盖，正在校验完整性...\n')
        f.write('for %%F in ("%SRC%") do set SS=%%~zF\n')
        f.write('for %%F in ("%DST%") do set DS=%%~zF\n')
        f.write('if not "!SS!"=="!DS!" goto ROLLBACK\n')
        f.write('fc /b "%SRC%" "%DST%" >nul 2>&1\n')
        f.write("if errorlevel 1 goto ROLLBACK\n")
        f.write('>> "%LOGF%" echo OK 覆盖并校验通过(!DS! 字节)\n')
        f.write('del "%BAK%"\n')
        f.write('start "" "%DST%"\n')
        f.write('if exist "%SRC%" del "%SRC%"\n')
        f.write("exit /b 0\n")
        f.write(":ROLLBACK\n")
        f.write('>> "%LOGF%" echo FAILED 覆盖后文件不完整(!DS!/!SS!)，回滚到备份版本\n')
        f.write('copy /Y "%BAK%" "%DST%" >> "%LOGF%" 2>&1\n')
        if src_sig:
            f.write('if exist "!OLDSIG!" copy /Y "!OLDSIG!" "!DSTSIG!" >> "%LOGF%" 2>&1\n')
        f.write('start "" "%DST%"\n')
        f.write("exit /b 2\n")

    def _check_github_updates(self, headers: dict) -> dict:
        """从GitHub检查更新"""
        while True:
            try:
                version_url = f"https://raw.githubusercontent.com/{self.REPO_OWNER}/{self.REPO_NAME}/{self.REPO_BRANCH}/version.json"
                proxied_url = self.get_proxy_url(version_url)
                
                logger.info(f"正在尝试从 {proxied_url} 获取版本信息...")
                response = requests.get(
                    proxied_url,
                    headers=headers,
                    timeout=10,
                    verify=True
                )
                response.raise_for_status()
                
                try:
                    remote_version_info = response.json()
                    current_version = self.get_current_version()
                    latest_version = remote_version_info.get('version', '0.0.0')
                except json.JSONDecodeError as e:
                    logger.error(f"解析版本信息JSON失败: {str(e)}")
                    if self.try_next_proxy():
                        logger.info("正在切换到下一个代理服务器检查更新，请稍候...")
                        continue
                    else:
                        return {
                            'has_update': False,
                            'error': "检查更新失败：无法解析版本信息",
                            'output': "检查更新失败：无法解析版本信息"
                        }
                
                def parse_version(version: str) -> tuple:
                    version = version.lower().strip('v')
                    try:
                        parts = version.split('.')
                        while len(parts) < 3:
                            parts.append('0')
                        return tuple(map(int, parts[:3]))
                    except (ValueError, AttributeError):
                        return (0, 0, 0)

                current_ver_tuple = parse_version(current_version)
                latest_ver_tuple = parse_version(latest_version)

                if latest_ver_tuple > current_ver_tuple:
                    # 根据当前使用的代理列表选择下载方式
                    if self.use_fallback_proxies:
                        # 备用代理使用Tag下载 (支持率更高)
                        logger.info("使用备用代理,采用Tag下载方式")
                        download_url = f"https://github.com/{self.REPO_OWNER}/{self.REPO_NAME}/archive/refs/tags/{latest_version}.zip"
                        # 如果没有tag,使用main分支
                        if not latest_version or latest_version == '0.0.0':
                            download_url = f"https://github.com/{self.REPO_OWNER}/{self.REPO_NAME}/archive/{self.REPO_BRANCH}.zip"
                    else:
                        # 主代理使用API Zipball下载
                        logger.info("使用主代理,采用API Zipball下载方式")
                        release_url = self.get_proxy_url(f"{self.GITHUB_API}/releases/latest")
                        response = requests.get(
                            release_url,
                            headers=headers,
                            timeout=10
                        )
                        
                        if response.status_code == 404:
                            download_url = f"{self.GITHUB_API}/zipball/{self.REPO_BRANCH}"
                        else:
                            try:
                                release_info = response.json()
                                # 安全地获取 zipball_url，如果不存在则使用分支下载
                                download_url = release_info.get('zipball_url', f"{self.GITHUB_API}/zipball/{self.REPO_BRANCH}")
                            except (json.JSONDecodeError, KeyError, TypeError) as e:
                                logger.warning(f"解析release信息失败: {str(e)}，使用分支下载")
                                download_url = f"{self.GITHUB_API}/zipball/{self.REPO_BRANCH}"
                    
                    # 返回原始下载URL（关键修改点）
                    return {
                        'has_update': True,
                        'version': latest_version,
                        'download_url': download_url,  # 直接返回GitHub原始URL
                        'description': remote_version_info.get('description', '无更新说明'),
                        'last_update': remote_version_info.get('last_update', ''),
                        'output': self.format_version_info(current_version, remote_version_info),
                        'source': 'GitHub (备用代理-Tag下载)' if self.use_fallback_proxies else 'GitHub (主代理-API)'
                    }
                
                return {
                    'has_update': False,
                    'output': self.format_version_info(current_version)
                }
                
            except (requests.RequestException, json.JSONDecodeError):
                if self.try_next_proxy():
                    logger.info("正在切换到下一个代理服务器检查更新，请稍候...")
                    continue
                else:
                    logger.error("所有代理服务器均已尝试失败")
                    return {
                        'has_update': False,
                        'error': "检查更新失败：无法连接到GitHub服务器",
                        'output': "检查更新失败：无法连接到GitHub服务器"
                    }
    
    def _check_gitee_updates(self) -> dict:
        """从Gitee检查更新（备选方案）"""
        try:
            logger.info("正在从Gitee获取版本信息...")
            
            # 获取版本信息
            version_url = f"https://gitee.com/{self.GITEE_REPO_OWNER}/{self.GITEE_REPO_NAME}/raw/{self.REPO_BRANCH}/version.json"
            response = requests.get(version_url, timeout=10)
            response.raise_for_status()
            
            try:
                remote_version_info = response.json()
                current_version = self.get_current_version()
                latest_version = remote_version_info.get('version', '0.0.0')
            except json.JSONDecodeError as e:
                logger.error(f"解析Gitee版本信息JSON失败: {str(e)}")
                return {
                    'has_update': False,
                    'error': "检查更新失败：无法解析Gitee版本信息",
                    'output': "检查更新失败：无法解析Gitee版本信息"
                }
            
            def parse_version(version: str) -> tuple:
                version = version.lower().strip('v')
                try:
                    parts = version.split('.')
                    while len(parts) < 3:
                        parts.append('0')
                    return tuple(map(int, parts[:3]))
                except (ValueError, AttributeError):
                    return (0, 0, 0)
            
            current_ver_tuple = parse_version(current_version)
            latest_ver_tuple = parse_version(latest_version)
            
            if latest_ver_tuple > current_ver_tuple:
                # Gitee下载链接
                download_url = f"https://gitee.com/{self.GITEE_REPO_OWNER}/{self.GITEE_REPO_NAME}/repository/archive/{self.REPO_BRANCH}.zip"
                
                return {
                    'has_update': True,
                    'version': latest_version,
                    'download_url': download_url,
                    'description': remote_version_info.get('description', '无更新说明'),
                    'last_update': remote_version_info.get('last_update', ''),
                    'output': self.format_version_info(current_version, remote_version_info) + "\n[使用Gitee源]",
                    'source': 'Gitee'
                }
            
            return {
                'has_update': False,
                'output': self.format_version_info(current_version) + "\n[使用Gitee源]"
            }
            
        except Exception as e:
            logger.error(f"从Gitee检查更新失败: {str(e)}")
            return {
                'has_update': False,
                'error': "检查更新失败：无法连接到更新服务器",
                'output': "检查更新失败：无法连接到更新服务器"
            }

    def backup_important_files(self) -> bool:
        """在更新前备份重要文件和文件夹到数据备份/{时间}_更新备份目录"""
        try:
            # 创建带时间戳的备份目录
            timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
            backup_dir = os.path.join(self.root_dir, '数据备份', f'{timestamp}_更新备份')
            os.makedirs(backup_dir, exist_ok=True)
            
            # 需要备份的文件
            files_to_backup = [
                "config.py",
                "recurring_reminders.json",
                "chat_contexts.json"
            ]
            
            # 需要备份的文件夹
            folders_to_backup = [
                "prompts",
                "emojis", 
                "forum_data",
                "CoreMemory",
                "Memory_Temp"
            ]
            
            backed_up_items = []
            
            # 备份单个文件
            for file in files_to_backup:
                src_file = os.path.join(self.root_dir, file)
                if os.path.exists(src_file):
                    dst_file = os.path.join(backup_dir, file)
                    os.makedirs(os.path.dirname(dst_file), exist_ok=True)
                    shutil.copy2(src_file, dst_file)
                    logger.info(f"已备份文件: {file}")
                    backed_up_items.append(f"{file}文件")
            
            # 备份文件夹
            for folder in folders_to_backup:
                src_folder = os.path.join(self.root_dir, folder)
                if os.path.exists(src_folder):
                    dst_folder = os.path.join(backup_dir, folder)
                    shutil.copytree(src_folder, dst_folder)
                    logger.info(f"已备份文件夹: {folder}")
                    backed_up_items.append(f"{folder}文件夹")
            
            if backed_up_items:
                logger.info(f"重要文件已备份到: {backup_dir}")
                logger.info(f"备份项目: {', '.join(backed_up_items)}")
            else:
                logger.info("没有找到需要备份的文件或文件夹")
                
            return True
        except Exception as e:
            logger.error(f"备份重要文件失败: {str(e)}")
            return False

    def should_skip_file(self, file_path: str) -> bool:
        """检查是否应该跳过更新某个文件

        调用方传入的可能是 basename 也可能是相对/绝对路径，因此同时处理：
        - "*" 通配全部
        - "*.pyc" 这类前缀通配（按后缀匹配，子串匹配对 "*.pyc" 永远不生效）
        - 精确文件名相等 / 原路径子串包含（保留目录规则如 "__pycache__"）
        """
        base = os.path.basename(file_path)
        for skip_file in self.SKIP_FILES:
            if skip_file == "*":
                return True
            if skip_file.startswith("*") and base.endswith(skip_file[1:]):
                return True
            if skip_file == base or skip_file in file_path:
                return True
        return False

    def _safe_remove_tree(self, path: str) -> bool:
        """安全地删除目录树，处理权限问题"""
        try:
            # 首先尝试普通删除
            shutil.rmtree(path)
            return True
        except Exception as e:
            logger.warning(f"普通删除失败: {str(e)}，尝试强制删除...")
            try:
                # 在Windows上，尝试更改权限后删除
                if os.name == 'nt':
                    import stat
                    def handle_remove_readonly(func, path, exc):
                        if os.path.exists(path):
                            os.chmod(path, stat.S_IWRITE)
                            func(path)
                    shutil.rmtree(path, onerror=handle_remove_readonly)
                else:
                    # 在Unix系统上，尝试使用rm命令
                    os.system(f'rm -rf "{path}"')
                return True
            except Exception as e2:
                logger.error(f"强制删除也失败: {str(e2)}")
                return False

    def backup_current_version(self) -> bool:
        """备份当前版本"""
        try:
            backup_dir = os.path.join(self.root_dir, 'backup')
            if os.path.exists(backup_dir):
                self._safe_remove_tree(backup_dir)
            
            # 使用自定义的忽略函数，更安全地处理文件权限问题
            def ignore_func(dir_path, filenames):
                ignored = []
                for name in filenames:
                    full_path = os.path.join(dir_path, name)
                    # 检查是否应该跳过
                    if any(skip_pattern in full_path for skip_pattern in self.SKIP_FILES):
                        ignored.append(name)
                    # 跳过可能有权限问题的文件
                    elif name.startswith('.git') or name.endswith('.lock'):
                        ignored.append(name)
                return ignored
            
            shutil.copytree(self.root_dir, backup_dir, ignore=ignore_func)
            logger.info(f"当前版本已备份到: {backup_dir}")
            return True
        except Exception as e:
            logger.error(f"备份失败: {str(e)}")
            return False

    def restore_from_backup(self) -> bool:
        """从备份恢复"""
        try:
            backup_dir = os.path.join(self.root_dir, 'backup')
            if not os.path.exists(backup_dir):
                logger.error("备份目录不存在")
                return False
                
            logger.info("开始从备份恢复...")
            for root, dirs, files in os.walk(backup_dir):
                relative_path = os.path.relpath(root, backup_dir)
                target_dir = os.path.join(self.root_dir, relative_path)
                
                for file in files:
                    if not self.should_skip_file(file):
                        try:
                            src_file = os.path.join(root, file)
                            dst_file = os.path.join(target_dir, file)
                            os.makedirs(os.path.dirname(dst_file), exist_ok=True)
                            
                            # 如果目标文件存在，尝试删除
                            if os.path.exists(dst_file):
                                try:
                                    os.remove(dst_file)
                                except OSError:
                                    logger.warning(f"无法删除现有文件: {dst_file}")
                            
                            shutil.copy2(src_file, dst_file)
                        except Exception as file_error:
                            logger.warning(f"恢复文件失败 {file}: {str(file_error)}")
                            continue
            
            logger.info("从备份恢复完成")
            return True
        except Exception as e:
            logger.error(f"恢复失败: {str(e)}")
            return False

    def apply_update(self) -> Tuple[bool, str]:
        """
        应用更新，并返回 (成功标志, 更新包顶层目录名称)
        """
        try:
            zip_path = os.path.join(self.temp_dir, 'update.zip')
            extract_dir = os.path.join(self.temp_dir, 'extracted')
            
            with zipfile.ZipFile(zip_path, 'r') as zip_ref:
                zip_ref.extractall(extract_dir)
            
            extracted_dirs = [d for d in os.listdir(extract_dir) 
                            if os.path.isdir(os.path.join(extract_dir, d))]
            if not extracted_dirs:
                raise Exception("无效的更新包结构")
            
            new_dir = extracted_dirs[0]
            source_root = os.path.join(extract_dir, new_dir)
            
            for root, dirs, files in os.walk(source_root):
                relative_path = os.path.relpath(root, source_root)
                target_dir = os.path.join(self.root_dir, relative_path)
                os.makedirs(target_dir, exist_ok=True)
                for file in files:
                    if not self.should_skip_file(file):
                        src_file = os.path.join(root, file)
                        dst_file = os.path.join(target_dir, file)
                        if os.path.exists(dst_file):
                            os.remove(dst_file)
                        shutil.copy2(src_file, dst_file)
                        
                        # 特殊处理version.json，确保它被正确复制
                        if file == 'version.json':
                            logger.info(f"已更新版本文件: {dst_file}")
            return True, new_dir
        except Exception as e:
            logger.error(f"更新失败: {str(e)}")
            return False, ""

    def cleanup(self):
        """清理临时文件"""
        try:
            if os.path.exists(self.temp_dir):
                logger.info(f"正在删除临时目录: {self.temp_dir}")
                self._safe_remove_tree(self.temp_dir)
            backup_dir = os.path.join(self.root_dir, 'backup')
            if os.path.exists(backup_dir):
                logger.info(f"正在删除备份目录: {backup_dir}")
                self._safe_remove_tree(backup_dir)
            extract_dir = os.path.join(self.temp_dir, 'extracted')
            if os.path.exists(extract_dir):
                logger.info(f"正在删除解压目录: {extract_dir}")
                self._safe_remove_tree(extract_dir)
            temp_zip = os.path.join(self.root_dir, 'update.zip')
            if os.path.exists(temp_zip):
                logger.info(f"正在删除残留zip文件: {temp_zip}")
                try:
                    os.remove(temp_zip)
                except OSError:
                    logger.warning(f"无法删除 {temp_zip}，可能被占用")
        except Exception as e:
            logger.error(f"清理失败: {str(e)}")

    def prompt_update(self, update_info: dict) -> bool:
        """提示用户是否更新"""
        print(self.format_version_info(self.get_current_version(), update_info))
        
        while True:
            choice = input("\n是否现在更新?\n输入'y'更新 / 输入'n'取消更新并继续启动: ").lower().strip()
            if choice in ('y', 'yes'):
                print("\n正在更新,这可能需要一些时间,请耐心等待...")
                return True
            elif choice in ('n', 'no'):
                return False
            print("请输入 y 或 n")

    def download_update(self, download_url: str, callback=None) -> bool:
        """下载更新包，并在下载过程中通过 callback 输出进度指示"""
        headers = {
            'Accept': 'application/vnd.github.v3+json',
            'User-Agent': f'{self.REPO_NAME}-UpdateChecker'
        }
        
        # 检查是否为Gitee链接，如果是则不使用代理
        is_gitee = 'gitee.com' in download_url
        
        while True:
            try:
                if is_gitee:
                    # Gitee链接直接使用，不应用代理
                    proxied_url = download_url
                    msg = "[下载更新] 使用Gitee源"
                    print(msg)
                    logger.info(msg)
                else:
                    # GitHub链接使用代理
                    # 先打印节点信息
                    node_type = "备用更新节点" if self.use_fallback_proxies else "主更新节点"
                    node_number = self.current_proxy_index + 1
                    proxy_list = self.get_current_proxy_list()
                    current_proxy = proxy_list[self.current_proxy_index] if self.current_proxy_index < len(proxy_list) else ""
                    
                    if current_proxy:
                        msg = f"[下载更新] 使用 {node_type}{node_number}"
                        print(msg)
                        logger.info(msg)
                    else:
                        msg = f"[下载更新] 使用直连方式 (主更新节点{node_number})"
                        print(msg)
                        logger.info(msg)
                    
                    proxied_url = self.get_proxy_url(download_url, log_info=False)  # 不重复打印日志
                
                with requests.get(
                    proxied_url,
                    headers=headers,
                    timeout=30,
                    stream=True
                ) as response:
                    response.raise_for_status()

                    os.makedirs(self.temp_dir, exist_ok=True)
                    zip_path = os.path.join(self.temp_dir, 'update.zip')

                    total_length = response.headers.get("Content-Length")
                    if total_length is not None:
                        total_length = int(total_length)
                    downloaded = 0

                    with open(zip_path, 'wb') as f:
                        for chunk in response.iter_content(chunk_size=8192):
                            if chunk:
                                f.write(chunk)
                                downloaded += len(chunk)
                                if total_length:
                                    percent = downloaded / total_length * 100
                                    # 添加进度条显示
                                    bar_length = 30
                                    filled = int(bar_length * downloaded // total_length)
                                    bar = '█' * filled + ' ' * (bar_length - filled)
                                    sys.stdout.write("\r下载进度 |{bar}| {percent:.1f}% ({downloaded/1024/1024:.1f}MB/{total_length/1024/1024:.1f}MB)")
                                else:
                                    sys.stdout.write("\r已下载: {downloaded/1024/1024:.1f} MB")
                                sys.stdout.flush()
                    print("\n下载完成")  # 换行确保后续输出不混乱

                    return True
                    
            except requests.RequestException as e:
                if is_gitee:
                    # Gitee下载失败，直接返回失败
                    logger.error(f"Gitee下载失败: {str(e)}")
                    return False
                else:
                    # GitHub下载失败，尝试下一个代理
                    logger.warning(f"使用当前代理下载更新失败: {str(e)}")
                    if self.try_next_proxy():
                        logger.info("正在切换到下一个代理服务器...")
                        continue
                    else:
                        logger.error("所有代理服务器均已尝试失败")
                        return False

    def update(self, callback=None) -> dict:
        """执行更新"""
        try:
            progress = []
            def log_progress(step, success=True, details=""):
                msg = self.format_update_progress(step, success, details)
                progress.append(msg)
                if callback:
                    callback(msg)

            log_progress("开始检查GitHub更新...")
            update_info = self.check_for_updates()
            if not update_info['has_update']:
                log_progress("检查更新完成", True, "当前已是最新版本")
                print("\n当前已是最新版本，无需更新")
                return {'success': True, 'output': '\n'.join(progress)}
            
            # 显示更新源信息
            update_source = update_info.get('source', 'Unknown')
            log_progress("发现新版本", True, f"来源: {update_source}")
            
            if not self.prompt_update(update_info):
                log_progress("提示用户是否更新", True, "用户取消更新")
                print("\n已取消更新")
                return {'success': True, 'output': '\n'.join(progress)}

            # 加密 exe 构件：走自更新通道，跳过源码补丁逻辑
            if update_info.get('source') in ('GitHub Actions Artifact', 'WeAuto Worker Update Proxy'):
                result = self.apply_artifact_update(update_info)
                result['output'] = '\n'.join(progress) + "\n" + result.get('output', '')
                return result

            log_progress(f"开始更新到版本: {update_info['version']}")
            
            # 添加重要文件备份步骤
            log_progress("开始备份重要文件...")
            if not self.backup_important_files():
                log_progress("备份重要文件", False, "备份失败")
                if not self.prompt_continue_update():
                    return {'success': False, 'output': '\n'.join(progress)}
            else:
                log_progress("备份重要文件", True, "备份完成")
            
            log_progress("开始下载更新...")
            if not self.download_update(update_info['download_url'], callback=log_progress):
                log_progress("下载更新", False, "下载失败")
                return {'success': False, 'output': '\n'.join(progress)}
            log_progress("下载更新", True, "下载完成")
                
            log_progress("开始备份当前版本...")
            if not self.backup_current_version():
                log_progress("备份当前版本", False, "备份失败")
                return {'success': False, 'output': '\n'.join(progress)}
            log_progress("备份当前版本", True, "备份完成")
                
            log_progress("开始应用更新...")
            success, new_dir = self.apply_update()
            if not success:
                log_progress("应用更新", False, "更新失败")
                log_progress("正在恢复之前的版本...")
                if not self.restore_from_backup():
                    log_progress("恢复备份", False, "恢复失败！请手动处理")
                return {'success': False, 'output': '\n'.join(progress)}
            log_progress("应用更新", True, "更新成功")
            
            # 合并配置文件：保留旧config.py所有原始内容，仅追加新版本中新增的配置项
            current_config = os.path.join(self.root_dir, "config.py")
            new_config = os.path.join(self.temp_dir, "extracted", new_dir, "config.py")
            if os.path.exists(current_config) and os.path.exists(new_config):
                log_progress("开始合并配置文件...")
                Updater.merge_config(current_config, new_config, current_config)
                log_progress("合并配置文件", True, "配置合并完成")
            else:
                log_progress("合并配置文件", False, "配置文件不存在，无法合并")
                if not os.path.exists(current_config) and os.path.exists(new_config):
                    shutil.copy2(new_config, current_config)
                    log_progress("复制新配置文件", True, "已复制新的配置文件")
            
            # 确保更新version.json文件 (添加明确的日志记录)
            log_progress("更新版本信息文件...")
            try:
                with open(self.version_file, 'w', encoding='utf-8') as f:
                    json.dump({
                        'version': update_info['version'],
                        'last_update': update_info.get('last_update', ''),
                        'description': update_info.get('description', '')
                    }, f, indent=4, ensure_ascii=False)
                log_progress("更新版本信息文件", True, f"成功更新到版本 {update_info['version']}")
            except Exception as e:
                log_progress("更新版本信息文件", False, f"无法写入版本文件: {str(e)}")
                logger.error(f"写入版本文件失败: {str(e)}")
            
            # 验证版本文件是否已更新
            log_progress("验证版本文件...")
            try:
                if os.path.exists(self.version_file):
                    with open(self.version_file, 'r', encoding='utf-8') as f:
                        version_data = json.load(f)
                        if version_data.get('version') == update_info['version']:
                            log_progress("验证版本文件", True, "版本文件已正确更新")
                        else:
                            log_progress("验证版本文件", False, "版本文件内容不正确，重新写入")
                            # 重新写入版本文件
                            with open(self.version_file, 'w', encoding='utf-8') as f:
                                json.dump({
                                    'version': update_info['version'],
                                    'last_update': update_info.get('last_update', ''),
                                    'description': update_info.get('description', '')
                                }, f, indent=4, ensure_ascii=False)
                else:
                    log_progress("验证版本文件", False, "版本文件不存在，创建新文件")
                    # 创建版本文件
                    with open(self.version_file, 'w', encoding='utf-8') as f:
                        json.dump({
                            'version': update_info['version'],
                            'last_update': update_info.get('last_update', ''),
                            'description': update_info.get('description', '')
                        }, f, indent=4, ensure_ascii=False)
            except Exception as e:
                log_progress("验证版本文件", False, f"验证失败: {str(e)}")
                
            self.cleanup()
            log_progress("清理临时文件", True)
            log_progress("更新完成", True, "请重启程序以应用更新")

            if success:
                print("\n" + "="*50)
                print("\033[32m\n更新成功!请关闭此窗口并重新运行Run.bat以应用更新。\n\033[0m")

                print("="*50 + "\n")
                # 仅在交互式入口（__main__ 会设置该环境变量）阻塞等待用户关窗口；
                # 以库方式调用 update() 时直接返回，避免调用线程永久挂起。
                if os.environ.get("WEAUTO_BLOCK_ON_UPDATE"):
                    while True:
                        try:
                            time.sleep(1)
                        except KeyboardInterrupt:
                            break

            return {'success': True, 'output': '\n'.join(progress)}

        except Exception as e:
            logger.error(f"更新失败: {str(e)}")
            return {'success': False, 'error': str(e), 'output': f"更新失败: {str(e)}"}

    def prompt_continue_update(self) -> bool:
        """当备份失败时，询问用户是否继续更新"""
        print("\n\033[31m警告：备份重要文件失败！\033[0m")
        while True:
            choice = input("是否仍要继续更新?\n输入'y'继续更新 / 输入'n'取消更新: ").lower().strip()
            if choice in ('y', 'yes'):
                print("\n继续更新...")
                return True
            elif choice in ('n', 'no'):
                print("\n已取消更新")
                return False
            print("请输入 y 或 n")

    @staticmethod
    def parse_config_file(path):
        """
        解析配置文件，返回字典和原始行列表
        """
        config = {}
        with open(path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        assign_pattern = re.compile(r'^(\w+)\s*=\s*(.+)$')
        for line in lines:
            line_strip = line.strip()
            if not line_strip or line_strip.startswith("#"):
                continue
            match = assign_pattern.match(line_strip)
            if match:
                key = match.group(1)
                value_str = match.group(2)
                try:
                    value = ast.literal_eval(value_str)
                except Exception:
                    value = value_str
                config[key] = value
        return config, lines

    @staticmethod
    def merge_config(old_path, new_path, output_path):
        """
        合并 old_path 与 new_path 两个配置文件：
        1. 保留旧文件的所有原始内容（包括注释和格式）。
        2. 对于新文件中出现而旧文件中不存在的配置项，将其原始赋值行追加到文件末尾。
        """
        # 读取旧文件原始内容
        with open(old_path, 'r', encoding='utf-8') as f:
            old_lines = f.readlines()

        # 提取旧文件中已存在的配置键
        assign_pattern = re.compile(r'^(\w+)\s*=\s*(.+)$')
        old_keys = set()
        for line in old_lines:
            m = assign_pattern.match(line.strip())
            if m:
                old_keys.add(m.group(1))

        # 读取新文件的所有行
        with open(new_path, 'r', encoding='utf-8') as f:
            new_lines = f.readlines()

        # 收集新文件中新增的配置项的原始行
        added_lines = []
        for line in new_lines:
            m = assign_pattern.match(line.strip())
            if m:
                key = m.group(1)
                if key not in old_keys:
                    added_lines.append(line)

        if added_lines:
            old_lines.append("\n# 以下为新增配置项\n")
            old_lines.extend(added_lines)

        with open(output_path, 'w', encoding='utf-8') as f:
            f.writelines(old_lines)


def check_and_update():
    """检查并执行更新"""
    logger.info("开始检查GitHub更新...")
    updater = Updater()
    return updater.update()


if __name__ == "__main__":
    try:
        # 交互式入口（Run.bat 直接运行 updater.py）：更新成功后阻塞等待用户关闭窗口
        os.environ["WEAUTO_BLOCK_ON_UPDATE"] = "1"
        result = check_and_update()
        if not result['success']:
            print("\n更新失败，请查看日志")
        else:
            print(result['output'])
    except KeyboardInterrupt:
        print("\n用户取消更新")
    except Exception as e:
        print(f"\n发生错误: {str(e)}")
