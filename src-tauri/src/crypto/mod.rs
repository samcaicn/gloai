// Copyright (c) 2026 MeeJoy

// tupAI P0 §4 — Encrypted local storage
//
// On-disk format:
//   [ 4 bytes : version (little-endian u32; always 1) ]
//   [ 12 bytes: AES-256-GCM nonce ]
//   [ N bytes : ciphertext + GCM tag ]
//
// The 32-byte key is derived from `(password, hardware_fingerprint)`
// using Argon2id. The fingerprint is stable across reboots on the same
// machine, so the same password produces the same key; the password is
// provided by the UI on demand and never persisted in plaintext.
//
// `EncryptedStorage::with_decrypted` is the only API the rest of the
// app should use to touch plaintext — it zeroizes the scratch buffer on
// the way out so that a stray `Vec<u8>` doesn't end up lingering on the
// heap after the call returns.

pub mod storage;
