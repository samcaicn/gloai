package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
)

// HashPassword creates a salted SHA-256 hash. Format: "salt:hash".
// For production, consider bcrypt/argon2 — this is a lightweight default.
func HashPassword(password string) string {
	salt := make([]byte, 16)
	_, _ = rand.Read(salt)
	saltHex := hex.EncodeToString(salt)
	hash := sha256.Sum256([]byte(saltHex + ":" + password))
	return fmt.Sprintf("%s:%s", saltHex, hex.EncodeToString(hash[:]))
}

// CheckPassword verifies a password against a "salt:hash" string.
func CheckPassword(password, stored string) bool {
	if len(stored) < 34 { // minimum: 32 hex salt + ":" + 1 char
		return false
	}
	// Find salt (first 32 hex chars before ":")
	saltHex := stored[:32]
	expectedHash := stored[33:]
	hash := sha256.Sum256([]byte(saltHex + ":" + password))
	actual := hex.EncodeToString(hash[:])
	return subtle.ConstantTimeCompare([]byte(actual), []byte(expectedHash)) == 1
}
