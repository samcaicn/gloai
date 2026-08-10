package storage

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// FSStore implements Store using the local filesystem.
type FSStore struct {
	root      string // absolute root directory for stored files
	publicURL string // URL prefix, e.g. "/api/v1/media"
}

// NewFS creates a new FSStore rooted at the given directory.
func NewFS(root, publicURL string) (*FSStore, error) {
	abs, err := filepath.Abs(root)
	if err != nil {
		return nil, fmt.Errorf("storage: fs abs: %w", err)
	}
	if err := os.MkdirAll(abs, 0750); err != nil {
		return nil, fmt.Errorf("storage: fs init: %w", err)
	}
	if publicURL == "" {
		publicURL = "/api/v1/media"
	}
	return &FSStore{root: abs, publicURL: publicURL}, nil
}

// safePath resolves a key to an absolute path and ensures it stays under root.
func (f *FSStore) safePath(key string) (string, error) {
	// Reject absolute paths and clean the key first
	clean := filepath.FromSlash(key)
	if filepath.IsAbs(clean) {
		return "", errors.New("storage: absolute path rejected")
	}
	p := filepath.Clean(filepath.Join(f.root, clean))
	if !strings.HasPrefix(p, f.root+string(os.PathSeparator)) {
		return "", errors.New("storage: path traversal rejected")
	}
	return p, nil
}

func (f *FSStore) Put(_ context.Context, key, contentType string, data []byte) (string, error) {
	path, err := f.safePath(key)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0750); err != nil {
		return "", fmt.Errorf("storage: fs mkdir %s: %w", key, err)
	}
	if err := os.WriteFile(path, data, 0640); err != nil {
		return "", fmt.Errorf("storage: fs put %s: %w", key, err)
	}
	return f.URL(key), nil
}

func (f *FSStore) Get(_ context.Context, key string) ([]byte, error) {
	path, err := f.safePath(key)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("storage: fs get %s: %w", key, err)
	}
	return data, nil
}

// List enumerates every object whose key starts with prefix. Only the subtree
// implied by the prefix is walked, so listing one partition never scans the
// whole bucket. A missing prefix directory is not an error — it yields an empty
// result, matching S3 semantics.
func (f *FSStore) List(_ context.Context, prefix string) ([]ObjectInfo, error) {
	root := f.root
	if i := strings.LastIndex(prefix, "/"); i >= 0 {
		p, err := f.safePath(prefix[:i])
		if err != nil {
			return nil, err
		}
		root = p
	}

	var out []ObjectInfo
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if os.IsNotExist(err) {
				return nil // prefix has nothing stored under it yet
			}
			return err
		}
		if d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(f.root, p)
		if err != nil {
			return nil
		}
		key := filepath.ToSlash(rel)
		if !strings.HasPrefix(key, prefix) {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		out = append(out, ObjectInfo{Key: key, Size: info.Size(), LastModified: info.ModTime()})
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("storage: fs list %s: %w", prefix, err)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Key < out[j].Key })
	return out, nil
}

// Delete removes an object. Deleting a missing key is a no-op (S3 semantics).
func (f *FSStore) Delete(_ context.Context, key string) error {
	path, err := f.safePath(key)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("storage: fs delete %s: %w", key, err)
	}
	return nil
}

func (f *FSStore) URL(key string) string {
	return f.publicURL + "/" + key
}
