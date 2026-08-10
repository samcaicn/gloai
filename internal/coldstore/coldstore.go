// Package coldstore lands chat messages + vectors on object storage as an open,
// incrementally-written, directly-queryable dataset — the cold tier of a
// hot/cold split whose hot tier is the live SQLite database.
//
// Any S3-compatible object storage works, since the transport is
// internal/storage's S3 client: MinIO (what docker-compose starts), 腾讯云 COS,
// AWS S3, and so on differ only by endpoint. Nothing here is vendor-specific.
//
// # Why this exists
//
// Uploading the whole .db file on a timer (see internal/backup) gives you a
// disaster-recovery copy and nothing else: it is O(database) per tick, the
// bytes are in an engine-private format, and the only way to read one message
// back is to download and open the entire database. This package is the other
// half — object storage as an open *system of record*:
//
//   - 增量 (incremental): each tick writes only messages newer than a
//     per-conversation watermark, as new immutable part objects. Nothing is
//     ever rewritten, so cost is O(new rows), not O(dataset).
//   - 可查 (queryable): data is laid out in Hive-style partitions of
//     newline-delimited JSON, so DuckDB / Spark / Pandas can read it in place
//     (`read_json_auto('s3://bucket/chat-vectors/conv=*/dt=*/*.jsonl')`), and
//     Reader can serve cold vector search from inside the Hub.
//
// # Layout
//
//	chat-vectors/
//	  _schema.json                                    # self-describing column list
//	  _manifest/state.json                            # per-conversation watermarks
//	  conv=<convID>/dt=<YYYY-MM-DD>/part-<lo>-<hi>.jsonl
//
// Part names encode the sequence range they cover, which buys two properties:
// the watermark can be rebuilt from a LIST alone (no downloads), and re-writing
// an already-exported range is idempotent because it lands on the same key.
//
// # Choosing the wire format
//
// The format lives behind the Codec interface and nothing else in this package
// knows about JSON or Parquet. Two codecs ship built in:
//
//   - Parquet (default) — columnar, zstd-compressed, with per-column
//     statistics. Roughly a third the size of JSONL on vector-heavy data, and
//     far faster to scan a subset of columns, which is what dominates once
//     embeddings are in the dataset.
//   - JSONL — newline-delimited JSON. Bigger, but inspectable with `jq` or any
//     text tool and the most forgiving on schema drift. Set
//     COLD_EXPORT_FORMAT=jsonl to prefer debuggability over size.
//
// Both codecs emit identical column names, and the partition layout,
// watermarks, reader and tiering logic are format-independent. Changing format
// needs no backfill: Reader dispatches per part on the file extension, so parts
// written before the switch stay readable next to new ones indefinitely.
//
// A dataset that spans formats records every format it contains in
// _schema.json, together with a read expression for each — reading only one of
// them would silently skip the rows stored in the others.
//
// # Slim builds
//
// parquet-go costs about 10 MB of binary. Deployments that will never use
// Parquet can compile it out:
//
//	go build -tags noparquet .
//
// Such a build defaults to JSONL, rejects COLD_EXPORT_FORMAT=parquet with an
// actionable error, and — importantly — refuses to read a dataset that already
// contains .parquet parts instead of silently returning the subset it can
// decode. Release artifacts are built without the tag, so Parquet is available
// out of the box.
//
// A third format only has to implement Codec and call RegisterCodec.
package coldstore

import (
	"fmt"
	"sort"
	"strconv"
	"strings"
	"time"
)

// DefaultPrefix is the root key prefix of the exported dataset.
const DefaultPrefix = "chat-vectors"

// SchemaVersion is bumped when the Row column set changes incompatibly.
const SchemaVersion = 1

// defaultCodec holds the format used when none is configured. JSONL is the
// floor because it is the only codec with no dependencies; the parquet codec
// promotes itself in its init when it is part of the build.
var defaultCodec Codec = JSONL{}

// DefaultCodec is the wire format used when none is configured. It is the
// single source of truth for that policy — config, CodecByName and the
// exporter all defer to it rather than each naming a format.
//
// It is Parquet in a normal build. Builds made with `-tags noparquet` drop the
// parquet-go dependency (~10 MB of binary) and fall back to JSONL; such a build
// can neither write nor read parquet parts, and says so loudly rather than
// skipping them.
func DefaultCodec() Codec { return defaultCodec }

// Row is one exported chat message with its embedding. It is the unit of the
// open format: one JSONL line or one Parquet row.
//
// Field tags are the column names seen by external engines — treat them as a
// public contract and only add fields, never rename or repurpose them. The json
// and parquet names are deliberately identical, so a query written against the
// JSONL dataset keeps working verbatim after a migration to Parquet.
//
// The parquet options are chosen per column: dictionary encoding for the
// low-cardinality identifiers, delta encoding for the monotonic integers, the
// LIST logical type for slices (the 3-level representation DuckDB / Spark /
// pyarrow expect, rather than a legacy repeated field), and BYTE_STREAM_SPLIT
// for the embedding floats, which is the standard encoding for float arrays.
type Row struct {
	ConvID     string    `json:"conv_id" parquet:"conv_id,dict"`
	TenantIDs  []string  `json:"tenant_ids" parquet:"tenant_ids,list" parquet-element:",dict"`
	TenantTags []string  `json:"tenant_tags" parquet:"tenant_tags,list" parquet-element:",dict"`
	Seq        int       `json:"seq" parquet:"seq,delta"`
	Side       string    `json:"side" parquet:"side,dict"`
	Content    string    `json:"content" parquet:"content"`
	Thinking   string    `json:"thinking,omitempty" parquet:"thinking"`
	Embedding  []float32 `json:"embedding" parquet:"embedding,list" parquet-element:",split"`
	CreatedAt  int64     `json:"created_at" parquet:"created_at,delta"`
}

// Day returns the partition date of the row in UTC.
func (r Row) Day() string { return time.Unix(r.CreatedAt, 0).UTC().Format("2006-01-02") }

// layout turns logical coordinates into object keys. Keeping every key in one
// place is what allows the format to be swapped without touching callers.
type layout struct{ prefix string }

func newLayout(prefix string) layout {
	if prefix == "" {
		prefix = DefaultPrefix
	}
	return layout{prefix: strings.TrimSuffix(prefix, "/")}
}

func (l layout) root() string      { return l.prefix + "/" }
func (l layout) stateKey() string  { return l.prefix + "/_manifest/state.json" }
func (l layout) schemaKey() string { return l.prefix + "/_schema.json" }
func (l layout) convPrefix(conv string) string {
	return l.prefix + "/conv=" + sanitize(conv) + "/"
}
func (l layout) dayPrefix(conv, day string) string {
	return l.convPrefix(conv) + "dt=" + day + "/"
}

// partKey is deterministic in (conv, day, lo, hi): re-exporting the same range
// overwrites the same object instead of creating a duplicate.
func (l layout) partKey(conv, day string, lo, hi int, ext string) string {
	return fmt.Sprintf("%spart-%08d-%08d.%s", l.dayPrefix(conv, day), lo, hi, ext)
}

// partRef is a parsed part object key.
type partRef struct {
	Key    string
	Conv   string
	Day    string
	Lo, Hi int
	Ext    string
	Size   int64
}

// parsePartKey decodes "…/conv=X/dt=YYYY-MM-DD/part-<lo>-<hi>.<ext>".
// Anything that does not match (manifests, schema, foreign objects) is skipped
// by returning ok=false, so a shared bucket is safe.
func (l layout) parsePartKey(key string) (partRef, bool) {
	rest, ok := strings.CutPrefix(key, l.root())
	if !ok {
		return partRef{}, false
	}
	parts := strings.Split(rest, "/")
	if len(parts) != 3 {
		return partRef{}, false
	}
	conv, ok := strings.CutPrefix(parts[0], "conv=")
	if !ok || conv == "" {
		return partRef{}, false
	}
	day, ok := strings.CutPrefix(parts[1], "dt=")
	if !ok {
		return partRef{}, false
	}
	if _, err := time.Parse("2006-01-02", day); err != nil {
		return partRef{}, false
	}
	name, ext, ok := strings.Cut(parts[2], ".")
	if !ok {
		return partRef{}, false
	}
	body, ok := strings.CutPrefix(name, "part-")
	if !ok {
		return partRef{}, false
	}
	loStr, hiStr, ok := strings.Cut(body, "-")
	if !ok {
		return partRef{}, false
	}
	lo, err1 := strconv.Atoi(loStr)
	hi, err2 := strconv.Atoi(hiStr)
	if err1 != nil || err2 != nil {
		return partRef{}, false
	}
	return partRef{Key: key, Conv: conv, Day: day, Lo: lo, Hi: hi, Ext: ext}, true
}

// sanitize keeps conversation ids safe to embed in an object key. Ids are hex
// today; this is defence in depth against a future id scheme.
func sanitize(s string) string {
	return strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
			return r
		case r == '-', r == '_', r == '.':
			return r
		default:
			return '_'
		}
	}, s)
}

// schemaDoc is written next to the data so a bare bucket is self-describing.
//
// Formats/ReadHints exist because the dataset may legitimately span formats:
// changing COLD_EXPORT_FORMAT never rewrites old parts. A consumer that reads
// only the current format would silently miss every row written before the
// switch, so the manifest names all of them.
type schemaDoc struct {
	Version   int               `json:"version"`
	Codec     string            `json:"codec"` // format new parts are written in
	Formats   []string          `json:"formats"`
	Partition []string          `json:"partition_keys"`
	Columns   []schemaCol       `json:"columns"`
	Hint      string            `json:"read_hint"`
	ReadHints map[string]string `json:"read_hints"`
	Warning   string            `json:"mixed_format_warning,omitempty"`
	UpdatedAt int64             `json:"updated_at"`
}

type schemaCol struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Doc  string `json:"doc"`
}

// readHint renders the expression an engine uses to read all parts of one
// format, falling back to a bare glob for codecs that do not implement
// ReadHinter.
func readHint(prefix string, codec Codec) string {
	glob := fmt.Sprintf("s3://<bucket>/%s/conv=*/dt=*/*.%s", prefix, codec.Ext())
	if h, ok := codec.(ReadHinter); ok {
		return h.ReadHint(glob)
	}
	return glob
}

// buildSchemaDoc describes the dataset. formats lists every extension known to
// be present (including the current codec's).
func buildSchemaDoc(prefix string, codec Codec, formats []string) schemaDoc {
	hints := map[string]string{}
	names := make([]string, 0, len(formats))
	for _, ext := range formats {
		c, ok := CodecByExt(ext)
		if !ok {
			// A part written by a format this build cannot resolve: still name
			// it, so the reader knows rows live there.
			hints[ext] = fmt.Sprintf("s3://<bucket>/%s/conv=*/dt=*/*.%s", prefix, ext)
			names = append(names, ext)
			continue
		}
		hints[c.Name()] = readHint(prefix, c)
		names = append(names, c.Name())
	}
	sort.Strings(names)

	var warning string
	if len(names) > 1 {
		warning = fmt.Sprintf(
			"该数据集含多种格式的 part（%s）。只读其中一种会漏掉切换格式之前写入的行，"+
				"请对 read_hints 中的每一项做 UNION ALL。",
			strings.Join(names, " + "))
	}

	return schemaDoc{
		Version:   SchemaVersion,
		Codec:     codec.Name(),
		Formats:   names,
		ReadHints: hints,
		Warning:   warning,
		Partition: []string{"conv", "dt"},
		Columns: []schemaCol{
			{"conv_id", "string", "会话 ID，同时是一级分区键"},
			{"tenant_ids", "list<string>", "该会话的租户（甲/乙）用户 ID"},
			{"tenant_tags", "list<string>", "租户标签"},
			{"seq", "int64", "会话内单调递增序号，(conv_id, seq) 唯一"},
			{"side", "string", "A=甲 / B=乙"},
			{"content", "string", "消息正文"},
			{"thinking", "string", "模型思考过程，可为空"},
			{"embedding", "list<float>", "正文向量，可为空（模型不可用时）"},
			{"created_at", "int64", "Unix 秒"},
		},
		Hint:      readHint(prefix, codec),
		UpdatedAt: time.Now().Unix(),
	}
}
