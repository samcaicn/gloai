package server

import (
	"fmt"
	"io"
	"strconv"
	"time"

	"tenant-memory/internal/store"
)

// 自检状态常量。
const (
	StatusOK   = "ok"
	StatusWarn = "warn"
	StatusFail = "fail"
)

// CheckResult 单条自检结果。
type CheckResult struct {
	Name      string `json:"name"`
	Status    string `json:"status"` // StatusOK | StatusWarn | StatusFail
	Fatal     bool   `json:"fatal"`  // true 表示失败会阻断启动
	Detail    string `json:"detail"`
	ElapsedMs int64  `json:"elapsed_ms"`
}

func since(t time.Time) int64 { return time.Since(t).Milliseconds() }

// Preflight 启动预检：验证存储读写链路与向量化是否可用。LLM 在未配置 key /
// mock 模式下仅做 warn（不阻断启动）。所有结果返回供上层打印与判定。
func (s *Server) Preflight() []CheckResult {
	return s.runChecks(false)
}

// SelfTest 端到端自测：在预检基础上覆盖记忆写入、向量召回、chat 注入，
// 用于 `tms --selftest` 或 /debug 接口。
func (s *Server) SelfTest() []CheckResult {
	return s.runChecks(true)
}

func (s *Server) runChecks(selftest bool) []CheckResult {
	results := []CheckResult{}

	// 1) 存储读写闭环（EnsureTenant -> AddMemory -> GetMemory -> DeleteMemory）。
	{
		start := time.Now()
		tenant := "__preflight__"
		if err := s.st.EnsureTenant(tenant); err != nil {
			results = append(results, CheckResult{Name: "store.ensure_tenant", Status: StatusFail, Fatal: true, Detail: err.Error(), ElapsedMs: since(start)})
		} else {
			m, err := s.st.AddMemory(tenant, "preference", "preflight probe memory")
			if err != nil {
				results = append(results, CheckResult{Name: "store.add_memory", Status: StatusFail, Fatal: true, Detail: err.Error(), ElapsedMs: since(start)})
			} else {
				if _, gerr := s.st.GetMemory(tenant, m.ID); gerr != nil {
					results = append(results, CheckResult{Name: "store.get_memory", Status: StatusFail, Fatal: true, Detail: gerr.Error(), ElapsedMs: since(start)})
				} else {
					results = append(results, CheckResult{Name: "store.roundtrip", Status: StatusOK, Detail: "wrote & read back id=" + m.ID, ElapsedMs: since(start)})
				}
				_ = s.st.DeleteMemory(tenant, m.ID)
			}
		}
	}

	// 2) 向量化可用性与形状检查。
	{
		start := time.Now()
		vecs, err := s.embed.Embed([]string{"健康检查 probe", "hello world"})
		if err != nil {
			results = append(results, CheckResult{Name: "embed.sample", Status: StatusFail, Fatal: true, Detail: err.Error(), ElapsedMs: since(start)})
		} else if len(vecs) != 2 || len(vecs[0]) == 0 {
			results = append(results, CheckResult{Name: "embed.sample", Status: StatusFail, Fatal: true, Detail: fmt.Sprintf("unexpected vector shape %dx", len(vecs)), ElapsedMs: since(start)})
		} else {
			results = append(results, CheckResult{Name: "embed.sample", Status: StatusOK, Detail: fmt.Sprintf("embedded %d texts, dim=%d", len(vecs), len(vecs[0])), ElapsedMs: since(start)})
		}
	}

	// 3) LLM 端点探活（仅 warn，不阻断启动）。
	{
		start := time.Now()
		ok, err := s.llm.Health()
		if err != nil {
			results = append(results, CheckResult{Name: "llm.reachable", Status: StatusWarn, Fatal: false, Detail: err.Error(), ElapsedMs: since(start)})
		} else if !ok {
			results = append(results, CheckResult{Name: "llm.reachable", Status: StatusWarn, Fatal: false, Detail: "mock / offline mode (no API key configured)", ElapsedMs: since(start)})
		} else {
			results = append(results, CheckResult{Name: "llm.reachable", Status: StatusOK, Detail: "endpoint reachable", ElapsedMs: since(start)})
		}
	}

	if selftest {
		results = append(results, s.selfTestEndToEnd()...)
	}
	return results
}

// selfTestEndToEnd 覆盖记忆写入 -> 向量召回 -> chat 注入，仅自测模式运行。
func (s *Server) selfTestEndToEnd() []CheckResult {
	results := []CheckResult{}
	tenant := "__selftest__"
	_ = s.st.EnsureTenant(tenant)
	m, err := s.st.AddMemory(tenant, "preference", "self-test: 用户使用中文沟通")
	if err != nil {
		results = append(results, CheckResult{Name: "selftest.write_memory", Status: StatusFail, Fatal: true, Detail: err.Error()})
		return results
	}
	results = append(results, CheckResult{Name: "selftest.write_memory", Status: StatusOK, Detail: "id=" + m.ID})

	mems, rerr := s.Retrieve(tenant, "怎么和用户沟通", s.cfg.RetrieveK)
	if rerr != nil {
		results = append(results, CheckResult{Name: "selftest.retrieve", Status: StatusFail, Fatal: true, Detail: rerr.Error()})
	} else {
		results = append(results, CheckResult{Name: "selftest.retrieve", Status: StatusOK, Detail: fmt.Sprintf("returned %d memories", len(mems))})
	}

	p, _ := s.st.GetProfile(tenant)
	ctx := store.RenderText(p, mems)
	reply, _, cerr := s.llm.Chat("你是测试助手。\n"+ctx, "ping")
	if cerr != nil {
		results = append(results, CheckResult{Name: "selftest.chat", Status: StatusWarn, Fatal: false, Detail: cerr.Error()})
	} else {
		results = append(results, CheckResult{Name: "selftest.chat", Status: StatusOK, Detail: "reply len=" + strconv.Itoa(len(reply))})
	}

	_ = s.st.DeleteMemory(tenant, m.ID)
	return results
}

// PrintResults 把自检结果以可读形式输出到 w（推荐使用 os.Stdout）。
func PrintResults(w io.Writer, results []CheckResult) {
	for _, r := range results {
		icon := "[ok]"
		switch r.Status {
		case StatusWarn:
			icon = "[warn]"
		case StatusFail:
			icon = "[FAIL]"
		}
		fmt.Fprintf(w, "  %s %-22s %s (%dms)%s\n", icon, r.Name, r.Detail, r.ElapsedMs, func() string {
			if r.Fatal {
				return " [fatal]"
			}
			return ""
		}())
	}
}

// HasFatalFailure 是否存在阻断启动的致命失败。
func HasFatalFailure(results []CheckResult) bool {
	for _, r := range results {
		if r.Fatal && r.Status == StatusFail {
			return true
		}
	}
	return false
}

// ExitCode 根据自检结果返回进程退出码（有致命失败则返回 1）。
func ExitCode(results []CheckResult) int {
	if HasFatalFailure(results) {
		return 1
	}
	return 0
}
