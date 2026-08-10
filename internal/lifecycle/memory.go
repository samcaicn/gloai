package lifecycle

import (
	"context"
	"encoding/json"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// MemoryService launches the per-tenant memory service (tms) as a sidecar in
// the same container as the Hub, so personalized memory runs consolidated with
// the Hub. It is best-effort: if the tms binary is absent the Hub still runs.
//
// It mirrors the previous startMemoryService() behaviour exactly, just wrapped
// in the lifecycle.Service contract so the Supervisor owns its start/stop.
type MemoryService struct {
	store store.Store
	cmd   *exec.Cmd
}

// NewMemoryService builds a MemoryService. st is used to propagate the
// platform's unified system LLM interface (ACC_PRODUCT_CONFIG_V2) to the
// sidecar when the operator has not overridden it via TMS_LLM_* env vars.
func NewMemoryService(st store.Store) *MemoryService { return &MemoryService{store: st} }

// Start launches the tms sidecar. The process is bound to ctx, so it is killed
// automatically when the Hub shuts down (SIGINT/SIGTERM).
func (m *MemoryService) Start(ctx context.Context) error {
	bin := os.Getenv("TMS_BIN")
	if bin == "" {
		if exe, err := os.Executable(); err == nil {
			bin = filepath.Join(filepath.Dir(exe), "tms")
		}
	}
	if bin == "" {
		return nil
	}
	if _, err := os.Stat(bin); err != nil {
		slog.Info("memory service skipped: tms binary not found", "path", bin)
		return nil
	}
	port := os.Getenv("TMS_PORT")
	if port == "" {
		port = "8090"
	}
	dataDir := os.Getenv("TMS_DATA_DIR")
	if dataDir == "" {
		dataDir = "/workspace/edict-shared"
	}
	cmd := exec.CommandContext(ctx, bin)
	cmd.Env = append(os.Environ(),
		"PORT="+port,
		"STORE=file",
		"DATA_DIR="+dataDir,
	)
	// Forward explicit LLM overrides from TMS_* env vars. CRITICAL: do NOT
	// default LLM_API_KEY to "mock". When LLM_API_KEY is left unset, the tms
	// config falls back to applySystemLLM(), which reads the platform's unified
	// system LLM interface (ACC_PRODUCT_CONFIG_V2). A forced "mock" default
	// would make applySystemLLM() return early and silently block the tms
	// application from ever using the system LLM. An operator can still opt into
	// mock mode by setting TMS_LLM_API_KEY=mock explicitly.
	if v := os.Getenv("TMS_LLM_API_KEY"); v != "" {
		cmd.Env = append(cmd.Env, "LLM_API_KEY="+v)
	}
	if v := os.Getenv("TMS_LLM_BASE_URL"); v != "" {
		cmd.Env = append(cmd.Env, "LLM_BASE_URL="+v)
	}
	if v := os.Getenv("TMS_LLM_MODEL"); v != "" {
		cmd.Env = append(cmd.Env, "LLM_MODEL="+v)
	}
	if v := os.Getenv("TMS_EMBED_BASE_URL"); v != "" {
		cmd.Env = append(cmd.Env, "EMBED_BASE_URL="+v)
	}
	if v := os.Getenv("TMS_EMBED_API_KEY"); v != "" {
		cmd.Env = append(cmd.Env, "EMBED_API_KEY="+v)
	}
	if v := os.Getenv("TMS_EMBED_MODEL"); v != "" {
		cmd.Env = append(cmd.Env, "EMBED_MODEL="+v)
	}
	// Propagate the platform's unified system LLM interface to the sidecar.
	// The Hub stores its global OpenAI-compatible interface under the `ai.*`
	// config keys (configured via the admin UI). The tms service falls back to
	// applySystemLLM(), which reads ACC_PRODUCT_CONFIG_V2 to reach the same
	// system LLM the Hub's tenants use. Without this, a freshly configured
	// system LLM is never seen by the sidecar and it silently drops to mock
	// mode. We only set it when the operator has not overridden the LLM
	// explicitly via TMS_LLM_*, so explicit overrides always win.
	if os.Getenv("TMS_LLM_API_KEY") == "" && os.Getenv("TMS_LLM_BASE_URL") == "" {
		if sysLLM := buildSystemLLMEnv(m.store); sysLLM != "" {
			cmd.Env = append(cmd.Env, "ACC_PRODUCT_CONFIG_V2="+sysLLM)
		}
	}
	if lf, err := os.OpenFile(filepath.Join(filepath.Dir(bin), "tms-from-oih.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
		cmd.Stdout = lf
		cmd.Stderr = lf
	}
	if err := cmd.Start(); err != nil {
		slog.Error("memory service failed to start", "err", err)
		return nil
	}
	m.cmd = cmd
	slog.Info("memory service started", "bin", bin, "port", port, "data", dataDir)
	go func() {
		if err := cmd.Wait(); err != nil && ctx.Err() == nil {
			slog.Warn("memory service exited", "err", err)
		}
	}()
	return nil
}

// Stop kills the sidecar if it is still running. (exec.CommandContext also
// terminates it on ctx cancellation, so this is a belt-and-braces cleanup.)
func (m *MemoryService) Stop(ctx context.Context) error {
	if m.cmd != nil && m.cmd.Process != nil {
		_ = m.cmd.Process.Kill()
	}
	return nil
}

// buildSystemLLMEnv builds the ACC_PRODUCT_CONFIG_V2 JSON that points the tms
// sidecar at the platform's unified system LLM interface. It mirrors what
// tenantchat.globalAIConfig / sink.resolveGlobalConfig already read from the
// `ai.*` config keys, so the sidecar application uses exactly the same system
// LLM as the Hub's tenants. Returns "" when the store is nil or the system LLM
// has no API key configured (applySystemLLM would fall back to mock anyway).
func buildSystemLLMEnv(st store.Store) string {
	if st == nil {
		return ""
	}
	conf, err := st.ListConfigByPrefix("ai.")
	if err != nil || conf == nil {
		return ""
	}
	apiKey := conf["ai.api_key"]
	if apiKey == "" {
		return ""
	}
	baseURL := conf["ai.base_url"]
	if baseURL == "" {
		baseURL = "https://api.openai.com/v1"
	}
	payload := map[string]any{
		"endpoint": baseURL,
		"authentication": map[string]any{
			"attributes": map[string]any{
				"token": apiKey,
			},
		},
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return ""
	}
	return string(b)
}
