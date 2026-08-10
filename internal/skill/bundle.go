// Package skill parses, validates and normalizes Agent Skill bundles.
//
// A skill bundle is a zip archive whose root contains a SKILL.md file with
// YAML frontmatter plus any number of supporting resources (scripts,
// references, templates). Bundles are the unit of submission, review,
// distribution and versioning in the skill marketplace.
package skill

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"path"
	"regexp"
	"strings"
)

// Limits applied to every uploaded / imported bundle.
const (
	MaxBundleSize   = 5 << 20  // 5 MiB compressed
	MaxUncompressed = 20 << 20 // 20 MiB total after decompression
	MaxFiles        = 200
	MaxReadmeChars  = 20000 // stored preview of SKILL.md
	EntryFile       = "SKILL.md"
)

// File is one entry of a normalized bundle.
type File struct {
	Path string `json:"path"`
	Size int64  `json:"size"`
}

// Meta is the parsed SKILL.md frontmatter.
type Meta struct {
	Name         string   `json:"name,omitempty"`
	Description  string   `json:"description,omitempty"`
	Version      string   `json:"version,omitempty"`
	License      string   `json:"license,omitempty"`
	Homepage     string   `json:"homepage,omitempty"`
	Author       string   `json:"author,omitempty"`
	Icon         string   `json:"icon,omitempty"`
	Category     string   `json:"category,omitempty"`
	Tags         []string `json:"tags,omitempty"`
	AllowedTools []string `json:"allowed_tools,omitempty"`

	// Raw holds every frontmatter key verbatim so reviewers can inspect
	// fields this struct does not model.
	Raw map[string]any `json:"raw,omitempty"`
}

// Bundle is a validated, normalized skill package.
type Bundle struct {
	Meta   Meta
	Body   string // SKILL.md content below the frontmatter
	Files  []File
	Data   []byte // normalized zip bytes (top-level directory stripped)
	Size   int64
	SHA256 string
}

// ManifestJSON renders the parsed frontmatter for storage.
func (b *Bundle) ManifestJSON() json.RawMessage {
	data, err := json.Marshal(b.Meta)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return data
}

// FilesJSON renders the file listing for storage.
func (b *Bundle) FilesJSON() json.RawMessage {
	data, err := json.Marshal(b.Files)
	if err != nil {
		return json.RawMessage(`[]`)
	}
	return data
}

// Readme returns a length-capped preview of SKILL.md for the review UI.
func (b *Bundle) Readme() string {
	if len(b.Body) <= MaxReadmeChars {
		return b.Body
	}
	return b.Body[:MaxReadmeChars] + "\n…(truncated)"
}

// Parse validates a zip archive and returns a normalized bundle.
//
// Normalization: a single wrapping top-level directory (the usual result of
// `zip -r skill.zip my-skill/` or a GitHub archive) is stripped, junk entries
// (__MACOSX, .DS_Store, .git) are dropped, and the archive is repacked so
// every consumer sees SKILL.md at the root.
func Parse(data []byte) (*Bundle, error) {
	if len(data) == 0 {
		return nil, fmt.Errorf("空的技能包")
	}
	if len(data) > MaxBundleSize {
		return nil, fmt.Errorf("技能包过大：%d 字节，上限 %d 字节", len(data), MaxBundleSize)
	}

	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, fmt.Errorf("无法解析 zip：%w", err)
	}

	type entry struct {
		name string
		body []byte
	}
	var entries []entry
	var total int64

	for _, f := range zr.File {
		name := normalizeName(f.Name)
		if name == "" || strings.HasSuffix(f.Name, "/") {
			continue // directory or junk
		}
		if err := checkPath(name); err != nil {
			return nil, err
		}
		if f.Mode()&fs.ModeSymlink != 0 {
			return nil, fmt.Errorf("技能包禁止包含符号链接：%s", name)
		}
		if len(entries) >= MaxFiles {
			return nil, fmt.Errorf("技能包文件过多，上限 %d 个", MaxFiles)
		}

		rc, err := f.Open()
		if err != nil {
			return nil, fmt.Errorf("读取 %s 失败：%w", name, err)
		}
		body, err := io.ReadAll(io.LimitReader(rc, MaxUncompressed+1))
		rc.Close()
		if err != nil {
			return nil, fmt.Errorf("读取 %s 失败：%w", name, err)
		}
		total += int64(len(body))
		if total > MaxUncompressed {
			return nil, fmt.Errorf("技能包解压后过大，上限 %d 字节", MaxUncompressed)
		}
		entries = append(entries, entry{name: name, body: body})
	}

	if len(entries) == 0 {
		return nil, fmt.Errorf("技能包为空")
	}

	// Strip a single common top-level directory if present.
	names := make([]string, len(entries))
	for i, e := range entries {
		names[i] = e.name
	}
	if prefix := commonTopDir(names); prefix != "" {
		for i := range entries {
			entries[i].name = strings.TrimPrefix(entries[i].name, prefix+"/")
		}
	}

	// Locate SKILL.md at the root (case-insensitive).
	var skillMD []byte
	found := false
	for _, e := range entries {
		if !strings.Contains(e.name, "/") && strings.EqualFold(e.name, EntryFile) {
			skillMD = e.body
			found = true
			break
		}
	}
	if !found {
		return nil, fmt.Errorf("技能包根目录缺少 %s", EntryFile)
	}

	meta, body := ParseFrontmatter(string(skillMD))
	if strings.TrimSpace(meta.Name) == "" {
		return nil, fmt.Errorf("%s 的 frontmatter 缺少 name 字段", EntryFile)
	}
	if strings.TrimSpace(meta.Description) == "" {
		return nil, fmt.Errorf("%s 的 frontmatter 缺少 description 字段", EntryFile)
	}

	// Repack normalized.
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	files := make([]File, 0, len(entries))
	for _, e := range entries {
		name := e.name
		if strings.EqualFold(name, EntryFile) {
			name = EntryFile // canonical casing
		}
		w, err := zw.Create(name)
		if err != nil {
			return nil, fmt.Errorf("打包 %s 失败：%w", name, err)
		}
		if _, err := w.Write(e.body); err != nil {
			return nil, fmt.Errorf("打包 %s 失败：%w", name, err)
		}
		files = append(files, File{Path: name, Size: int64(len(e.body))})
	}
	if err := zw.Close(); err != nil {
		return nil, fmt.Errorf("打包失败：%w", err)
	}

	out := buf.Bytes()
	sum := sha256.Sum256(out)
	return &Bundle{
		Meta:   meta,
		Body:   body,
		Files:  files,
		Data:   out,
		Size:   int64(len(out)),
		SHA256: hex.EncodeToString(sum[:]),
	}, nil
}

// BuildFromMarkdown wraps a bare SKILL.md into a single-file bundle so that
// single-file skills imported from a URL share the same storage format.
func BuildFromMarkdown(markdown []byte) ([]byte, error) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	w, err := zw.Create(EntryFile)
	if err != nil {
		return nil, err
	}
	if _, err := w.Write(markdown); err != nil {
		return nil, err
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// --- path helpers ---

var junkPrefixes = []string{"__MACOSX/", ".git/", ".github/"}

func normalizeName(name string) string {
	n := strings.ReplaceAll(name, "\\", "/")
	n = path.Clean(n)
	if n == "." || n == "/" {
		return ""
	}
	n = strings.TrimPrefix(n, "./")
	base := path.Base(n)
	if base == ".DS_Store" || base == "Thumbs.db" {
		return ""
	}
	for _, p := range junkPrefixes {
		if strings.HasPrefix(n, p) || strings.Contains(n, "/"+p) {
			return ""
		}
	}
	return n
}

func checkPath(name string) error {
	if path.IsAbs(name) || strings.HasPrefix(name, "/") {
		return fmt.Errorf("技能包包含绝对路径：%s", name)
	}
	for _, seg := range strings.Split(name, "/") {
		if seg == ".." {
			return fmt.Errorf("技能包包含非法路径：%s", name)
		}
	}
	if len(name) > 255 {
		return fmt.Errorf("技能包文件名过长：%s", name)
	}
	return nil
}

// commonTopDir returns the single directory every entry lives under, or "".
func commonTopDir(names []string) string {
	var prefix string
	for _, n := range names {
		i := strings.Index(n, "/")
		if i <= 0 {
			return "" // a root-level file exists → nothing to strip
		}
		top := n[:i]
		if prefix == "" {
			prefix = top
		} else if prefix != top {
			return ""
		}
	}
	return prefix
}

// --- slug ---

var slugInvalid = regexp.MustCompile(`[^a-z0-9-]+`)
var slugDashes = regexp.MustCompile(`-{2,}`)

// Slugify converts a skill name into a marketplace slug.
func Slugify(s string) string {
	out := strings.ToLower(strings.TrimSpace(s))
	out = strings.ReplaceAll(out, "_", "-")
	out = strings.ReplaceAll(out, " ", "-")
	out = slugInvalid.ReplaceAllString(out, "")
	out = slugDashes.ReplaceAllString(out, "-")
	out = strings.Trim(out, "-")
	if len(out) > 64 {
		out = strings.Trim(out[:64], "-")
	}
	return out
}

var validSlug = regexp.MustCompile(`^[a-z0-9][a-z0-9-]{1,63}$`)

// ValidSlug reports whether a slug is acceptable as a marketplace identifier.
func ValidSlug(s string) bool { return validSlug.MatchString(s) }
