package store

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"syscall"
	"time"

	_ "modernc.org/sqlite"
)

// Memory 单条记忆。
type Memory struct {
	ID        string `json:"id"`
	Type      string `json:"type"` // fact | preference | episode
	Content   string `json:"content"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
}

// Profile 租户画像。
type Profile struct {
	TenantID    string            `json:"tenant_id"`
	Name        string            `json:"name"`
	Preferences map[string]string `json:"preferences"`
	CreatedAt   int64             `json:"created_at"`
	UpdatedAt   int64             `json:"updated_at"`
}

// Store 多租户记忆存储接口（sqlite 与 file 两种实现）。
type Store interface {
	EnsureTenant(id string) error
	GetProfile(id string) (*Profile, error)
	SetProfile(id, name string, prefs map[string]string) error
	ListMemories(tenant, typ string) ([]Memory, error)
	AddMemory(tenant, typ, content string) (*Memory, error)
	GetMemory(tenant, mid string) (*Memory, error)
	DeleteMemory(tenant, mid string) error
	RenderContext(tenant string) (string, error)
	// 系统 LLM 调用的 token 用量记录（与 Hub 的 llm_usage 口径一致）。
	RecordLLMUsage(r *LLMUsageRecord) error
	ListLLMUsageAgg(f UsageFilter) ([]LLMUsageAgg, error)
	Close() error
}

// LLMUsageRecord 一条系统 LLM 调用的 token 用量记录。
type LLMUsageRecord struct {
	TenantID         string
	ChannelID        string
	Model            string
	ModelType        string // "chat" | "embedding"
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
	CachedTokens     int
	ReasoningTokens  int
	DurationMS       int64 // 调用耗时（毫秒）
	CreatedAt        int64
}

// UsageFilter 用量聚合查询过滤条件。
type UsageFilter struct {
	TenantID string
	Model    string
	Limit    int
}

// LLMUsageAgg 按 (租户, 渠道, 模型, 类型) 聚合后的用量行。
type LLMUsageAgg struct {
	TenantID         string
	ChannelID        string
	Model            string
	ModelType        string
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
	CachedTokens     int
	ReasoningTokens  int
	DurationMS       int64 // 累计调用耗时（毫秒）
	CallCount        int
	LastAt           int64
}

// Open 按模式打开存储。file 模式采用与 edict、Hub 内存（memory 包）兼容的
// <dataDir>/tenants/<id>/ 布局；要真正共享同一份租户记忆文件，需把本服务的
// DATA_DIR 与 Hub 的 MEMORY_DIR 指向同一目录。默认两者不同，互不共享。
func Open(mode, dbPath, dataDir string) (Store, error) {
	if mode == "file" {
		return openFile(dataDir), nil
	}
	return openSQLite(dbPath)
}

// ---- 共享渲染：把画像 + 记忆拼成一个可注入 prompt 的文本块 ----

// RenderText 导出渲染函数，供上层（server）在「仅检索到的子集」上渲染上下文。
func RenderText(p *Profile, mems []Memory) string {
	return renderContext(p, mems)
}

func renderContext(p *Profile, mems []Memory) string {
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
			b.WriteString(fmt.Sprintf("  - %s: %s\n", k, v))
		}
	}
	if len(mems) > 0 {
		b.WriteString("历史记忆:\n")
		for _, m := range mems {
			b.WriteString(fmt.Sprintf("  [%s] %s\n", m.Type, m.Content))
		}
	}
	if len(p.Preferences) == 0 && len(mems) == 0 {
		b.WriteString("（暂无记忆）\n")
	}
	return b.String()
}

// newID 生成一个简单的唯一 ID（租户前缀 + 纳秒时间戳）。
func newID(prefix string) string {
	return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
}

// ---- SQLite 实现 ----

type sqliteStore struct {
	db *sql.DB
}

func openSQLite(path string) (*sqliteStore, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec("PRAGMA journal_mode=WAL;"); err != nil {
		return nil, err
	}
	ddl := `
	CREATE TABLE IF NOT EXISTS tenants(
		id TEXT PRIMARY KEY, name TEXT, preferences TEXT, created_at INTEGER, updated_at INTEGER);
	CREATE TABLE IF NOT EXISTS memories(
		id TEXT PRIMARY KEY, tenant_id TEXT, type TEXT, content TEXT, created_at INTEGER, updated_at INTEGER);
	CREATE INDEX IF NOT EXISTS idx_mem_tenant ON memories(tenant_id);
	CREATE TABLE IF NOT EXISTS llm_usage(
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		tenant_id TEXT, channel_id TEXT, model TEXT, model_type TEXT,
		prompt_tokens INTEGER, completion_tokens INTEGER, total_tokens INTEGER,
		cached_tokens INTEGER, reasoning_tokens INTEGER, duration_ms INTEGER, created_at INTEGER);
	CREATE INDEX IF NOT EXISTS idx_usage_tenant ON llm_usage(tenant_id);`
	if _, err := db.Exec(ddl); err != nil {
		return nil, err
	}
	// 兼容已存在的库：补加 duration_ms 列（列已存在时忽略错误）。
	if _, err := db.Exec(`ALTER TABLE llm_usage ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0`); err != nil {
		// 列已存在或表不存在都会报错，旧库升级场景下忽略即可。
		_ = err
	}
	return &sqliteStore{db: db}, nil
}

func (s *sqliteStore) Close() error { return s.db.Close() }

func (s *sqliteStore) EnsureTenant(id string) error {
	now := time.Now().Unix()
	_, err := s.db.Exec(
		`INSERT OR IGNORE INTO tenants(id, name, preferences, created_at, updated_at)
		 VALUES(?, '', '{}', ?, ?)`, id, now, now)
	return err
}

func (s *sqliteStore) GetProfile(id string) (*Profile, error) {
	var name, prefsJSON string
	var created, updated int64
	err := s.db.QueryRow(
		`SELECT name, preferences, created_at, updated_at FROM tenants WHERE id=?`, id).
		Scan(&name, &prefsJSON, &created, &updated)
	if err == sql.ErrNoRows {
		return &Profile{TenantID: id, Preferences: map[string]string{}}, nil
	}
	if err != nil {
		return nil, err
	}
	p := &Profile{TenantID: id, Name: name, CreatedAt: created, UpdatedAt: updated, Preferences: map[string]string{}}
	_ = json.Unmarshal([]byte(prefsJSON), &p.Preferences)
	return p, nil
}

func (s *sqliteStore) SetProfile(id, name string, prefs map[string]string) error {
	now := time.Now().Unix()
	if name == "" {
		if p, err := s.GetProfile(id); err == nil && p.Name != "" {
			name = p.Name
		}
	}
	existing, _ := s.GetProfile(id)
	if existing.Preferences == nil {
		existing.Preferences = map[string]string{}
	}
	for k, v := range prefs {
		existing.Preferences[k] = v
	}
	b, _ := json.Marshal(existing.Preferences)
	_, err := s.db.Exec(
		`INSERT INTO tenants(id, name, preferences, created_at, updated_at)
		 VALUES(?, ?, ?, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET name=excluded.name, preferences=excluded.preferences, updated_at=excluded.updated_at`,
		id, name, string(b), now, now)
	return err
}

func (s *sqliteStore) ListMemories(tenant, typ string) ([]Memory, error) {
	query := `SELECT id, type, content, created_at, updated_at FROM memories WHERE tenant_id=?`
	args := []any{tenant}
	if typ != "" {
		query += " AND type=?"
		args = append(args, typ)
	}
	query += " ORDER BY created_at DESC"
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Memory
	for rows.Next() {
		var m Memory
		if err := rows.Scan(&m.ID, &m.Type, &m.Content, &m.CreatedAt, &m.UpdatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, nil
}

func (s *sqliteStore) AddMemory(tenant, typ, content string) (*Memory, error) {
	norm := strings.ToLower(strings.TrimSpace(content))
	// 去重：相同归一化内容（忽略大小写/首尾空白）已存在则直接返回已有记忆，避免重复写入。
	var existingID string
	_ = s.db.QueryRow(
		`SELECT id FROM memories WHERE tenant_id=? AND lower(trim(content))=? LIMIT 1`,
		tenant, norm).Scan(&existingID)
	if existingID != "" {
		return s.GetMemory(tenant, existingID)
	}
	if typ == "" {
		typ = "episode"
	}
	now := time.Now().Unix()
	m := &Memory{ID: newID(tenant), Type: typ, Content: content, CreatedAt: now, UpdatedAt: now}
	_, err := s.db.Exec(
		`INSERT INTO memories(id, tenant_id, type, content, created_at, updated_at)
		 VALUES(?, ?, ?, ?, ?, ?)`, m.ID, tenant, m.Type, m.Content, m.CreatedAt, m.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return m, nil
}

func (s *sqliteStore) GetMemory(tenant, mid string) (*Memory, error) {
	var m Memory
	err := s.db.QueryRow(
		`SELECT id, type, content, created_at, updated_at FROM memories WHERE tenant_id=? AND id=?`,
		tenant, mid).Scan(&m.ID, &m.Type, &m.Content, &m.CreatedAt, &m.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &m, nil
}

func (s *sqliteStore) DeleteMemory(tenant, mid string) error {
	_, err := s.db.Exec(`DELETE FROM memories WHERE tenant_id=? AND id=?`, tenant, mid)
	return err
}

func (s *sqliteStore) RenderContext(tenant string) (string, error) {
	p, err := s.GetProfile(tenant)
	if err != nil {
		return "", err
	}
	mems, err := s.ListMemories(tenant, "")
	if err != nil {
		return "", err
	}
	return renderContext(p, mems), nil
}

func (s *sqliteStore) RecordLLMUsage(r *LLMUsageRecord) error {
	if r.CreatedAt == 0 {
		r.CreatedAt = time.Now().Unix()
	}
	_, err := s.db.Exec(
		`INSERT INTO llm_usage(tenant_id, channel_id, model, model_type, prompt_tokens, completion_tokens, total_tokens, cached_tokens, reasoning_tokens, duration_ms, created_at)
		 VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		r.TenantID, r.ChannelID, r.Model, r.ModelType, r.PromptTokens, r.CompletionTokens, r.TotalTokens, r.CachedTokens, r.ReasoningTokens, r.DurationMS, r.CreatedAt)
	return err
}

func (s *sqliteStore) ListLLMUsageAgg(f UsageFilter) ([]LLMUsageAgg, error) {
	query := `SELECT tenant_id, channel_id, model, model_type,
		COALESCE(SUM(prompt_tokens),0), COALESCE(SUM(completion_tokens),0), COALESCE(SUM(total_tokens),0),
		COALESCE(SUM(cached_tokens),0), COALESCE(SUM(reasoning_tokens),0), COALESCE(SUM(duration_ms),0), COUNT(*), MAX(created_at)
		FROM llm_usage WHERE 1=1`
	args := []any{}
	if f.TenantID != "" {
		query += " AND tenant_id=?"
		args = append(args, f.TenantID)
	}
	if f.Model != "" {
		query += " AND model=?"
		args = append(args, f.Model)
	}
	query += " GROUP BY tenant_id, channel_id, model, model_type ORDER BY MAX(created_at) DESC"
	if f.Limit > 0 {
		query += " LIMIT ?"
		args = append(args, f.Limit)
	}
	rows, err := s.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []LLMUsageAgg
	for rows.Next() {
		var a LLMUsageAgg
		if err := rows.Scan(&a.TenantID, &a.ChannelID, &a.Model, &a.ModelType,
			&a.PromptTokens, &a.CompletionTokens, &a.TotalTokens, &a.CachedTokens, &a.ReasoningTokens, &a.DurationMS, &a.CallCount, &a.LastAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, nil
}

// ---- File 实现（布局与 edict / Hub memory 包兼容；共享同一份文件需与它们指向同一目录） ----

type fileStore struct {
	dir string
	mu  sync.Mutex
}

func openFile(dir string) *fileStore {
	_ = os.MkdirAll(dir, 0o755)
	return &fileStore{dir: dir}
}

func (f *fileStore) tenantDir(t string) string { return filepath.Join(f.dir, "tenants", t) }
func (f *fileStore) profilePath(t string) string {
	return filepath.Join(f.tenantDir(t), "profile.json")
}
func (f *fileStore) memoriesPath(t string) string {
	return filepath.Join(f.tenantDir(t), "memories.json")
}
func (f *fileStore) usagePath() string { return filepath.Join(f.dir, "llm_usage.json") }

// withLock serializes a read-modify-write critical section both within the
// process (f.mu) and across processes (an advisory file lock on a per-tenant
// lock file). 当 edict、tms 与本 Hub 被配置为使用同一数据目录时，它们可能
// 写入同一份租户 JSON 文件，因此这里防止并发写入互相覆盖。进程退出时锁自动释放。
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

func (f *fileStore) readProfile(t string) (*Profile, error) {
	p := &Profile{TenantID: t, Preferences: map[string]string{}}
	data, err := os.ReadFile(f.profilePath(t))
	if os.IsNotExist(err) {
		return p, nil
	}
	if err != nil {
		return nil, err
	}
	_ = json.Unmarshal(data, p)
	if p.Preferences == nil {
		p.Preferences = map[string]string{}
	}
	return p, nil
}

func (f *fileStore) readMemories(t string) ([]Memory, error) {
	var mems []Memory
	data, err := os.ReadFile(f.memoriesPath(t))
	if os.IsNotExist(err) {
		return mems, nil
	}
	if err != nil {
		return nil, err
	}
	_ = json.Unmarshal(data, &mems)
	return mems, nil
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

func (f *fileStore) Close() error { return nil }

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
	mems, err := f.readMemories(tenant)
	if err != nil {
		return nil, err
	}
	if typ == "" {
		return mems, nil
	}
	var out []Memory
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
		mems, err := f.readMemories(tenant)
		if err != nil {
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
		m := Memory{ID: newID(tenant), Type: typ, Content: content, CreatedAt: now, UpdatedAt: now}
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
	mems, err := f.readMemories(tenant)
	if err != nil {
		return nil, err
	}
	for _, m := range mems {
		if m.ID == mid {
			return &m, nil
		}
	}
	return nil, fmt.Errorf("memory %s not found", mid)
}

func (f *fileStore) DeleteMemory(tenant, mid string) error {
	return f.withLock(tenant, func() error {
		mems, err := f.readMemories(tenant)
		if err != nil {
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

func (f *fileStore) RenderContext(tenant string) (string, error) {
	p, err := f.readProfile(tenant)
	if err != nil {
		return "", err
	}
	mems, err := f.readMemories(tenant)
	if err != nil {
		return "", err
	}
	return renderContext(p, mems), nil
}

func (f *fileStore) readUsage() ([]LLMUsageRecord, error) {
	var out []LLMUsageRecord
	data, err := os.ReadFile(f.usagePath())
	if os.IsNotExist(err) {
		return out, nil
	}
	if err != nil {
		return nil, err
	}
	if err := json.Unmarshal(data, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (f *fileStore) RecordLLMUsage(r *LLMUsageRecord) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if r.CreatedAt == 0 {
		r.CreatedAt = time.Now().Unix()
	}
	out, err := f.readUsage()
	if err != nil {
		return err
	}
	out = append(out, *r)
	return f.writeJSON(f.usagePath(), out)
}

func (f *fileStore) ListLLMUsageAgg(filter UsageFilter) ([]LLMUsageAgg, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	recs, err := f.readUsage()
	if err != nil {
		return nil, err
	}
	type aggKey struct{ tenant, channel, model, modelType string }
	m := map[aggKey]*LLMUsageAgg{}
	for i := range recs {
		r := recs[i]
		if filter.TenantID != "" && r.TenantID != filter.TenantID {
			continue
		}
		if filter.Model != "" && r.Model != filter.Model {
			continue
		}
		k := aggKey{r.TenantID, r.ChannelID, r.Model, r.ModelType}
		a, ok := m[k]
		if !ok {
			a = &LLMUsageAgg{TenantID: r.TenantID, ChannelID: r.ChannelID, Model: r.Model, ModelType: r.ModelType}
			m[k] = a
		}
		a.PromptTokens += r.PromptTokens
		a.CompletionTokens += r.CompletionTokens
		a.TotalTokens += r.TotalTokens
		a.CachedTokens += r.CachedTokens
		a.ReasoningTokens += r.ReasoningTokens
		a.DurationMS += r.DurationMS
		a.CallCount++
		if r.CreatedAt > a.LastAt {
			a.LastAt = r.CreatedAt
		}
	}
	out := make([]LLMUsageAgg, 0, len(m))
	for _, a := range m {
		out = append(out, *a)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].LastAt > out[i].LastAt })
	if filter.Limit > 0 && len(out) > filter.Limit {
		out = out[:filter.Limit]
	}
	return out, nil
}
