// Copyright (c) 2026 tupAI
//
// UIRPA skill-level AES-256-GCM wrapper.
//
// The encryption primitive used by `LocalSkillStorage`. We
// intentionally do *not* use the project-wide
// `crypto::EncryptedStorage` here:
//
//   * `EncryptedStorage` is built around Argon2id key derivation
//     from a password + a hardware fingerprint, and frames the
//     ciphertext with a `[version | nonce | ciphertext+tag]`
//     blob. That framing is great for the "user-set password"
//     flow but the skill layer sometimes needs a *raw* key (e.g.
//     tests, a `keyring`-backed secret, or a key the executor
//     pre-derived once and cached in `UirpaState`).
//   * The skill layer's on-disk format is defined by
//     `storage.rs`, not by `EncryptedStorage` — keeping the
//     primitives separate lets the two encodings evolve
//     independently.
//
// The API surface mirrors the contract spelled out in
// §3.5:
//
//   * `new(master_key)`    — take a 32-byte raw key
//   * `encrypt(plaintext)` — return (ciphertext, nonce, tag)
//   * `decrypt(ciphertext, nonce, tag)` — return plaintext
//
// The 32-byte key is wrapped in a `Zeroizing<[u8; 32]>` so it is
// wiped on drop. The nonce returned by `encrypt` is fresh per
// call (12 random bytes from `getrandom`).

use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use zeroize::{Zeroize, Zeroizing};

const KEY_LEN: usize = 32;
const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;

/// AES-256-GCM encrypt / decrypt with explicit `(ciphertext,
/// nonce, tag)` triple so the storage layer can lay the bytes
/// out however it likes.
pub struct SkillDecryptor {
    key: Zeroizing<[u8; KEY_LEN]>,
}

impl SkillDecryptor {
    /// Wrap a 32-byte raw key. The key is copied into a
    /// `Zeroizing<[u8; 32]>` so it is wiped on drop.
    pub fn new(master_key: [u8; KEY_LEN]) -> Self {
        Self {
            key: Zeroizing::new(master_key),
        }
    }

    /// Encrypt `plaintext`. Returns the ciphertext (without
    /// trailing tag), a fresh 12-byte nonce, and the 16-byte GCM
    /// authentication tag.
    pub fn encrypt(
        &self,
        plaintext: &[u8],
    ) -> Result<(Vec<u8>, [u8; NONCE_LEN], [u8; TAG_LEN]), String> {
        let cipher =
            Aes256Gcm::new_from_slice(self.key.as_slice()).map_err(|e| {
                format!("skill decryptor: failed to init cipher: {}", e)
            })?;

        let mut nonce_bytes = [0u8; NONCE_LEN];
        getrandom::getrandom(&mut nonce_bytes)
            .map_err(|e| format!("skill decryptor: getrandom failed: {}", e))?;
        let nonce = Nonce::from_slice(&nonce_bytes);

        // `aes-gcm` appends the 16-byte tag to the returned
        // ciphertext; split it off so we can hand the caller a
        // (ciphertext, tag) pair per the contract.
        let sealed = cipher
            .encrypt(nonce, plaintext)
            .map_err(|e| format!("skill decryptor: encrypt failed: {}", e))?;
        if sealed.len() < TAG_LEN {
            return Err(format!(
                "skill decryptor: cipher returned {} bytes (< {} tag)",
                sealed.len(),
                TAG_LEN
            ));
        }
        let split = sealed.len() - TAG_LEN;
        let mut tag = [0u8; TAG_LEN];
        tag.copy_from_slice(&sealed[split..]);
        let ciphertext = sealed[..split].to_vec();

        Ok((ciphertext, nonce_bytes, tag))
    }

    /// Decrypt the `(ciphertext, nonce, tag)` triple. Returns
    /// the plaintext as a `Vec<u8>`. The caller is responsible
    /// for zeroizing the result if it contains secrets.
    pub fn decrypt(
        &self,
        ciphertext: &[u8],
        nonce: &[u8; NONCE_LEN],
        tag: &[u8; TAG_LEN],
    ) -> Result<Vec<u8>, String> {
        let cipher =
            Aes256Gcm::new_from_slice(self.key.as_slice()).map_err(|e| {
                format!("skill decryptor: failed to init cipher: {}", e)
            })?;
        let nonce_obj = Nonce::from_slice(nonce);

        // Re-attach the tag to the ciphertext — that is the
        // layout `aes-gcm::Aead::decrypt` expects.
        let mut sealed = Vec::with_capacity(ciphertext.len() + TAG_LEN);
        sealed.extend_from_slice(ciphertext);
        sealed.extend_from_slice(tag);

        cipher
            .decrypt(nonce_obj, sealed.as_slice())
            .map_err(|e| format!("skill decryptor: decrypt failed: {}", e))
    }
}

impl Drop for SkillDecryptor {
    fn drop(&mut self) {
        // Belt-and-braces: even though `Zeroizing` already wipes
        // on drop, we explicitly zero again so future refactors
        // that swap the wrapper (e.g. a `Box<[u8]>`) don't lose
        // the guarantee.
        self.key.zeroize();
    }
}

impl std::fmt::Debug for SkillDecryptor {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        // Never leak the key bytes — only the type signature.
        f.debug_struct("SkillDecryptor").finish_non_exhaustive()
    }
}
