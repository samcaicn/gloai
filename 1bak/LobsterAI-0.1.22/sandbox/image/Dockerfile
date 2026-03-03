ARG BASE_IMAGE=ubuntu:22.04
FROM ${BASE_IMAGE}

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    bash \
    ca-certificates \
    curl \
    dosfstools \
    e2fsprogs \
    kpartx \
    parted \
    qemu-utils \
    rsync \
    tar \
    udev \
    util-linux \
    xz-utils \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

ENTRYPOINT ["/bin/bash"]
