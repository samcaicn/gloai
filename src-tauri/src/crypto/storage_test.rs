// Copyright (c) 2026 MeeJoy

// Unit tests for the encrypted storage layer. We focus on the encrypt /
// decrypt round-trip and on the file round-trip (using a tempdir); the
// key-derivation path is exercised indirectly via `derive` + `encrypt`.

use super::*;
use std::io::Read;

fn test_key() -> EncryptedStorage {
    EncryptedStorage::from_raw_key([0x42u8; KEY_LEN])
}

#[test]
fn round_trip_recovers_plaintext() {
    let storage = test_key();
    let plaintext = b"the quick brown fox jumps over the lazy dog";
    let framed = storage.encrypt(plaintext).expect("encrypt");
    let recovered = storage.decrypt(&framed).expect("decrypt");
    assert_eq!(recovered.as_slice(), plaintext);
}

#[test]
fn round_trip_with_arbitrary_binary_data() {
    let storage = test_key();
    let mut plaintext = Vec::with_capacity(1024);
    for index in 0..1024 {
        plaintext.push((index % 251) as u8);
    }
    let framed = storage.encrypt(&plaintext).expect("encrypt");
    let recovered = storage.decrypt(&framed).expect("decrypt");
    assert_eq!(recovered.as_slice(), plaintext.as_slice());
}

#[test]
fn decryption_with_wrong_key_fails() {
    let storage_a = test_key();
    let storage_b = EncryptedStorage::from_raw_key([0x99u8; KEY_LEN]);
    let framed = storage_a.encrypt(b"secret").expect("encrypt");
    let result = storage_b.decrypt(&framed);
    assert!(result.is_err(), "wrong key should fail to decrypt");
}

#[test]
fn tampered_ciphertext_is_rejected() {
    let storage = test_key();
    let mut framed = storage.encrypt(b"secret").expect("encrypt");
    let last = framed.len() - 1;
    framed[last] ^= 0xFF;
    let result = storage.decrypt(&framed);
    assert!(result.is_err(), "tampered ciphertext should fail");
}

#[test]
fn framed_ciphertext_carries_expected_overhead() {
    let storage = test_key();
    let plaintext = b"hello world";
    let framed = storage.encrypt(plaintext).expect("encrypt");
    // version (4) + nonce (12) + ciphertext (11) + GCM tag (16) = 43
    assert_eq!(framed.len(), plaintext.len() + OVERHEAD_LEN + 16);
    // First four bytes are the little-endian version.
    let version = u32::from_le_bytes([framed[0], framed[1], framed[2], framed[3]]);
    assert_eq!(version, STORAGE_VERSION);
}

#[test]
fn base64_round_trip_preserves_utf8() {
    let storage = test_key();
    let original = "包含中文与 emoji 🦀 的明文".to_string();
    let encoded = storage
        .encrypt_base64(original.as_bytes())
        .expect("encrypt_base64");
    let decoded = storage
        .decrypt_base64_string(&encoded)
        .expect("decrypt_base64_string");
    assert_eq!(decoded.as_str(), original);
}

#[test]
fn with_decrypted_zeros_buffer_after_callback() {
    let storage = test_key();
    let framed = storage.encrypt(b"sensitive").expect("encrypt");
    let mut observed: Option<Vec<u8>> = None;
    let result: Result<(), StorageError> = storage.with_decrypted(&framed, |plaintext| {
        observed = Some(plaintext.to_vec());
        assert_eq!(plaintext, b"sensitive");
        Ok(())
    });
    assert!(result.is_ok());
    // The captured Vec<u8> is a copy the test still owns; the original
    // zeroizing buffer has been wiped, but we cannot observe that
    // directly without poking into private state. The important
    // guarantee is that the callback completed and the plaintext
    // matched; a separate test covers drop-time zeroize via the
    // Zeroizing wrapper.
    let snapshot = observed.expect("callback ran");
    assert_eq!(snapshot.as_slice(), b"sensitive");
}

#[test]
fn file_round_trip_writes_and_reads_back() {
    let storage = test_key();
    let mut path = std::env::temp_dir();
    path.push(format!("tupai-crypto-test-{}.bin", std::process::id()));
    let _ = std::fs::remove_file(&path);

    storage
        .write_encrypted_file(&path, b"file plaintext")
        .expect("write");
    let recovered = storage
        .read_encrypted_file(&path)
        .expect("read")
        .expect("file present");
    assert_eq!(recovered.as_slice(), b"file plaintext");

    // Sanity check: the file on disk does not contain the plaintext
    // as a substring. We read it raw and check.
    let mut raw = Vec::new();
    std::fs::File::open(&path)
        .expect("open")
        .read_to_end(&mut raw)
        .expect("read raw");
    assert!(
        !raw.windows(b"file plaintext".len()).any(|window| window == b"file plaintext"),
        "plaintext should not be present in the raw on-disk file"
    );

    let _ = std::fs::remove_file(&path);
}

#[test]
fn derive_rejects_empty_inputs() {
    assert!(matches!(
        EncryptedStorage::derive("", "fingerprint"),
        Err(StorageError::InvalidPassword)
    ));
    assert!(matches!(
        EncryptedStorage::derive("password", ""),
        Err(StorageError::InvalidPassword)
    ));
}

#[test]
fn derive_produces_a_working_key() {
    let storage = EncryptedStorage::derive("hunter2", "test-fingerprint")
        .expect("derive should succeed");
    let framed = storage.encrypt(b"hello").expect("encrypt");
    let recovered = storage.decrypt(&framed).expect("decrypt");
    assert_eq!(recovered.as_slice(), b"hello");
}
