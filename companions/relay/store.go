package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// Only the paired device identity is persisted. Phone access tokens never enter this store.
type diskState struct {
	DeviceHash string `json:"device_hash,omitempty"`
}
type store struct {
	mu   sync.Mutex
	path string
	data diskState
}

func openStore(path string) (*store, error) {
	s := &store{path: path}
	b, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return s, nil
	}
	if err != nil {
		return nil, err
	}
	if err = json.Unmarshal(b, &s.data); err != nil {
		return nil, err
	}
	return s, nil
}
func (s *store) view() diskState { s.mu.Lock(); defer s.mu.Unlock(); return s.data }
func (s *store) update(change func(*diskState) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	next := s.data
	if err := change(&next); err != nil {
		return err
	}
	b, err := json.Marshal(next)
	if err != nil {
		return err
	}
	if err = os.MkdirAll(filepath.Dir(s.path), 0700); err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(s.path), ".relay-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(b)
	}
	if err == nil {
		err = f.Sync()
	}
	closed := f.Close()
	if err == nil {
		err = closed
	}
	if err != nil {
		return err
	}
	if err = os.Rename(f.Name(), s.path); err != nil {
		return err
	}
	s.data = next
	return nil
}
func secret() string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic("secure random unavailable")
	}
	return base64.RawURLEncoding.EncodeToString(b)
}
func digest(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }
func matches(value, hash string) bool {
	return hash != "" && subtle.ConstantTimeCompare([]byte(digest(value)), []byte(hash)) == 1
}
