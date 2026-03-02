#!/bin/bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DOCKER_SCRIPT="${ROOT_DIR}/scripts/build-sandbox-image-docker.sh"

if [ "$(uname)" != "Darwin" ]; then
  echo "Error: This script is intended for macOS only." >&2
  exit 1
fi

if [ ! -x "${DOCKER_SCRIPT}" ]; then
  echo "Error: Missing executable script: ${DOCKER_SCRIPT}" >&2
  exit 1
fi

ARCH=${1:-}
if [ -z "${ARCH}" ]; then
  case "$(uname -m)" in
    arm64|aarch64) ARCH=arm64 ;;
    x86_64) ARCH=amd64 ;;
    *)
      echo "Error: Unsupported host architecture." >&2
      exit 1
      ;;
  esac
fi

case "${ARCH}" in
  amd64|arm64)
    ARCHS="${ARCH}" "${DOCKER_SCRIPT}"
    ;;
  all)
    ARCHS=amd64 "${DOCKER_SCRIPT}"
    ARCHS=arm64 "${DOCKER_SCRIPT}"
    ;;
  *)
    echo "Error: Unsupported arch '${ARCH}'. Use: amd64 | arm64 | all" >&2
    exit 1
    ;;
esac
