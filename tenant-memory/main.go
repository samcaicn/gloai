package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"

	"tenant-memory/internal/config"
	"tenant-memory/internal/server"
	"tenant-memory/internal/store"
	"tenant-memory/internal/usage"
)

func main() {
	selftest := flag.Bool("selftest", false, "运行端到端自测（健康检查/记忆/召回/chat）后退出，不监听端口")
	noPreflight := flag.Bool("no-preflight", false, "跳过启动预检（存储/向量化/LLM 探活）")
	flag.Parse()

	cfg := config.Load()

	st, err := store.Open(cfg.Store, cfg.DBPath, cfg.DataDir)
	if err != nil {
		log.Fatalf("open store: %v", err)
	}
	defer st.Close()

	// 将所有 /chat 调用的系统 LLM token 用量落库，便于与 Hub 的 llm_usage 口径核对。
	usage.SetRecorder(func(r usage.UsageRecord) {
		_ = st.RecordLLMUsage(&store.LLMUsageRecord{
			TenantID:         r.TenantID,
			ChannelID:        r.ChannelID,
			Model:            r.Model,
			ModelType:        r.ModelType,
			PromptTokens:     r.PromptTokens,
			CompletionTokens: r.CompletionTokens,
			TotalTokens:      r.TotalTokens,
			CachedTokens:     r.CachedTokens,
			ReasoningTokens:  r.ReasoningTokens,
			DurationMS:       r.DurationMS,
			CreatedAt:        r.CreatedAt,
		})
	})
	defer usage.SetRecorder(nil)

	srv := server.New(cfg, st)

	// 自测模式：跑完直接退出，不启动 HTTP 服务。
	if *selftest {
		results := srv.SelfTest()
		fmt.Println("tms 自测结果:")
		server.PrintResults(os.Stdout, results)
		os.Exit(server.ExitCode(results))
	}

	// 启动预检：任何 fatal 失败都会阻断启动并打印可读错误。
	if !*noPreflight {
		fmt.Println("tms 启动预检:")
		results := srv.Preflight()
		server.PrintResults(os.Stdout, results)
		if server.HasFatalFailure(results) {
			log.Fatalf("启动预检未通过（存在 fatal 项），请按上述明细修复后重试。")
		}
	}

	addr := fmt.Sprintf(":%d", cfg.Port)
	log.Printf("tenant-memory 服务已启动 -> %s  (store=%s, llm=%s)", addr, cfg.Store, cfg.LLMModel)
	if err := http.ListenAndServe(addr, srv.Handler()); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
