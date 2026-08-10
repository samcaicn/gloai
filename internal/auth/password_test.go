package auth

import "testing"

func TestHashAndCheck(t *testing.T) {
	pw := "my-secret-password"
	hash := HashPassword(pw)

	if !CheckPassword(pw, hash) {
		t.Error("correct password should match")
	}
	if CheckPassword("wrong-password", hash) {
		t.Error("wrong password should not match")
	}
	if CheckPassword(pw, "") {
		t.Error("empty hash should not match")
	}
	if CheckPassword(pw, "short") {
		t.Error("short hash should not match")
	}
}

func TestHashUnique(t *testing.T) {
	h1 := HashPassword("same")
	h2 := HashPassword("same")
	if h1 == h2 {
		t.Error("same password should produce different hashes (different salts)")
	}
}
