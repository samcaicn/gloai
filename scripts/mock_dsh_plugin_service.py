#!/usr/bin/env python3
# Copyright (c) 2026 tupAI
#
# Local mock of a DSH runtime's plugin service, for end-to-end testing of the
# "接通 DSH 插件服务" fetch path (scripts/test_dsh_plugin_fetch.mjs mirrors the
# exact Rust fetch+normalize logic in src-tauri/src/commands/plugin_market.rs).
#
# Serves:
#   GET /plugins         -> top-level JSON array of plugins
#   GET /plugins-wrapped -> { "plugins": [ ... ] }  (object-wrapped variant)
#   GET /health          -> { "ok": true }
#
# Usage: python mock_dsh_plugin_service.py [port]   (default 8787)

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PLUGINS = [
    {
        "id": "translator",
        "name": "实时翻译",
        "description": "DSH 运行时翻译插件",
        "stars": 42,
        "homepage": "https://dsh.local/plugins/translator",
        "language": "TypeScript",
        "license": "MIT",
        "version": "1.2.3",
    },
    {
        "id": "summarizer",
        "name": "摘要器",
        "description": "长文摘要",
        "stars": 17,
        "language": "Python",
    },
    {
        "id": "ocr",
        "name": "OCR 识别",
        "description": "图片文字识别",
        "stars": 8,
        "language": "Rust",
    },
]


class Handler(BaseHTTPRequestHandler):
    def _send(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/plugins-wrapped"):
            self._send({"plugins": PLUGINS})
        elif self.path.startswith("/plugins"):
            self._send(PLUGINS)
        elif self.path == "/health":
            self._send({"ok": True})
        else:
            self._send({"error": "not found"}, 404)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8787
    print(f"mock DSH plugin service on http://127.0.0.1:{port}")
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
