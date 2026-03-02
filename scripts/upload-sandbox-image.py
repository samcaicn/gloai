#!/usr/bin/env python3
"""
Upload sandbox VM images to Luna NOS CDN.

Usage:
    python scripts/upload-sandbox-image.py [--arch amd64|arm64|all] [--input-dir PATH]

This script:
1. Reads the built qcow2 images from sandbox/image/out/
2. Uploads them to Luna NOS CDN
3. Prints the CDN URLs for updating coworkSandboxRuntime.ts
"""

import os
import sys
import hashlib
import argparse
import requests

LUNA_NOS_URL = os.environ.get("LUNA_NOS_URL", "")
LUNA_NOS_PRODUCT = os.environ.get("LUNA_NOS_PRODUCT", "")
LUNA_NOS_SUCCESS_CODE = 0

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR = os.path.dirname(SCRIPT_DIR)
DEFAULT_INPUT_DIR = os.path.join(ROOT_DIR, "sandbox", "image", "out")


def sha256_file(file_path: str) -> str:
    """Calculate SHA256 hash of a file."""
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while True:
            chunk = f.read(8192)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def upload_file(file_path: str) -> str | None:
    """Upload a file to Luna NOS and return the CDN URL."""
    file_name = os.path.basename(file_path)
    file_size = os.path.getsize(file_path)

    # Determine MIME type
    if file_name.endswith(".qcow2"):
        media_type = "application/octet-stream"
    elif file_name.endswith(".gz"):
        media_type = "application/gzip"
    else:
        media_type = "application/octet-stream"

    print(f"  Uploading {file_name} ({file_size:,} bytes)...")

    with open(file_path, "rb") as f:
        files = {"file": (file_name, f, media_type)}
        data = {"product": LUNA_NOS_PRODUCT, "useHttps": "true"}

        try:
            response = requests.post(LUNA_NOS_URL, files=files, data=data, timeout=600)
            response.raise_for_status()
        except requests.exceptions.RequestException as e:
            print(f"  ERROR: Upload failed: {e}")
            return None

    result = response.json()
    if result.get("code") == LUNA_NOS_SUCCESS_CODE:
        url = result.get("data", {}).get("url")
        if url:
            print(f"  OK: {url}")
            return url
        else:
            print(f"  ERROR: No URL in response: {result}")
            return None
    else:
        print(f"  ERROR: Upload failed (code={result.get('code')}): {result.get('msg')}")
        return None


def main():
    parser = argparse.ArgumentParser(description="Upload sandbox VM images to CDN")
    parser.add_argument(
        "--arch",
        choices=["amd64", "arm64", "all"],
        default="all",
        help="Architecture to upload (default: all)",
    )
    parser.add_argument(
        "--input-dir",
        default=DEFAULT_INPUT_DIR,
        help=f"Input directory (default: {DEFAULT_INPUT_DIR})",
    )
    args = parser.parse_args()

    if not LUNA_NOS_URL or not LUNA_NOS_PRODUCT:
        print("Error: Environment variables LUNA_NOS_URL and LUNA_NOS_PRODUCT must be set.")
        print("Example:")
        print('  set LUNA_NOS_URL=https://your-upload-endpoint/upload')
        print('  set LUNA_NOS_PRODUCT=your-product-name')
        sys.exit(1)

    input_dir = args.input_dir
    if not os.path.isdir(input_dir):
        print(f"Error: Input directory not found: {input_dir}")
        print("Run the build first: scripts\\build-sandbox-image.bat")
        sys.exit(1)

    archs = ["amd64", "arm64"] if args.arch == "all" else [args.arch]
    results = {}

    print("=" * 60)
    print("  Upload sandbox VM images to CDN")
    print("=" * 60)
    print()

    for arch in archs:
        qcow2_path = os.path.join(input_dir, f"linux-{arch}.qcow2")
        if not os.path.isfile(qcow2_path):
            print(f"[{arch}] Skipped: {qcow2_path} not found")
            continue

        file_hash = sha256_file(qcow2_path)
        print(f"[{arch}] File: {qcow2_path}")
        print(f"[{arch}] SHA256: {file_hash}")

        url = upload_file(qcow2_path)
        if url:
            results[arch] = {"url": url, "sha256": file_hash}
        else:
            print(f"[{arch}] FAILED to upload")

        print()

    if not results:
        print("No images were uploaded successfully.")
        sys.exit(1)

    # Print summary with code to update
    print("=" * 60)
    print("  Upload Summary")
    print("=" * 60)
    print()

    for arch, info in results.items():
        print(f"  {arch}:")
        print(f"    URL:    {info['url']}")
        print(f"    SHA256: {info['sha256']}")
        print()

    print("-" * 60)
    print("  Update the following in src/main/libs/coworkSandboxRuntime.ts:")
    print("-" * 60)
    print()

    if "arm64" in results:
        print(f"const DEFAULT_SANDBOX_IMAGE_URL_ARM64 = '{results['arm64']['url']}';")
    if "amd64" in results:
        print(f"const DEFAULT_SANDBOX_IMAGE_URL_AMD64 = '{results['amd64']['url']}';")

    print()
    print("Done!")


if __name__ == "__main__":
    main()
