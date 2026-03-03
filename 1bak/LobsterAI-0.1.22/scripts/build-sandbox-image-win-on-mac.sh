#!/bin/bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DOCKER_SCRIPT="${ROOT_DIR}/scripts/build-sandbox-image-docker.sh"

usage() {
  cat <<'EOF'
Usage:
  ./scripts/build-sandbox-image-win-on-mac.sh [--tool docker|podman]

Options:
  -t, --tool   Container tool to use (docker or podman). If omitted, auto-detects.
  -h, --help   Show this help message.
EOF
}

if [ "$(uname)" != "Darwin" ]; then
  echo "Error: This script is intended for macOS only." >&2
  exit 1
fi

if [ ! -x "${DOCKER_SCRIPT}" ]; then
  echo "Error: Missing executable script: ${DOCKER_SCRIPT}" >&2
  exit 1
fi

CONTAINER_TOOL=""
while [ $# -gt 0 ]; do
  case "$1" in
    -t|--tool)
      if [ $# -lt 2 ]; then
        echo "Error: missing value for $1" >&2
        usage
        exit 1
      fi
      CONTAINER_TOOL="$2"
      shift 2
      ;;
    --tool=*)
      CONTAINER_TOOL="${1#*=}"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown argument '$1'" >&2
      usage
      exit 1
      ;;
  esac
done

if [ -z "${CONTAINER_TOOL}" ]; then
  if command -v docker >/dev/null 2>&1; then
    CONTAINER_TOOL=docker
  elif command -v podman >/dev/null 2>&1; then
    CONTAINER_TOOL=podman
  else
    echo "Error: Neither docker nor podman found. Install one to continue." >&2
    exit 1
  fi
fi

if [ "${CONTAINER_TOOL}" != "docker" ] && [ "${CONTAINER_TOOL}" != "podman" ]; then
  echo "Error: unsupported tool '${CONTAINER_TOOL}'. Use docker or podman." >&2
  exit 1
fi

if ! command -v "${CONTAINER_TOOL}" >/dev/null 2>&1; then
  echo "Error: ${CONTAINER_TOOL} command not found." >&2
  if [ "${CONTAINER_TOOL}" = "docker" ]; then
    echo "Download: https://www.docker.com/products/docker-desktop/" >&2
  fi
  exit 1
fi

if ! "${CONTAINER_TOOL}" info >/dev/null 2>&1; then
  if [ "${CONTAINER_TOOL}" = "docker" ]; then
    echo "Error: Docker Desktop is not running. Please start Docker Desktop and retry." >&2
  else
    echo "Error: podman machine is not running. Start it and retry." >&2
    echo "Hint: podman machine start" >&2
  fi
  exit 1
fi

HOST_ARCH=$(uname -m)
if [ "${HOST_ARCH}" = "arm64" ] || [ "${HOST_ARCH}" = "aarch64" ]; then
  # Windows sandbox uses linux-amd64 image. On Apple Silicon, run amd64 container explicitly.
  echo "Apple Silicon detected. Building linux-amd64 image via emulated linux/amd64 container using ${CONTAINER_TOOL}..."
  CONTAINER_TOOL="${CONTAINER_TOOL}" CONTAINER_PLATFORM=linux/amd64 ARCHS=amd64 "${DOCKER_SCRIPT}"
elif [ "${HOST_ARCH}" = "x86_64" ]; then
  echo "Intel macOS detected. Building linux-amd64 image using ${CONTAINER_TOOL}..."
  CONTAINER_TOOL="${CONTAINER_TOOL}" ARCHS=amd64 "${DOCKER_SCRIPT}"
else
  echo "Error: Unsupported macOS architecture '${HOST_ARCH}'." >&2
  exit 1
fi

echo "Done. Windows sandbox image generated at:"
echo "  ${ROOT_DIR}/sandbox/image/out/linux-amd64.qcow2"
