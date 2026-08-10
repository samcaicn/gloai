// Package model 定义 edict Go 服务的数据模型，字段命名对齐前端 TS 类型
// （edict/edict/frontend/src/api.ts），保证 React 前端无需改动即可对接。
package model

// ── 任务 / 看板 ──

type FlowEntry struct {
	At     string `json:"at"`
	From   string `json:"from"`
	To     string `json:"to"`
	Remark string `json:"remark"`
}

type TodoItem struct {
	ID     any    `json:"id"`
	Title  string `json:"title"`
	Status string `json:"status"` // not-started | in-progress | completed
	Detail string `json:"detail,omitempty"`
}

type Heartbeat struct {
	Status string `json:"status"` // active | warn | stalled | unknown | idle
	Label  string `json:"label"`
}

type Task struct {
	ID          string          `json:"id"`
	Title       string          `json:"title"`
	State       string          `json:"state"`
	Org         string          `json:"org"`
	Official    string          `json:"official,omitempty"`
	Now         string          `json:"now"`
	ETA         string          `json:"eta"`
	Block       string          `json:"block"`
	AC          string          `json:"ac"`
	Output      string          `json:"output"`
	Heartbeat   Heartbeat       `json:"heartbeat"`
	FlowLog     []FlowEntry     `json:"flow_log"`
	Todos       []TodoItem      `json:"todos"`
	ReviewRound int             `json:"review_round"`
	Archived    bool            `json:"archived"`
	ArchivedAt  string          `json:"archivedAt,omitempty"`
	UpdatedAt   string          `json:"updatedAt,omitempty"`
	SourceMeta  map[string]any  `json:"sourceMeta,omitempty"`
	Activity    []ActivityEntry `json:"activity,omitempty"`
	PrevState   string          `json:"_prev_state,omitempty"`
}

// ── 实时状态聚合 ──

type SyncStatus struct {
	OK bool `json:"ok"`
}

type LiveStatus struct {
	Tasks      []Task     `json:"tasks"`
	SyncStatus SyncStatus `json:"syncStatus"`
}

// ── 官员 / Agent 配置 ──

type SkillInfo struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	Path        string `json:"path"`
}

type AgentInfo struct {
	ID     string      `json:"id"`
	Label  string      `json:"label"`
	Emoji  string      `json:"emoji"`
	Role   string      `json:"role"`
	Model  string      `json:"model"`
	Skills []SkillInfo `json:"skills"`
}

type KnownModel struct {
	ID       string `json:"id"`
	Label    string `json:"label"`
	Provider string `json:"provider"`
}

type AgentConfig struct {
	Agents          []AgentInfo  `json:"agents"`
	KnownModels     []KnownModel `json:"knownModels,omitempty"`
	DispatchChannel string       `json:"dispatchChannel,omitempty"`
}

type ChangeLogEntry struct {
	At        string `json:"at"`
	AgentID   string `json:"agentId"`
	OldModel  string `json:"oldModel"`
	NewModel  string `json:"newModel"`
	RolledBack bool  `json:"rolledBack,omitempty"`
}

// ── 官员统计（功过簿） ──

type OfficialInfo struct {
	ID             string `json:"id"`
	Label          string `json:"label"`
	Emoji          string `json:"emoji"`
	Role           string `json:"role"`
	Rank           string `json:"rank"`
	Model          string `json:"model"`
	ModelShort     string `json:"model_short"`
	TokensIn       int    `json:"tokens_in"`
	TokensOut      int    `json:"tokens_out"`
	CacheRead      int    `json:"cache_read"`
	CacheWrite     int    `json:"cache_write"`
	CostCNY        float64 `json:"cost_cny"`
	CostUSD        float64 `json:"cost_usd"`
	Sessions       int    `json:"sessions"`
	Messages       int    `json:"messages"`
	TasksDone      int    `json:"tasks_done"`
	TasksActive    int    `json:"tasks_active"`
	FlowParts      int    `json:"flow_participations"`
	MeritScore     int    `json:"merit_score"`
	MeritRank      int    `json:"merit_rank"`
	LastActive     string `json:"last_active"`
	Heartbeat      Heartbeat `json:"heartbeat"`
}

type OfficialsData struct {
	Officials []OfficialInfo `json:"officials"`
	Totals    struct {
		TasksDone int     `json:"tasks_done"`
		CostCNY   float64 `json:"cost_cny"`
	} `json:"totals"`
	TopOfficial string `json:"top_official"`
}

// ── Agent 运行状态 ──

type AgentStatusInfo struct {
	ID         string `json:"id"`
	Label      string `json:"label"`
	Emoji      string `json:"emoji"`
	Role       string `json:"role"`
	Status     string `json:"status"` // running | idle | offline | unconfigured
	StatusLabel string `json:"statusLabel"`
	LastActive string `json:"lastActive,omitempty"`
}

type GatewayStatus struct {
	Alive  bool   `json:"alive"`
	Probe  bool   `json:"probe"`
	Status string `json:"status"`
}

type AgentsStatusData struct {
	OK        bool            `json:"ok"`
	Gateway   GatewayStatus   `json:"gateway"`
	Agents    []AgentStatusInfo `json:"agents"`
	CheckedAt string          `json:"checkedAt"`
}

// ── 天下要闻 ──

type MorningNewsItem struct {
	Title    string `json:"title"`
	Summary  string `json:"summary,omitempty"`
	Desc     string `json:"desc,omitempty"`
	Link     string `json:"link"`
	Source   string `json:"source"`
	Image    string `json:"image,omitempty"`
	PubDate  string `json:"pub_date,omitempty"`
}

type MorningBrief struct {
	Date        string                     `json:"date,omitempty"`
	GeneratedAt string                     `json:"generated_at,omitempty"`
	Categories  map[string][]MorningNewsItem `json:"categories"`
}

type SubCategoryConfig struct {
	Name    string `json:"name"`
	Enabled bool   `json:"enabled"`
}

type CustomFeed struct {
	Name     string `json:"name"`
	URL      string `json:"url"`
	Category string `json:"category"`
}

type SubConfig struct {
	Categories   []SubCategoryConfig `json:"categories"`
	Keywords     []string            `json:"keywords"`
	CustomFeeds  []CustomFeed        `json:"custom_feeds"`
	FeishuWebhook string             `json:"feishu_webhook"`
}

// ── 任务动态 / 调度 ──

type ActivityEntry struct {
	Kind      string `json:"kind"`
	At        any    `json:"at,omitempty"`
	Text      string `json:"text,omitempty"`
	Thinking  string `json:"thinking,omitempty"`
	Agent     string `json:"agent,omitempty"`
	From      string `json:"from,omitempty"`
	To        string `json:"to,omitempty"`
	Remark    string `json:"remark,omitempty"`
	Tools     []any  `json:"tools,omitempty"`
	Tool      string `json:"tool,omitempty"`
	Output    string `json:"output,omitempty"`
	ExitCode  *int   `json:"exitCode,omitempty"`
	Items     []TodoItem `json:"items,omitempty"`
	Diff      any    `json:"diff,omitempty"`
}

type PhaseDuration struct {
	Phase       string `json:"phase"`
	DurationSec int    `json:"durationSec"`
	DurationText string `json:"durationText"`
	Ongoing     bool   `json:"ongoing,omitempty"`
}

type TodosSummary struct {
	Total      int `json:"total"`
	Completed  int `json:"completed"`
	InProgress int `json:"inProgress"`
	NotStarted int `json:"notStarted"`
	Percent    int `json:"percent"`
}

type ResourceSummary struct {
	TotalTokens    int `json:"totalTokens,omitempty"`
	TotalCost      int `json:"totalCost,omitempty"`
	TotalElapsedSec int `json:"totalElapsedSec,omitempty"`
}

type TaskActivityData struct {
	OK             bool            `json:"ok"`
	Message        string          `json:"message,omitempty"`
	Error          string          `json:"error,omitempty"`
	Activity       []ActivityEntry `json:"activity,omitempty"`
	RelatedAgents  []string        `json:"relatedAgents,omitempty"`
	AgentLabel     string          `json:"agentLabel,omitempty"`
	LastActive     string          `json:"lastActive,omitempty"`
	PhaseDurations []PhaseDuration `json:"phaseDurations,omitempty"`
	TotalDuration  string          `json:"totalDuration,omitempty"`
	TodosSummary   *TodosSummary    `json:"todosSummary,omitempty"`
	ResourceSummary *ResourceSummary `json:"resourceSummary,omitempty"`
}

type SchedulerInfo struct {
	RetryCount        int    `json:"retryCount,omitempty"`
	EscalationLevel   int    `json:"escalationLevel,omitempty"`
	LastDispatchStatus string `json:"lastDispatchStatus,omitempty"`
	StallThresholdSec int    `json:"stallThresholdSec,omitempty"`
	Enabled           bool   `json:"enabled,omitempty"`
	LastProgressAt    string `json:"lastProgressAt,omitempty"`
	LastDispatchAt    string `json:"lastDispatchAt,omitempty"`
	LastDispatchAgent string `json:"lastDispatchAgent,omitempty"`
	AutoRollback      bool   `json:"autoRollback,omitempty"`
}

type SchedulerStateData struct {
	OK        bool          `json:"ok"`
	Error     string        `json:"error,omitempty"`
	Scheduler *SchedulerInfo `json:"scheduler,omitempty"`
	StalledSec int          `json:"stalledSec,omitempty"`
}

type ScanAction struct {
	TaskID     string `json:"taskId"`
	Action     string `json:"action"`
	To         string `json:"to,omitempty"`
	ToState    string `json:"toState,omitempty"`
	StalledSec int    `json:"stalledSec,omitempty"`
}

// ── 技能 ──

type SkillContentResult struct {
	OK      bool   `json:"ok"`
	Name    string `json:"name,omitempty"`
	Agent   string `json:"agent,omitempty"`
	Content string `json:"content,omitempty"`
	Path    string `json:"path,omitempty"`
	Error   string `json:"error,omitempty"`
}

type RemoteSkillItem struct {
	SkillName  string `json:"skillName"`
	AgentID    string `json:"agentId"`
	SourceURL  string `json:"sourceUrl"`
	Description string `json:"description"`
	LocalPath  string `json:"localPath"`
	AddedAt    string `json:"addedAt"`
	LastUpdated string `json:"lastUpdated"`
	Status     string `json:"status"`
}

type RemoteSkillsListResult struct {
	OK          bool             `json:"ok"`
	RemoteSkills []RemoteSkillItem `json:"remoteSkills,omitempty"`
	Count       int              `json:"count,omitempty"`
	ListedAt    string           `json:"listedAt,omitempty"`
	Error       string           `json:"error,omitempty"`
}

// ── 朝堂议政 ──

type CourtDiscussMessage struct {
	OfficialID string `json:"official_id"`
	Name       string `json:"name"`
	Content    string `json:"content"`
	Emotion    string `json:"emotion,omitempty"`
	Action     string `json:"action,omitempty"`
}

type CourtDiscussResult struct {
	OK           bool                  `json:"ok"`
	SessionID    string                `json:"session_id,omitempty"`
	Topic        string                `json:"topic,omitempty"`
	Round        int                   `json:"round,omitempty"`
	NewMessages  []CourtDiscussMessage `json:"new_messages,omitempty"`
	SceneNote    string                `json:"scene_note,omitempty"`
	TotalMessages int                  `json:"total_messages,omitempty"`
	Error        string                `json:"error,omitempty"`
}

// ── 通用 ──

type ActionResult struct {
	OK      bool   `json:"ok"`
	Message string `json:"message,omitempty"`
	Error   string `json:"error,omitempty"`
}

type CreateTaskPayload struct {
	Title       string            `json:"title"`
	Org         string            `json:"org"`
	TargetDept  string            `json:"targetDept,omitempty"`
	Priority    string            `json:"priority,omitempty"`
	TemplateID  string            `json:"templateId,omitempty"`
	Params      map[string]string `json:"params,omitempty"`
	ID          string            `json:"-"`
	Official    string            `json:"-"`
}
