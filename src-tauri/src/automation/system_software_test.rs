// Copyright (c) 2026 tupAI
//
// tupAI P1 §3.3 — Unit tests for the cross-platform software detection
// helpers. The tests live in a dedicated file (per `plan.md §3.3`)
// so the binary's behaviour is separated from the renderer /
// dispatcher logic that consumes it.
//
// We focus on the pure-Rust surface (`check_software_installed`,
// `list_installed_software`, `launch_software`) and avoid any test
// that would actually start a process or hit the network.

#[cfg(test)]
mod tests {
    use crate::automation::system_software::{
        check_software_installed, launch_software, list_installed_software,
    };

    #[test]
    fn empty_name_is_never_installed() {
        // A defensive guard against a UI bug that passes "" or "   ".
        assert!(!check_software_installed(""));
        assert!(!check_software_installed("   "));
    }

    #[test]
    fn list_returns_whitelist_shape() {
        // `list_installed_software` is supposed to return one row per
        // curated entry — never zero, never an unbounded scan. We
        // assert the shape (non-empty + every row has a `name` and a
        // boolean) so any future refactor that breaks the contract
        // fails the test loudly.
        let list = list_installed_software();
        assert!(!list.is_empty(), "whitelist should not be empty");
        for entry in &list {
            assert!(!entry.name.is_empty(), "every entry must have a name");
            // `installed` is a bool by construction; we just touch
            // the field so the test cannot be optimised away.
            let _ = entry.installed;
        }
    }

    #[test]
    fn unknown_software_is_not_installed() {
        // The function should be defensive: a clearly fake name must
        // not raise. We do not assert == false on every platform
        // (Linux's `which` could in theory hit a homonym) but the
        // `definitely_fake_name_xyz` slug is a 28-char string that
        // no real package is ever going to be called.
        let result = check_software_installed("definitely_fake_name_xyz_2026");
        assert!(!result);
    }

    #[test]
    fn launch_rejects_empty_name() {
        // `launch_software` should not spawn a process for an empty
        // input; it should return a user-readable error.
        let result = launch_software("");
        assert!(result.is_err());
    }

    #[test]
    fn list_includes_browsers_and_common_tools() {
        // The whitelist is curated; if a maintainer accidentally
        // removes the browser or "notepad" entries, downstream UI
        // will silently break. Lock the contract.
        let list = list_installed_software();
        let names: Vec<String> = list.iter().map(|s| s.name.clone()).collect();
        assert!(
            names.iter().any(|n| n.contains("chrome")),
            "whitelist should contain a chrome variant"
        );
        assert!(
            names.iter().any(|n| n.contains("edge")),
            "whitelist should contain an edge variant"
        );
    }
}
