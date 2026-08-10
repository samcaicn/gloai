// Package usage 提供 tenant-memory 应用侧（/chat 调用系统 LLM）的 token 用量
// 记录能力。设计上镜像 Hub 的 internal/ai 包：包级别安装一个 Recorder，所有
// LLM 调用在完成后上报一条 UsageRecord，由上层（main.go）落库。
package usage

import (
	"sync"
	"time"
)

// UsageRecord 一条 LLM 调用的 token 用量记录。
// 每次系统 LLM 调用都会记录 token、调用次数（每条记录为一次调用，聚合时求和）
// 以及调用耗时（毫秒）。
type UsageRecord struct {
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

// Recorder 接收一条用量记录。
type Recorder func(UsageRecord)

var (
	mu       sync.RWMutex
	recorder Recorder
)

// SetRecorder 安装全局记录器。传 nil 可清空。
func SetRecorder(fn Recorder) {
	mu.Lock()
	defer mu.Unlock()
	recorder = fn
}

// Record 上报一条用量记录；未安装记录器时为空操作。
func Record(r UsageRecord) {
	mu.RLock()
	fn := recorder
	mu.RUnlock()
	if fn != nil {
		fn(r)
	}
}

// Now 返回当前秒级时间戳，便于构造记录时间。
func Now() int64 { return time.Now().Unix() }
