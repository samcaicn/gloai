# Sandbox Runtime (macOS)

This folder contains helper scripts to build the **runtime package** used by
Cowork's built-in VM sandbox. The runtime is the host-side QEMU binary plus its
dependent dylibs and data files. It is **not** included in the VM image.

## Requirements

- macOS
- Homebrew
- QEMU: `brew install qemu`
- dylibbundler: `brew install dylibbundler`

## Build

```bash
# build for the current machine architecture
bash sandbox/runtime/build-runtime-macos.sh

# or specify architecture
ARCH=arm64 bash sandbox/runtime/build-runtime-macos.sh
ARCH=x64   bash sandbox/runtime/build-runtime-macos.sh
```

### Notes

- It is recommended to build on the matching architecture.
- On Apple Silicon, to build `x64`, install x86 Homebrew under `/usr/local`
  and set `BREW_PREFIX=/usr/local`.

## Output

Files are written to:

```
sandbox/runtime/out/
  runtime-darwin-arm64.tar.gz
  runtime-darwin-x64.tar.gz
  runtime-darwin-*.tar.gz.sha256
```

## Use In App

Upload the tarballs to your CDN and configure one of the following:

- `COWORK_SANDBOX_RUNTIME_URL` (single file URL), or
- `COWORK_SANDBOX_BASE_URL` + `COWORK_SANDBOX_RUNTIME_VERSION`

When using the base URL, the app expects:

```
${BASE_URL}/${VERSION}/runtime-darwin-arm64.tar.gz
${BASE_URL}/${VERSION}/runtime-darwin-x64.tar.gz
```

