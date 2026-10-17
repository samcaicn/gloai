# -*- coding: utf-8 -*-
"""WeAuto 本地视觉识别模块（可选，全部失败时由 bot.py 回落云端）。

两条本地引擎，按 config.py 开关启用：

1. RapidOCR（PP-OCR ONNX，纯文字提取）
   - 依赖 rapidocr_onnxruntime（自带 PP-OCR 模型 ~15MB）+ onnxruntime 运行时
   - 按 GPU 信息自适应：有 DX12 显卡装 onnxruntime-directml（DirectML 加速，
     AMD/Intel/NVIDIA 通吃），无 GPU 装 CPU 版
   - 首次使用时从国内多 pip 镜像源依次尝试临时下载安装（清华→阿里→中科大→腾讯→上交→官方）
   - LOCAL_OCR_ENABLE / LOCAL_OCR_USE_GPU / LOCAL_OCR_AUTO_INSTALL

2. llama.cpp llama-server（视觉语义，默认 HunyuanOCR-1.5，Qwen3-VL 兜底）
   - 全自动：bot 启动时 start_prefetch() 守护线程按显卡情况下载组件——
     有卡（NVIDIA/AMD/Intel 通吃 Vulkan）下 Vulkan 版 llama-server，无卡下 CPU 版；
     模型从 hf-mirror 断点续传下载（~1GB）。下载就绪前识别直接回落云端。
   - OpenAI 兼容接口调用，与云端 Moonshot 同构
   - LOCAL_VISION_ENABLE / LOCAL_VISION_BASE_URL / ...

打包说明：运行时 pip 安装仅对源码 / .venv 运行方式有效；冻结 EXE 内
sys.executable 不是 Python 解释器，无法在线装包（RapidOCR 需打包前预装依赖）。
llama-server + 模型走文件下载，冻结 EXE 内同样生效；分发包需把
llama-server-{vulkan,cpu}-win64.zip 上传到 VISION_BIN_URLS 列出的分发地址。
"""

import base64
import logging
import os
import subprocess
import sys
import threading
import time

logger = logging.getLogger(__name__)

# 国内 pip 镜像源（依次回退，全部失败才算失败）
PIP_MIRRORS = [
    'https://pypi.tuna.tsinghua.edu.cn/simple',
    'https://mirrors.aliyun.com/pypi/simple/',
    'https://pypi.mirrors.ustc.edu.cn/simple/',
    'https://mirrors.cloud.tencent.com/pypi/simple',
    'https://mirror.sjtu.edu.cn/pypi/web/simple',
    'https://pypi.org/simple',
]

# 与 bot.py recognize_image_with_moonshot 相同的提示词，保证云端/本地行为一致
PROMPT_DESC = "请用中文描述这张图片的主要内容或主题。不要使用'这是'、'这张'等开头，直接描述。如果有文字，请包含在描述中。"
PROMPT_EMOJI = "请用中文简洁地描述这个聊天窗口最后一张表情包所表达的情绪、含义或内容。如果表情包含文字，请一并描述。注意：1. 只描述表情包本身，不要添加其他内容 2. 不要出现'这是'、'这个'等词语"

_install_lock = threading.Lock()
_ocr_engine = None          # RapidOCR 单例
_ocr_dml_patched = False    # DirectML 会话补丁只打一次
_ocr_install_failed = False # 安装失败缓存：本进程内不再反复重试多源下载
_dml_upgrade_tried = False  # CPU→DirectML 换装每进程只试一次
_gpu_info_cache = None      # GPU 型号列表缓存
_llamaserver_launched = False
_vision_dl_failed = False   # 视觉组件（bin/model）下载失败缓存
_prefetch_started = False   # 预取线程只起一次


def _popen_kwargs():
    """GUI 环境下拉起子进程不弹黑窗。"""
    flags = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
    return {'creationflags': flags} if flags else {}


def _is_frozen():
    return getattr(sys, 'frozen', False)


# ---------------------------------------------------------------- GPU 探测

def detect_gpu():
    """探测本机 GPU 型号列表；探测失败返回 []。结果进程内缓存。"""
    global _gpu_info_cache
    if _gpu_info_cache is not None:
        return _gpu_info_cache
    gpus = []
    try:
        out = subprocess.run(
            ['wmic', 'path', 'win32_VideoController', 'get', 'name'],
            capture_output=True, text=True, timeout=10, **_popen_kwargs())
        for line in (out.stdout or '').splitlines()[1:]:
            name = line.strip()
            if name and 'VideoController' not in name:
                gpus.append(name)
    except Exception:
        try:
            out = subprocess.run(
                ['powershell', '-NoProfile', '-Command',
                 '(Get-CimInstance Win32_VideoController).Name'],
                capture_output=True, text=True, timeout=20, **_popen_kwargs())
            gpus = [l.strip() for l in (out.stdout or '').splitlines() if l.strip()]
        except Exception as e:
            logger.warning(f"[本地OCR] GPU 探测失败（按无 GPU 处理）: {e}")
    # 过滤虚拟/基础显示适配器
    gpus = [g for g in gpus if g and 'microsoft basic display adapter' not in g.lower()]
    _gpu_info_cache = gpus
    logger.info(f"[本地OCR] 检测到 GPU: {gpus or '无（使用 CPU）'}")
    return gpus


def _want_gpu(use_gpu):
    """解析 LOCAL_OCR_USE_GPU：auto 按探测结果；true/false 强制。"""
    val = str(use_gpu or 'auto').strip().lower()
    if val in ('true', '1', 'yes', 'on', 'gpu'):
        return True
    if val in ('false', '0', 'no', 'off', 'cpu'):
        return False
    return bool(detect_gpu())  # auto


# ---------------------------------------------------------------- 多源安装

def _pip_install(packages, timeout=600):
    """按多镜像源依次尝试 pip 安装；任一源成功即返回 True。"""
    if _is_frozen():
        logger.warning('[本地OCR] 冻结 EXE 环境无法在线安装依赖，请在打包时预装: '
                       + ' '.join(packages))
        return False
    for mirror in PIP_MIRRORS:
        cmd = [sys.executable, '-m', 'pip', 'install', '-i', mirror,
               '--prefer-binary', '--no-warn-script-location'] + list(packages)
        try:
            logger.info(f"[本地OCR] 正在从镜像源安装: {' '.join(packages)}  <-  {mirror}")
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
            if r.returncode == 0:
                logger.info(f"[本地OCR] 安装成功（源: {mirror}）")
                return True
            tail = ((r.stderr or '') + (r.stdout or ''))[-300:]
            logger.warning(f"[本地OCR] 源 {mirror} 安装失败，尝试下一个源。{tail}")
        except Exception as e:
            logger.warning(f"[本地OCR] 源 {mirror} 安装异常，尝试下一个源: {e}")
    return False


def _pip_uninstall(package):
    try:
        subprocess.run([sys.executable, '-m', 'pip', 'uninstall', '-y', package],
                       capture_output=True, text=True, timeout=120, **_popen_kwargs())
    except Exception:
        pass


def _swap_onnxruntime(target_pkg):
    """把 onnxruntime 运行时换成 target_pkg（onnxruntime-directml 或 onnxruntime）。

    两个包都占用 onnxruntime 模块名，必须先卸再装，绝不能共存。
    """
    _pip_uninstall('onnxruntime')
    _pip_uninstall('onnxruntime-directml')
    return _pip_install([target_pkg])


def ensure_rapidocr(use_gpu='auto'):
    """确保 RapidOCR + onnxruntime 可用；按 GPU 信息选择 DirectML / CPU 版。

    返回 (ok: bool, info: str)。ok=True 时可直接调用 RapidOCR。
    """
    global _ocr_install_failed, _dml_upgrade_tried
    with _install_lock:
        try:
            import rapidocr_onnxruntime  # noqa: F401
            import onnxruntime
            provs = onnxruntime.get_available_providers()
            # 已可用但只有 CPU 运行时、而配置要求 GPU：换装 DirectML 版
            if (_want_gpu(use_gpu) and 'DmlExecutionProvider' not in provs
                    and not _is_frozen() and not _dml_upgrade_tried):
                _dml_upgrade_tried = True
                logger.info('[本地OCR] 当前为 CPU 运行时，按配置换装 DirectML GPU 版（换装后重启程序生效）')
                if _swap_onnxruntime('onnxruntime-directml'):
                    return True, '已换装 DirectML GPU 版，重启程序后生效'
                logger.warning('[本地OCR] DirectML 版换装失败，恢复 CPU 版')
                _pip_install(['onnxruntime'])
            return True, f'rapidocr 已就绪, providers={provs}'
        except ImportError:
            pass
        if _ocr_install_failed:
            return False, '依赖自动安装此前已失败，本进程内不再重试（重启后重试或手动安装）'

        gpu = _want_gpu(use_gpu)
        gpus = detect_gpu()
        logger.info(f'[本地OCR] 开始安装依赖（GPU={gpu}, 检测到显卡={gpus or "无"}）')

        # 注意：rapidocr_onnxruntime 的依赖名就叫 onnxruntime，若与 onnxruntime-directml
        # 放在同一命令安装会因模块名冲突必然失败 —— 必须先装 rapidocr，再单独替换运行时。
        if not _pip_install(['rapidocr_onnxruntime']):
            _ocr_install_failed = True
            return False, '多镜像源安装 rapidocr_onnxruntime 均失败，请检查网络或手动安装'

        if gpu:
            if _swap_onnxruntime('onnxruntime-directml'):
                return True, f'安装完成（DirectML GPU 版）, GPU={gpus}'
            logger.warning('[本地OCR] DirectML 版安装失败，回退 CPU 版')
            if _pip_install(['onnxruntime']):
                return True, 'GPU 版安装失败，已回退 CPU 版'
            _ocr_install_failed = True
            return False, 'onnxruntime 运行时安装失败'

        # CPU 模式：清掉可能存在的 directml 版，避免模块名共存冲突
        _pip_uninstall('onnxruntime-directml')
        return True, f'安装完成（CPU 版）, GPU={gpus}'


def _patch_dml_sessions():
    """让 RapidOCR 内部创建的 InferenceSession 优先走 DirectML（GPU）。"""
    global _ocr_dml_patched
    if _ocr_dml_patched:
        return
    _ocr_dml_patched = True
    try:
        import onnxruntime
        if 'DmlExecutionProvider' not in onnxruntime.get_available_providers():
            logger.info('[本地OCR] 无 DirectML 提供程序，使用 CPU 推理')
            return
        _orig_sess = onnxruntime.InferenceSession

        def _dml_sess(*args, **kwargs):
            for providers in (['DmlExecutionProvider', 'CPUExecutionProvider'],
                              ['CPUExecutionProvider']):
                try:
                    kwargs['providers'] = providers
                    return _orig_sess(*args, **kwargs)
                except Exception:
                    continue
            return _orig_sess(*args, **kwargs)

        onnxruntime.InferenceSession = _dml_sess
        logger.info('[本地OCR] 已启用 DirectML GPU 加速')
    except Exception as e:
        logger.warning(f'[本地OCR] DirectML 补丁失败（用 CPU）: {e}')


def _get_ocr_engine(use_gpu='auto'):
    """RapidOCR 单例（线程安全由调用方 _install_lock/惰性调用保证）。"""
    global _ocr_engine
    if _ocr_engine is None:
        _patch_dml_sessions()
        from rapidocr_onnxruntime import RapidOCR
        _ocr_engine = RapidOCR()
    return _ocr_engine


def recognize_image_ocr(image_path, use_gpu='auto', auto_install=True):
    """本地 OCR：识别图片中的文字。

    返回 (ok, text, info)。ok=True 且 text 非空表示识别到文字；
    ok=True 且 text 为空表示图中无文字；ok=False 表示引擎不可用。
    """
    try:
        if auto_install:
            ok, info = ensure_rapidocr(use_gpu)
            if not ok:
                return False, '', info
        result = _get_ocr_engine(use_gpu)(image_path)
        # rapidocr_onnxruntime 1.x 返回 (result, elapse)；兼容裸返回
        items = result[0] if isinstance(result, tuple) else result
        if not items:
            return True, '', '图中未识别到文字'
        lines = []
        for item in items:
            try:
                txt = item[1] if isinstance(item, (list, tuple)) else item.get('text', '')
            except Exception:
                continue
            if txt:
                lines.append(str(txt).strip())
        text = ' '.join(l for l in lines if l).strip()
        return True, text, 'ok'
    except ImportError as e:
        return False, '', f'依赖未安装且 auto_install=False: {e}'
    except Exception as e:
        return False, '', f'OCR 识别异常: {e}'


# ------------------------------------------------- 视觉组件自动下载（装机自适应）

# llama-server 运行时分发包：**全部公开多源**，无需任何私有通道/认证。
# 来源说明：官方 llama.cpp 自 v0.7.0 起 release 不再附二进制，选固定版本
# b9637（官方预编译，MIT）。三镜像 HF（国内 hf-mirror.com / hf-mirror.net /
# aifasthub.com，海外 huggingface.co 官方）+ 官方 GH release + 自家 CI 的
# Release 资产（.github/workflows/vision-bin.yml 每月自动重建，Release 资产
# 匿名可下，artifact 匿名下载是 401）。逐源依次尝试。
_LLAMA_VER = 'b9637'
_LLAMA_REPO = f'limnmn/llama.cpp-{_LLAMA_VER}-Windows-Runtime'
_LLAMA_ZIPS = {
    'vulkan': f'llama-{_LLAMA_VER}-bin-win-vulkan-x64.zip',
    'cpu': f'llama-{_LLAMA_VER}-bin-win-cpu-x64.zip',
}
_HF_MIRRORS = [
    'https://hf-mirror.com',       # 国内主镜像（实测最快）
    'https://hf-mirror.net',       # 国内备用镜像
    'https://aifasthub.com',       # 国内备用镜像
    'https://huggingface.co',      # 海外官方（兜底）
]
VISION_BIN_URLS = {
    variant: [
        *(f'{m}/{_LLAMA_REPO}/resolve/main/{z}' for m in _HF_MIRRORS),
        f'https://github.com/ggml-org/llama.cpp/releases/download/{_LLAMA_VER}/{z}',
        f'https://github.com/samcaicn/gloai/releases/download/vision-bin/{z}',
    ]
    for variant, z in _LLAMA_ZIPS.items()
}

# HunyuanOCR-1.5 模型（~1B；Q4_K_M 338MB + mmproj 699MB），三镜像 + 海外官方
_VISION_REPO = 'prithivMLmods/HunyuanOCR-1.5-GGUF-Updated'
VISION_MODEL_URLS = [
    (fname, [f'{m}/{_VISION_REPO}/resolve/main/{fname}' for m in _HF_MIRRORS])
    for fname in ('HunyuanOCR.Q4_K_M.gguf', 'HunyuanOCR.mmproj-q8_0.gguf')
]


def _gpu_vendor():
    """按 detect_gpu() 结果归类显卡厂商：'nvidia'/'amd'/'intel'/'other'，无卡返回 None。"""
    joined = ' '.join(detect_gpu()).lower()
    if not joined:
        return None
    if any(k in joined for k in ('nvidia', 'geforce', 'rtx', 'gtx', 'quadro', 'tesla')):
        return 'nvidia'
    if any(k in joined for k in ('amd', 'radeon', ' radeon', 'ati')):
        return 'amd'
    if any(k in joined for k in ('intel', 'arc', 'iris', 'uhd', 'hd graphics')):
        return 'intel'
    return 'other'   # 有显卡但认不出厂商：仍按有卡处理（Vulkan 通吃）


def _remote_size(url):
    """HEAD 取远端文件大小（用于断点续传完整性判断）；失败返回 0。"""
    try:
        import requests
        r = requests.head(url, timeout=10, allow_redirects=True)
        return int(r.headers.get('Content-Length', 0) or 0)
    except Exception:
        return 0


def _download_file(url, dest, desc=''):
    """流式下载 + 断点续传。成功返回 True；失败保留半成品供下次续传。"""
    try:
        import requests
    except ImportError:
        logger.warning('[视觉下载] 缺少 requests，无法自动下载')
        return False
    resume = os.path.getsize(dest) if os.path.exists(dest) else 0
    headers = {'Range': f'bytes={resume}-'} if resume else {}
    name = desc or os.path.basename(dest)
    logger.info(f'[视觉下载] {name}: 开始下载/续传（已有 {resume/1048576:.0f}MB）')
    t0 = time.time()
    last = 0.0
    total = resume
    try:
        with requests.get(url, headers=headers, stream=True, timeout=(10, 60),
                          allow_redirects=True) as r:
            r.raise_for_status()
            mode = 'ab' if (resume and r.status_code == 206) else 'wb'
            if mode == 'wb':
                total = 0
            with open(dest, mode) as f:
                for chunk in r.iter_content(512 * 1024):
                    if not chunk:
                        continue
                    f.write(chunk)
                    total += len(chunk)
                    now = time.time()
                    if now - last >= 8:
                        last = now
                        speed = (total - resume) / max(now - t0, 0.1) / 1048576
                        logger.info(f'[视觉下载] {name}: {total/1048576:.0f}MB ({speed:.2f}MB/s)')
        logger.info(f'[视觉下载] {name}: 完成 {total/1048576:.0f}MB，耗时 {time.time()-t0:.0f}s')
        return True
    except Exception as e:
        logger.warning(f'[视觉下载] {name} 下载失败（下次续传）: {e}')
        return False


def ensure_vision_model(model_dir=''):
    """确保 GGUF + mmproj 模型就位；缺失则从 hf-mirror 下载（断点续传）。

    返回 (ok, info)。下载量大（约 1GB），**只允许在后台预取线程调用**，
    严禁在消息处理流程（can_send_messages=False）中调用。
    """
    global _vision_dl_failed
    _, gguf, mmproj = _vision_paths(model_dir=model_dir)
    if gguf and mmproj and os.path.exists(gguf) and os.path.exists(mmproj):
        return True, '模型已就绪'
    if _vision_dl_failed:
        return False, '此前下载失败，本进程内不再重试'
    script_dir = os.path.dirname(os.path.abspath(__file__))
    model_dir = (model_dir or '').strip() or os.path.join(script_dir, 'vision_server', 'models')
    try:
        os.makedirs(model_dir, exist_ok=True)
    except Exception as e:
        return False, f'模型目录不可写: {e}'
    for fname, urls in VISION_MODEL_URLS:
        dest = os.path.join(model_dir, fname)
        local = os.path.getsize(dest) if os.path.exists(dest) else 0
        remote = 0
        for u in urls:                       # 任一镜像探到大小即用
            remote = _remote_size(u)
            if remote:
                break
        if local and local >= (remote or local):
            continue    # 已完整
        ok_dl = False
        for u in urls:                       # 逐镜像下载（断点续传跨源兼容）
            if _download_file(u, dest, desc=fname):
                ok_dl = True
                break
        if not ok_dl:
            _vision_dl_failed = True
            return False, f'{fname} 下载失败（全部 {len(urls)} 源均不可达）'
    return True, '模型下载完成'


def ensure_llama_bin(server_exe=''):
    """确保 llama-server.exe 就位：本地已有 > packages/ 本地 zip 解压 > 多源下载。

    按显卡自适应：有卡（任意厂商）用 Vulkan 版；无卡用 CPU 版。
    返回 (ok, exe_path)。
    """
    global _vision_dl_failed
    script_dir = os.path.dirname(os.path.abspath(__file__))
    exe = (server_exe or '').strip() or os.path.join(script_dir, 'vision_server', 'llama-server.exe')
    if os.path.exists(exe):
        return True, exe
    if _vision_dl_failed:
        return False, exe
    variant = 'cpu' if not _gpu_vendor() else 'vulkan'
    zip_name = _LLAMA_ZIPS[variant]
    pkg_dir = os.path.join(script_dir, 'vision_server', 'packages')
    try:
        os.makedirs(os.path.dirname(exe), exist_ok=True)
        os.makedirs(pkg_dir, exist_ok=True)
    except Exception as e:
        return False, f'目录不可写: {e}'
    local_zip = os.path.join(pkg_dir, zip_name)
    if not os.path.exists(local_zip):
        ok = False
        for url in VISION_BIN_URLS[variant]:
            if _download_file(url, local_zip, desc=zip_name):
                ok = True
                break
        if not ok:
            _vision_dl_failed = True
            return False, exe
    try:
        import zipfile
        with zipfile.ZipFile(local_zip) as z:
            for info in z.infolist():
                base = os.path.basename(info.filename)
                if base and base.lower().endswith(('.exe', '.dll')):
                    with z.open(info) as src, open(os.path.join(os.path.dirname(exe), base), 'wb') as dst:
                        dst.write(src.read())
        logger.info(f'[视觉下载] {zip_name} 解压完成（{variant} 版，适配 {"无显卡" if variant=="cpu" else "GPU"}）')
        return True, exe
    except Exception as e:
        logger.warning(f'[视觉下载] {zip_name} 解压失败: {e}')
        return False, exe


def vision_ready(server_exe='', model_dir=''):
    """识别流程用轻量就绪检查：llama-server.exe 与 GGUF/mmproj 全部在位才走本地视觉。"""
    exe, gguf, mmproj = _vision_paths(server_exe, model_dir)
    return bool(exe and os.path.exists(exe) and gguf and mmproj
                and os.path.exists(gguf) and os.path.exists(mmproj))


def start_prefetch(model_dir=''):
    """bot 启动时后台预取 llama-server 运行时与模型（守护线程，绝不阻塞主流程）。

    下载期间收到图片 → vision_ready()=False → 直接回落云端；下载完成后
    后续图片即走本地。任何异常都只记日志，不影响 bot 其他功能。
    """
    global _prefetch_started
    if _prefetch_started:
        return
    _prefetch_started = True

    def _worker():
        try:
            ok, exe = ensure_llama_bin()
            logger.info(f'[视觉预取] llama-server: {"就绪" if ok else "未就绪（下载失败或源不可达）"}')
            ok2, info2 = ensure_vision_model(model_dir)
            logger.info(f'[视觉预取] 模型: {info2}')
        except Exception as e:
            logger.warning(f'[视觉预取] 异常（不影响主流程）: {e}')

    threading.Thread(target=_worker, name='VisionPrefetch', daemon=True).start()


# ------------------------------------------------- llama.cpp 视觉语义引擎

def _vision_paths(server_exe='', model_dir='', prefer_model=''):
    """返回 (exe, gguf, mmproj)；缺任一返回 None 项。

    prefer_model 非空时优先选文件名包含该串的模型；否则默认优先顺序为
    HunyuanOCR* > Qwen3-VL* > 其余字母序（HunyuanOCR 为精度最高的 OCR 专用模型）。
    每个主模型自动配对同名前缀的 mmproj 投影文件。
    """
    script_dir = os.path.dirname(os.path.abspath(__file__))
    model_dir = (model_dir or '').strip() or os.path.join(script_dir, 'vision_server', 'models')
    exe = (server_exe or '').strip() or os.path.join(script_dir, 'vision_server', 'llama-server.exe')
    if not os.path.isdir(model_dir):
        return exe, None, None
    names = sorted(os.listdir(model_dir))
    # mmproj 识别不能只看开头（HunyuanOCR.mmproj-q8_0.gguf 的 mmproj 在中间）
    main_models = [n for n in names
                   if n.lower().endswith('.gguf') and 'mmproj' not in n.lower()]
    if not main_models:
        return exe, None, None

    def rank(n):
        low = n.lower()
        if prefer_model and prefer_model.lower() in low:
            return 0
        if low.startswith('hunyuanocr'):
            return 1
        if low.startswith('qwen3-vl') or 'qwen3_vl' in low:
            return 2
        return 3

    main_models.sort(key=rank)
    gguf = os.path.join(model_dir, main_models[0])
    # 配对 mmproj：按模型主体名（第一个 '.' 之前，如 hunyuanocr）前缀匹配；
    # 找不到同名再退化到任意第一个 mmproj（单模型目录场景）
    stem = main_models[0][:-len('.gguf')].lower() if main_models[0].lower().endswith('.gguf') else main_models[0].lower()
    brand = stem.split('.')[0]
    mms = [n for n in names
           if 'mmproj' in n.lower() and n.lower().endswith('.gguf')]
    mmproj = None
    for n in mms:                       # pass1: 主体名开头（HunyuanOCR.mmproj-*.gguf）
        if n.lower().startswith(brand):
            mmproj = os.path.join(model_dir, n)
            break
    if mmproj is None:                  # pass2: 主体名包含于文件名
        for n in mms:
            if brand in n.lower():
                mmproj = os.path.join(model_dir, n)
                break
    if mmproj is None and mms:          # pass3: 退化取第一个
        mmproj = os.path.join(model_dir, mms[0])
    return exe, gguf, mmproj


def _llamaserver_alive(base_url, timeout=2):
    """真正的就绪检查：/health 200 才算就绪（llama-server 模型加载中会 503，
    /models 在加载期也会应答，不能作为就绪依据）。"""
    try:
        import requests
        root = base_url.rstrip('/')
        if root.endswith('/v1'):
            root = root[:-3]
        # /health：就绪 200 / 加载中 503
        try:
            r = requests.get(f'{root}/health', timeout=timeout)
            if r.status_code == 200:
                return True
            if r.status_code == 503:
                return False
        except Exception:
            pass
        # 兜底（老版本无 /health）：/v1/models 必须 200
        try:
            r = requests.get(f"{base_url.rstrip('/')}/models", timeout=timeout)
            return r.status_code == 200
        except Exception:
            return False
    except Exception:
        return False


def ensure_llamaserver(base_url, server_exe='', model_dir='', wait_secs=12):
    """本地 vision server 未运行时自动拉起一次（每进程只尝试一次）。

    任何失败都静默返回 False，绝不影响主流程。
    """
    global _llamaserver_launched
    base_url = (base_url or 'http://127.0.0.1:8080/v1').strip().rstrip('/')
    if _llamaserver_alive(base_url):
        return True
    if _is_frozen() or _llamaserver_launched:
        # 冻结 EXE/已试过：不重复拉起
        return _llamaserver_alive(base_url, timeout=1)
    _llamaserver_launched = True
    try:
        from urllib.parse import urlparse
        exe, gguf, mmproj = _vision_paths(server_exe, model_dir)
        if not (exe and os.path.exists(exe) and gguf and mmproj):
            logger.warning('[本地视觉] 自动启动失败：找不到 llama-server.exe 或模型文件'
                           '（参见 vision_server/ 目录说明）')
            return False
        parsed = urlparse(base_url)
        host = parsed.hostname or '127.0.0.1'
        port = parsed.port or 8080
        # 显卡自适应：有任意显卡全部层卸载到 GPU；无卡全 CPU
        ngl = '99' if detect_gpu() else '0'
        cmd = [exe, '-m', gguf, '--mmproj', mmproj,
               '--host', host, '--port', str(port), '-ngl', ngl, '-c', '4096']
        logger.info(f'[本地视觉] 自动启动 llama-server（端口 {port}）')
        subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         **_popen_kwargs())
        for _ in range(int(wait_secs)):
            if _llamaserver_alive(base_url, timeout=1):
                logger.info('[本地视觉] llama-server 就绪')
                return True
            time.sleep(1)
        logger.warning('[本地视觉] llama-server 启动超时，本次识别回落云端')
        return False
    except Exception as e:
        logger.warning(f'[本地视觉] llama-server 自动启动异常: {e}')
        return False


def recognize_image_llamaserver(image_path, base_url, api_key='', model='',
                                is_emoji=False, timeout=15):
    """调用本机 llama.cpp llama-server（OpenAI 兼容）做视觉语义识别。

    成功返回原始描述文本（不带前缀），失败返回 ''。
    """
    try:
        import requests
        url = (base_url or 'http://127.0.0.1:8080/v1').strip().rstrip('/')
        if not url.endswith('/v1'):
            url += '/v1'
        headers = {'Content-Type': 'application/json'}
        if (api_key or '').strip():
            headers['Authorization'] = f"Bearer {api_key.strip()}"
        with open(image_path, 'rb') as f:
            b64 = base64.b64encode(f.read()).decode('utf-8')
        try:
            timeout_sec = int(timeout)
        except (TypeError, ValueError):
            timeout_sec = 15
        data = {'messages': [{'role': 'user', 'content': [
            {'type': 'image_url', 'image_url': {'url': f'data:image/jpeg;base64,{b64}'}},
            {'type': 'text', 'text': PROMPT_EMOJI if is_emoji else PROMPT_DESC},
        ]}]}
        if (model or '').strip():
            data['model'] = model.strip()
        # 503 = 模型仍在加载（与 /health 就绪检查之间的窗口期），短暂重试
        last_resp = None
        for attempt in range(3):
            r = requests.post(f'{url}/chat/completions', headers=headers, json=data,
                              timeout=(3, timeout_sec))
            if r.status_code == 503 and attempt < 2:
                time.sleep(2)
                continue
            r.raise_for_status()
            last_resp = r
            break
        return (last_resp.json()['choices'][0]['message']['content'] or '').strip()
    except Exception as e:
        logger.warning(f'[本地视觉] llama-server 识别失败: {e}')
        return ''
