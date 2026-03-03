# 沙箱 Runtime（macOS）

这里提供用于构建 Cowork 内置 VM 沙箱 **runtime 包** 的脚本。  
runtime 指宿主机侧的 QEMU 可执行文件及其依赖 dylib 和数据文件，**不包含在镜像里**。

## 依赖

- macOS
- Homebrew
- QEMU：`brew install qemu`
- dylibbundler：`brew install dylibbundler`

## 构建

```bash
# 构建当前机器架构
bash sandbox/runtime/build-runtime-macos.sh

# 或指定架构
ARCH=arm64 bash sandbox/runtime/build-runtime-macos.sh
ARCH=x64   bash sandbox/runtime/build-runtime-macos.sh
```

### 说明

- 建议在对应架构机器上构建。
- Apple Silicon 需要构建 `x64` 时，请安装 x86 Homebrew 到 `/usr/local`，
  并设置 `BREW_PREFIX=/usr/local`。

## 输出

文件输出到：

```
sandbox/runtime/out/
  runtime-darwin-arm64.tar.gz
  runtime-darwin-x64.tar.gz
  runtime-darwin-*.tar.gz.sha256
```

## 在应用中使用

将 tar.gz 上传到 CDN，并配置以下环境变量之一：

- `COWORK_SANDBOX_RUNTIME_URL`（单文件地址），或
- `COWORK_SANDBOX_BASE_URL` + `COWORK_SANDBOX_RUNTIME_VERSION`

使用 base URL 时，应用会按以下路径请求：

```
${BASE_URL}/${VERSION}/runtime-darwin-arm64.tar.gz
${BASE_URL}/${VERSION}/runtime-darwin-x64.tar.gz
```

