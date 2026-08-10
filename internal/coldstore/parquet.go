//go:build !noparquet

package coldstore

import (
	"bytes"
	"fmt"

	"github.com/parquet-go/parquet-go"
	"github.com/parquet-go/parquet-go/compress/zstd"
)

// Parquet encodes a part as an Apache Parquet file: columnar, compressed, with
// per-column statistics that let engines skip row groups.
//
// Compared with the JSONL default it trades human-readability and append-ability
// for size and scan speed — the win is largest exactly where this dataset hurts,
// namely the embedding column, where BYTE_STREAM_SPLIT plus zstd replaces the
// ~14 decimal characters JSON spends per float.
//
// The column names are identical to the JSONL ones (see Row), so switching
// formats does not invalidate queries written against the old parts, and Reader
// keeps reading both.
type Parquet struct{}

func (Parquet) Name() string        { return "parquet" }
func (Parquet) Ext() string         { return "parquet" }
func (Parquet) ContentType() string { return "application/vnd.apache.parquet" }

// ReadHint implements ReadHinter.
func (Parquet) ReadHint(glob string) string {
	return fmt.Sprintf("read_parquet('%s')", glob)
}

func (Parquet) Encode(rows []Row) ([]byte, error) {
	var buf bytes.Buffer
	// zstd is applied writer-wide; the per-column encodings live on Row's tags.
	if err := parquet.Write(&buf, rows, parquet.Compression(&zstd.Codec{})); err != nil {
		return nil, fmt.Errorf("coldstore: parquet encode (%d rows): %w", len(rows), err)
	}
	return buf.Bytes(), nil
}

func (Parquet) Decode(data []byte) ([]Row, error) {
	rows, err := parquet.Read[Row](bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("coldstore: parquet decode (%d bytes): %w", len(data), err)
	}
	// Parquet has no concept of a nil list, so an absent embedding comes back as
	// an empty non-nil slice. Normalise it so the two codecs are observationally
	// identical and a Row survives a JSONL -> Parquet -> JSONL trip unchanged.
	for i := range rows {
		if len(rows[i].TenantIDs) == 0 {
			rows[i].TenantIDs = nil
		}
		if len(rows[i].TenantTags) == 0 {
			rows[i].TenantTags = nil
		}
		if len(rows[i].Embedding) == 0 {
			rows[i].Embedding = nil
		}
	}
	return rows, nil
}

// Being linked in is what makes parquet the default format; a `-tags noparquet`
// build leaves defaultCodec at JSONL.
func init() {
	RegisterCodec(Parquet{})
	defaultCodec = Parquet{}
}
