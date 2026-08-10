package coldstore

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
)

// Codec is the pluggable wire format of a part object. It is the single seam
// between "how rows are laid out on object storage" (partitions, watermarks, manifests —
// stable) and "how bytes are encoded" (JSONL today, Parquet tomorrow).
//
// Implementations must round-trip Row losslessly and must be safe for
// concurrent use.
type Codec interface {
	// Name is the registry key, e.g. "jsonl" or "parquet".
	Name() string
	// Ext is the object file extension, without a dot.
	Ext() string
	// ContentType is the MIME type stored with the object.
	ContentType() string
	// Encode serialises a whole part.
	Encode(rows []Row) ([]byte, error)
	// Decode parses a whole part.
	Decode(data []byte) ([]Row, error)
}

// ReadHinter is an OPTIONAL Codec capability: rendering the expression an
// external engine uses to read the parts, given a glob over the dataset. It
// keeps format knowledge inside the codec instead of leaking it into the
// manifest writer.
type ReadHinter interface {
	ReadHint(glob string) string
}

// JSONL encodes a part as newline-delimited JSON: zero dependencies, appendable
// by construction, and readable by DuckDB (read_json_auto), Spark, Pandas and
// plain `jq`. This is the default.
type JSONL struct{}

func (JSONL) Name() string        { return "jsonl" }
func (JSONL) Ext() string         { return "jsonl" }
func (JSONL) ContentType() string { return "application/x-ndjson" }

// ReadHint implements ReadHinter.
func (JSONL) ReadHint(glob string) string { return fmt.Sprintf("read_json_auto('%s')", glob) }

func (JSONL) Encode(rows []Row) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf) // NewEncoder already appends '\n' per value
	for _, r := range rows {
		if err := enc.Encode(r); err != nil {
			return nil, fmt.Errorf("coldstore: jsonl encode conv=%s seq=%d: %w", r.ConvID, r.Seq, err)
		}
	}
	return buf.Bytes(), nil
}

func (JSONL) Decode(data []byte) ([]Row, error) {
	var out []Row
	sc := bufio.NewScanner(bytes.NewReader(data))
	// Chat messages can be long; allow generous lines (embeddings dominate).
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	line := 0
	for sc.Scan() {
		line++
		b := bytes.TrimSpace(sc.Bytes())
		if len(b) == 0 {
			continue
		}
		var r Row
		if err := json.Unmarshal(b, &r); err != nil {
			return nil, fmt.Errorf("coldstore: jsonl decode line %d: %w", line, err)
		}
		out = append(out, r)
	}
	if err := sc.Err(); err != nil {
		return nil, fmt.Errorf("coldstore: jsonl scan: %w", err)
	}
	return out, nil
}

var (
	codecMu sync.RWMutex
	codecs  = map[string]Codec{"jsonl": JSONL{}}
	byExt   = map[string]Codec{"jsonl": JSONL{}}
)

// RegisterCodec makes a format available to CodecByName and to Reader's
// per-part extension dispatch. Call it from an init() in a build-tagged file to
// add Parquet without forcing the dependency on everyone.
func RegisterCodec(c Codec) {
	codecMu.Lock()
	defer codecMu.Unlock()
	codecs[c.Name()] = c
	byExt[c.Ext()] = c
}

// CodecByName resolves a configured format name. An empty name yields
// DefaultCodec.
func CodecByName(name string) (Codec, error) {
	if name == "" {
		return DefaultCodec(), nil
	}
	key := strings.ToLower(name)
	codecMu.RLock()
	c, ok := codecs[key]
	codecMu.RUnlock()
	if !ok {
		if hint, excluded := excludedFormats[key]; excluded {
			return nil, fmt.Errorf("coldstore: format %q is not in this build: %s", name, hint)
		}
		return nil, fmt.Errorf("coldstore: unknown format %q (available: %s); "+
			"register one with coldstore.RegisterCodec", name, strings.Join(codecNames(), ", "))
	}
	return c, nil
}

// excludedFormats maps a format that ships with the project but can be compiled
// out to the reason it is missing. Without this a `-tags noparquet` binary would
// report parquet as merely "unknown", sending the operator hunting for a typo
// instead of a build flag.
var excludedFormats = map[string]string{
	"parquet": "this binary was built with -tags noparquet; rebuild without that tag to enable parquet",
}

// CodecByExt resolves the codec for an existing part object by its file
// extension, which is how a bucket can hold a mix of .jsonl (historical) and
// .parquet (post-migration) parts. Note this keys on the extension, not the
// format name — they coincide for the built-in codecs but need not in general.
func CodecByExt(ext string) (Codec, bool) {
	codecMu.RLock()
	c, ok := byExt[strings.ToLower(ext)]
	codecMu.RUnlock()
	return c, ok
}

func codecNames() []string {
	codecMu.RLock()
	defer codecMu.RUnlock()
	out := make([]string, 0, len(codecs))
	for n := range codecs {
		out = append(out, n)
	}
	sort.Strings(out)
	return out
}
