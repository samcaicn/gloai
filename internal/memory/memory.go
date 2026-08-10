// Package memory implements per-tenant personalized memory for the CEOadmin Hub.
//
// Each tenant (a real iLink user) owns an isolated memory space: a profile with
// free-form preferences plus a list of memories (facts / preferences / episodes).
// Memories can be retrieved by semantic similarity using the platform's system
// embedding interface (internal/ai.Embed), so a tenant's history is injected
// into the LLM prompt as personalized, relevant context.
//
// The on-disk layout is compatible with the edict / tenant-memory (tms) memory
// model — each tenant is a directory of profile.json + memories.json — so the
// Hub, edict and the tms sidecar CAN share one set of files by pointing their
// data directories at the same path (MEMORY_DIR here, DATA_DIR for tms). The
// defaults are intentionally separate, so without that configuration the stores
// operate independently of each other.
//
//	data/tenants/<tenant_id>/
//	  profile.json    # name + preferences
//	  memories.json   # memory list
package memory

import (
	"context"
	"encoding/json"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/ai"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// Memory is a single stored fact / preference / episode.
type Memory struct {
	ID        string    `json:"id"`
	Type      string    `json:"type"` // fact | preference | episode
	Content   string    `json:"content"`
	CreatedAt int64     `json:"created_at"`
	UpdatedAt int64     `json:"updated_at"`
	Embedding []float32 `json:"embedding,omitempty"`
}

// Profile is a tenant's persona / preference bag.
type Profile struct {
	TenantID    string            `json:"tenant_id"`
	Name        string            `json:"name"`
	Preferences map[string]string `json:"preferences"`
	CreatedAt   int64             `json:"created_at"`
	UpdatedAt   int64             `json:"updated_at"`
}

// Store is the per-tenant memory backend.
type Store interface {
	EnsureTenant(id string) error
	GetProfile(id string) (*Profile, error)
	SetProfile(id, name string, prefs map[string]string) error
	ListMemories(tenant, typ string) ([]Memory, error)
	AddMemory(tenant, typ, content string) (*Memory, error)
	GetMemory(tenant, mid string) (*Memory, error)
	DeleteMemory(tenant, mid string) error
	// Retrieve returns the k memories most relevant to query, using embeddings.
	Retrieve(ctx context.Context, cfg store.AIConfig, tenant, query string, k int) ([]Memory, error)
	// RenderContext dumps the full profile + memories as a prompt-ready block.
	RenderContext(tenant string) (string, error)
}

// DefaultDir returns the on-disk base directory for memory files. It defaults
// to "data/tenants" (relative to the Hub's working directory). The layout
// (<dir>/<tenant_id>/{profile,memories}.json) is compatible with edict and the
// tenant-memory (tms) service, so to share the same tenant files the Hub's
// MEMORY_DIR must be pointed at the directory tms uses for DATA_DIR. On its own
// this default does not share data with a separately-configured tms instance.
func DefaultDir() string {
	if d := os.Getenv("MEMORY_DIR"); d != "" {
		return d
	}
	return "data/tenants"
}

// NewFileStore creates a file-backed store rooted at dir.
func NewFileStore(dir string) Store {
	_ = os.MkdirAll(dir, 0o755)
	return &fileStore{dir: dir}
}

type fileStore struct {
	dir string
	mu  sync.Mutex
}

func (f *fileStore) tenantDir(t string) string  { return filepath.Join(f.dir, t) }
func (f *fileStore) profilePath(t string) string { return filepath.Join(f.tenantDir(t), "profile.json") }
func (f *fileStore) memoriesPath(t string) string {
	return filepath.Join(f.tenantDir(t), "memories.json")
}

// withLock serializes a read-modify-write critical section both within the
// process (f.mu) and across processes (an advisory file lock on a per-tenant
// lock file). When the Hub (oih), edict and the tms service are configured to
// use the same data directory they may all write the same tenant JSON files, so
// this prevents concurrent writers from clobbering each other's changes. The
// lock is released automatically when the process exits.
func (f *fileStore) withLock(tenant string, fn func() error) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	dir := f.tenantDir(tenant)
	// Ensure the tenant directory exists before opening the lock file, so the
	// very first write (e.g. EnsureTenant) doesn't fail on a missing parent dir.
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	lk, err := os.OpenFile(filepath.Join(dir, ".lock"), os.O_CREATE|os.O_RDWR, 0o644)
	if err != nil {
		return err
	}
	defer lk.Close()
	if err := syscall.Flock(int(lk.Fd()), syscall.LOCK_EX); err != nil {
		return err
	}
	defer syscall.Flock(int(lk.Fd()), syscall.LOCK_UN)
	return fn()
}

func (f *fileStore) readJSON(path string, v any) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}

func (f *fileStore) writeJSON(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func (f *fileStore) EnsureTenant(id string) error {
	return f.withLock(id, func() error {
		_ = os.MkdirAll(f.tenantDir(id), 0o755)
		if _, err := os.Stat(f.profilePath(id)); os.IsNotExist(err) {
			now := time.Now().Unix()
			p := &Profile{TenantID: id, Preferences: map[string]string{}, CreatedAt: now, UpdatedAt: now}
			if err := f.writeJSON(f.profilePath(id), p); err != nil {
				return err
			}
		}
		if _, err := os.Stat(f.memoriesPath(id)); os.IsNotExist(err) {
			if err := f.writeJSON(f.memoriesPath(id), []Memory{}); err != nil {
				return err
			}
		}
		return nil
	})
}

func (f *fileStore) GetProfile(id string) (*Profile, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.readProfile(id)
}

func (f *fileStore) readProfile(id string) (*Profile, error) {
	p := &Profile{TenantID: id, Preferences: map[string]string{}}
	if err := f.readJSON(f.profilePath(id), p); err != nil {
		return nil, err
	}
	if p.Preferences == nil {
		p.Preferences = map[string]string{}
	}
	return p, nil
}

func (f *fileStore) SetProfile(id, name string, prefs map[string]string) error {
	return f.withLock(id, func() error {
		p, err := f.readProfile(id)
		if err != nil {
			return err
		}
		if name != "" {
			p.Name = name
		}
		if p.Preferences == nil {
			p.Preferences = map[string]string{}
		}
		for k, v := range prefs {
			p.Preferences[k] = v
		}
		p.UpdatedAt = time.Now().Unix()
		return f.writeJSON(f.profilePath(id), p)
	})
}

func (f *fileStore) ListMemories(tenant, typ string) ([]Memory, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var mems []Memory
	if err := f.readJSON(f.memoriesPath(tenant), &mems); err != nil {
		return nil, err
	}
	if typ == "" {
		return mems, nil
	}
	out := mems[:0]
	for _, m := range mems {
		if m.Type == typ {
			out = append(out, m)
		}
	}
	return out, nil
}

func (f *fileStore) AddMemory(tenant, typ, content string) (*Memory, error) {
	norm := strings.ToLower(strings.TrimSpace(content))
	var res *Memory
	err := f.withLock(tenant, func() error {
		var mems []Memory
		if err := f.readJSON(f.memoriesPath(tenant), &mems); err != nil {
			return err
		}
		// 去重：相同归一化内容（忽略大小写/首尾空白）已存在则直接返回已有记忆，避免重复写入。
		for i := range mems {
			if strings.ToLower(strings.TrimSpace(mems[i].Content)) == norm {
				res = &mems[i]
				return nil
			}
		}
		if typ == "" {
			typ = "episode"
		}
		now := time.Now().Unix()
		m := Memory{
			ID:        tenant + "-" + time.Now().Format("20060102") + "-" + itoa(len(mems)),
			Type:      typ,
			Content:   content,
			CreatedAt: now,
			UpdatedAt: now,
		}
		mems = append(mems, m)
		if err := f.writeJSON(f.memoriesPath(tenant), mems); err != nil {
			return err
		}
		res = &m
		return nil
	})
	return res, err
}

func (f *fileStore) GetMemory(tenant, mid string) (*Memory, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var mems []Memory
	if err := f.readJSON(f.memoriesPath(tenant), &mems); err != nil {
		return nil, err
	}
	for _, m := range mems {
		if m.ID == mid {
			return &m, nil
		}
	}
	return nil, os.ErrNotExist
}

func (f *fileStore) DeleteMemory(tenant, mid string) error {
	return f.withLock(tenant, func() error {
		var mems []Memory
		if err := f.readJSON(f.memoriesPath(tenant), &mems); err != nil {
			return err
		}
		out := mems[:0]
		for _, m := range mems {
			if m.ID != mid {
				out = append(out, m)
			}
		}
		return f.writeJSON(f.memoriesPath(tenant), out)
	})
}

// Retrieve returns the k memories most similar to query, using the platform
// embedding interface. When embeddings are unavailable, or nothing is even
// weakly relevant to the query, it falls back to the most recent k memories so
// the caller always gets personalized context instead of an empty result.
func (f *fileStore) Retrieve(ctx context.Context, cfg store.AIConfig, tenant, query string, k int) ([]Memory, error) {
	f.mu.Lock()
	var mems []Memory
	_ = f.readJSON(f.memoriesPath(tenant), &mems)
	f.mu.Unlock()
	if len(mems) == 0 {
		return nil, nil
	}
	if k <= 0 {
		k = 5
	}
	// No query or no embedding API key: nothing to rank by, return recent k.
	if query == "" || cfg.APIKey == "" {
		return recentMemories(mems, k), nil
	}
	texts := append([]string{query}, collect(mems)...)
	vecs, err := ai.Embed(ctx, cfg, texts)
	if err != nil || len(vecs) == 0 {
		return recentMemories(mems, k), nil
	}
	qv := vecs[0]
	type scored struct {
		m     Memory
		score float32
	}
	var ranked []scored
	for i, m := range mems {
		if i+1 >= len(vecs) {
			break
		}
		if s, ok := cosine32(qv, vecs[i+1]); ok {
			ranked = append(ranked, scored{m, s})
		}
	}
	sort.Slice(ranked, func(i, j int) bool { return ranked[i].score > ranked[j].score })
	// Nothing relevant (best similarity <= 0 — e.g. an unrelated query, or all
	// memories with zero/negative vectors): fall back to the most recent k.
	if len(ranked) == 0 || ranked[0].score <= 0 {
		return recentMemories(mems, k), nil
	}
	if len(ranked) > k {
		ranked = ranked[:k]
	}
	out := make([]Memory, len(ranked))
	for i, r := range ranked {
		out[i] = r.m
	}
	return out, nil
}

// recentMemories returns up to k of the most recently created memories
// (newest first), independent of the underlying slice order.
func recentMemories(mems []Memory, k int) []Memory {
	if k <= 0 {
		k = 5
	}
	out := make([]Memory, len(mems))
	copy(out, mems)
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt > out[j].CreatedAt })
	if len(out) > k {
		out = out[:k]
	}
	return out
}

// RenderContext renders the full profile + memories as a prompt-ready block.
func (f *fileStore) RenderContext(tenant string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	p, err := f.readProfile(tenant)
	if err != nil {
		return "", err
	}
	var mems []Memory
	_ = f.readJSON(f.memoriesPath(tenant), &mems)
	return renderText(p, mems), nil
}

// RenderText builds the prompt-ready memory block from a profile + memories.
func RenderText(p *Profile, mems []Memory) string {
	return renderText(p, mems)
}

func renderText(p *Profile, mems []Memory) string {
	if p == nil {
		p = &Profile{TenantID: "", Preferences: map[string]string{}}
	}
	var b strings.Builder
	b.WriteString("# 租户个性化记忆\n")
	b.WriteString("租户: " + p.TenantID)
	if p.Name != "" {
		b.WriteString(" (" + p.Name + ")")
	}
	b.WriteString("\n")
	if len(p.Preferences) > 0 {
		b.WriteString("偏好:\n")
		for k, v := range p.Preferences {
			b.WriteString("  - " + k + ": " + v + "\n")
		}
	}
	if len(mems) > 0 {
		b.WriteString("历史记忆:\n")
		for _, m := range mems {
			b.WriteString("  [" + m.Type + "] " + m.Content + "\n")
		}
	}
	if len(p.Preferences) == 0 && len(mems) == 0 {
		b.WriteString("（暂无记忆）\n")
	}
	return b.String()
}

func collect(mems []Memory) []string {
	out := make([]string, len(mems))
	for i, m := range mems {
		out[i] = m.Content
	}
	return out
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

// cosine32 returns the cosine similarity of two equal-length float32 vectors.
func cosine32(a, b []float32) (float32, bool) {
	if len(a) == 0 || len(b) == 0 || len(a) != len(b) {
		return 0, false
	}
	var dot, na, nb float64
	for i := range a {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	if na == 0 || nb == 0 {
		return 0, false
	}
	return float32(dot / (sqrt(na) * sqrt(nb))), true
}

func sqrt(x float64) float64 {
	return math.Sqrt(x)
}
