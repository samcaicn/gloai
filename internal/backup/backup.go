// Package backup uploads the SQLite database files to object storage as a
// whole-file snapshot.
//
// Scope note: since internal/coldstore landed, this is NO LONGER the primary
// path for chat data. Snapshots are engine-private, O(database) per upload and
// unqueryable — they exist purely as disaster recovery for the parts of the hot
// tier that are not in the open dataset (accounts, apps, bots, config). Chat
// messages and vectors go to the cold tier incrementally in an open format
// instead, so this job runs on a slow timer and keeps only the newest few
// generations.
package backup

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	_ "modernc.org/sqlite"

	"github.com/ceoadmin/CEOadmin/internal/storage"
)

// Prefix is the key namespace of full-database snapshots.
const Prefix = "sqlite-backup/"

// Backup uploads the SQLite database files (main db + -wal + -shm) to object
// storage under a timestamped generation directory. A WAL checkpoint is
// attempted first so the uploaded snapshot is consistent.
func Backup(ctx context.Context, obj storage.Store, dbPath string) error {
	checkpointWAL(dbPath)
	files := []string{dbPath}
	for _, suff := range []string{"-wal", "-shm"} {
		if _, err := os.Stat(dbPath + suff); err == nil {
			files = append(files, dbPath+suff)
		}
	}
	stamp := time.Now().UTC().Format("20060102T150405")
	for _, f := range files {
		data, err := os.ReadFile(f)
		if err != nil {
			return fmt.Errorf("backup read %s: %w", f, err)
		}
		key := fmt.Sprintf("%s%s/%s", Prefix, stamp, filepath.Base(f))
		if _, err := obj.Put(ctx, key, "application/octet-stream", data); err != nil {
			return fmt.Errorf("backup put %s: %w", f, err)
		}
	}
	return nil
}

// Prune keeps only the newest `keep` snapshot generations and deletes the rest.
// Without this, a snapshot timer grows the bucket without bound — the exact
// cost problem the incremental exporter exists to avoid.
//
// It is a no-op when the store cannot list or delete objects.
func Prune(ctx context.Context, obj storage.Store, keep int) (int, error) {
	if keep <= 0 {
		return 0, nil
	}
	lister, ok := obj.(storage.Lister)
	if !ok {
		return 0, nil
	}
	deleter, ok := obj.(storage.Deleter)
	if !ok {
		return 0, nil
	}

	objs, err := lister.List(ctx, Prefix)
	if err != nil {
		return 0, fmt.Errorf("backup prune list: %w", err)
	}
	gens := map[string][]string{}
	for _, o := range objs {
		rest := strings.TrimPrefix(o.Key, Prefix)
		stamp, _, ok := strings.Cut(rest, "/")
		if !ok || stamp == "" {
			continue
		}
		gens[stamp] = append(gens[stamp], o.Key)
	}
	if len(gens) <= keep {
		return 0, nil
	}

	stamps := make([]string, 0, len(gens))
	for s := range gens {
		stamps = append(stamps, s)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(stamps))) // stamps sort lexically == chronologically

	deleted := 0
	for _, stamp := range stamps[keep:] {
		for _, key := range gens[stamp] {
			if err := deleter.Delete(ctx, key); err != nil {
				return deleted, fmt.Errorf("backup prune delete %s: %w", key, err)
			}
			deleted++
		}
	}
	return deleted, nil
}

// checkpointWAL folds the WAL into the main db so a copied snapshot is
// consistent. Best-effort: failures are ignored and the db+-wal copy is still
// usable.
func checkpointWAL(dbPath string) {
	db, err := sql.Open("sqlite", "file:"+dbPath+"?mode=ro&_pragma=busy_timeout(2000)")
	if err != nil {
		return
	}
	defer db.Close()
	_, _ = db.Exec("PRAGMA wal_checkpoint(TRUNCATE)")
}
