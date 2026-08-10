package storage

import (
	"context"
	"time"
)

// Store is the storage interface for media files.
// Implementations: S3Store (MinIO/S3), FSStore (local filesystem).
type Store interface {
	Put(ctx context.Context, key, contentType string, data []byte) (string, error)
	Get(ctx context.Context, key string) ([]byte, error)
	URL(key string) string
}

// ObjectInfo describes a single stored object as returned by Lister.
type ObjectInfo struct {
	Key          string
	Size         int64
	LastModified time.Time
}

// Lister is an OPTIONAL capability on top of Store: enumerating objects under a
// key prefix.
//
// It is what makes the cold tier queryable: partition pruning, watermark
// rebuild and cold statistics are all List-only operations (no payload
// download). Both S3Store and FSStore implement it; callers must type-assert
// and degrade gracefully when a Store does not.
type Lister interface {
	List(ctx context.Context, prefix string) ([]ObjectInfo, error)
}

// Deleter is an OPTIONAL capability on top of Store: removing an object.
// Used for snapshot retention (keeping only the newest N full backups).
type Deleter interface {
	Delete(ctx context.Context, key string) error
}
