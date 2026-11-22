# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Tests for tools/mcp/check_catalog.py — offline validation and probe classification."""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import check_catalog  # noqa: E402


LOCALES = ["en", "zh", "zh-Hant", "ar", "fa", "ja", "ko", "es", "pt", "ru", "id", "tr"]


def copy(text):
    return {loc: text for loc in LOCALES}


def preset(pid="svc", transport="streamable-http", endpoint="https://api.example.com/mcp",
           auth="oauth", fields=(), command=(), package=None):
    p = {
        "id": pid,
        "name": "Example",
        "summary": copy("Example service"),
        "category": "dev",
        "transport": transport,
        "auth": auth,
        "officialSource": "https://example.com/docs",
    }
    if transport == "stdio":
        p["command"] = list(command) or ["svc"]
        if package:
            p["packageSpec"] = package
    else:
        p["endpoint"] = endpoint
    if fields:
        p["fields"] = [dict(f, label=copy(f.get("label", "Field"))) for f in fields]
    return p


class ValidateCatalogTest(unittest.TestCase):
    def test_validPresetPasses(self):
        catalog = {"formatVersion": 1, "presets": [preset()]}
        report = check_catalog.validate_catalog(catalog)
        self.assertTrue(report["ok"], report)
        self.assertEqual(report["valid"], 1)

    def test_invalidTransportIsFlagged(self):
        catalog = {"formatVersion": 1, "presets": [preset(), preset("bad", transport="bogus")]}
        report = check_catalog.validate_catalog(catalog)
        self.assertFalse(report["ok"])
        self.assertEqual(report["valid"], 1)
        self.assertEqual(report["invalid"][0]["id"], "bad")

    def test_missingLocaleIsFlagged(self):
        p = preset()
        p["summary"].pop("ar")
        report = check_catalog.validate_catalog({"formatVersion": 1, "presets": [p]})
        self.assertFalse(report["ok"])
        self.assertIn("missing locales", report["invalid"][0]["reason"])

    def test_duplicateIdIsFlagged(self):
        catalog = {"formatVersion": 1, "presets": [preset(), preset()]}
        report = check_catalog.validate_catalog(catalog)
        self.assertFalse(report["ok"])
        self.assertEqual(report["valid"], 1)

    def test_secretCannotEnterUrl(self):
        p = preset("x", auth="fields", endpoint="https://{{key}}.example.com",
                   fields=[{"id": "key", "secret": True, "required": True, "target": "url", "name": ""}])
        report = check_catalog.validate_catalog({"formatVersion": 1, "presets": [p]})
        self.assertFalse(report["ok"])

    def test_badVersionFails(self):
        p = preset("x", transport="stdio", package={"ecosystem": "npm", "name": "p", "version": "latest", "source": "https://example.com"})
        report = check_catalog.validate_catalog({"formatVersion": 1, "presets": [p]})
        self.assertFalse(report["ok"])


class ClassifyProbeTest(unittest.TestCase):
    def test_networkFailuresAreNeverVerified(self):
        for kind in ("network_error", "http_error", "timeout"):
            self.assertEqual(check_catalog.classify_probe(kind, "x"), "unreachable", kind)
            self.assertNotEqual(check_catalog.classify_probe(kind, "x"), "verified")

    def test_verifiedOnlyForRealHandshake(self):
        self.assertEqual(check_catalog.classify_probe("mcp_ok"), "verified")
        self.assertEqual(check_catalog.classify_probe("not_mcp"), "unsupported")

    def test_unknownKindFallsBackToUnsupported(self):
        self.assertEqual(check_catalog.classify_probe("something_else"), "unsupported")


class RunTest(unittest.TestCase):
    def test_runOfflineOnValidCatalog(self):
        out = check_catalog.run(json.dumps({"formatVersion": 1, "presets": [preset()]}), online=False)
        report = json.loads(out)
        self.assertTrue(report["ok"])
        self.assertNotIn("probed", report)

    def test_runRejectsWrongFormatVersion(self):
        out = check_catalog.run(json.dumps({"formatVersion": 99, "presets": []}))
        report = json.loads(out)
        self.assertFalse(report["ok"])


if __name__ == "__main__":
    unittest.main()
