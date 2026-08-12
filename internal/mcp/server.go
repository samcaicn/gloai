package mcp

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/mcp/actions"
	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// Server is the MCP JSON-RPC server
type Server struct {
	registry *Registry
	store    store.Store
	config   MCPServerConfig
}

func NewServer(s store.Store, config MCPServerConfig) *Server {
	server := &Server{
		registry: NewRegistry(),
		store:    s,
		config:   config,
	}
	server.registerActions()
	return server
}

func (s *Server) registerActions() {
	// Task actions
	taskMgr := actions.NewTaskManager(s.store)
	s.registry.Register(ActionTaskCreate, taskMgr.CreateTask)
	s.registry.Register(ActionTaskGet, taskMgr.GetTask)
	s.registry.Register(ActionTaskList, taskMgr.ListTasks)
	s.registry.Register(ActionTaskPollPending, taskMgr.PollPendingTasks)
	s.registry.Register(ActionTaskDelivered, taskMgr.MarkDelivered)
	s.registry.Register(ActionTaskAcknowledge, taskMgr.AcknowledgeTask)
	s.registry.Register(ActionTaskComplete, taskMgr.CompleteTask)
	s.registry.Register(ActionTaskFail, taskMgr.FailTask)
	s.registry.Register(ActionTaskCancel, taskMgr.CancelTask)

	// Client/Device actions
	deviceMgr := actions.NewDeviceManager(s.store)
	s.registry.Register(ActionClientHeartbeat, deviceMgr.Heartbeat)
	s.registry.Register(ActionClientFingerprintBind, deviceMgr.FingerprintBind)
	s.registry.Register(ActionClientFingerprintStatus, deviceMgr.FingerprintStatus)
	s.registry.Register(ActionClientUnbind, deviceMgr.Unbind)
	s.registry.Register(ActionClientUnbindStatus, deviceMgr.UnbindStatus)
	s.registry.Register(ActionClientBind, deviceMgr.Bind)
	s.registry.Register(ActionClientBindStatus, deviceMgr.BindStatus)
	s.registry.Register(ActionClientCheckUpdate, deviceMgr.CheckUpdate)

	// LLM actions
	llmMgr := actions.NewLLMManager(s.store)
	s.registry.Register(ActionLLMRequest, llmMgr.Request)
	s.registry.Register(ActionLLMStreamRequest, llmMgr.StreamRequest)

	// Skill actions
	skillMgr := actions.NewSkillManager(s.store)
	s.registry.Register(ActionSkillSearch, skillMgr.Search)
	s.registry.Register(ActionSkillDetail, skillMgr.Detail)
	s.registry.Register(ActionSkillCreate, skillMgr.Create)
	s.registry.Register(ActionSkillUpload, skillMgr.Upload)
	s.registry.Register(ActionSkillCall, skillMgr.Call)
	s.registry.Register(ActionSkillInstallConfirm, skillMgr.InstallConfirm)
	s.registry.Register(ActionSkillReportExec, skillMgr.ReportExecution)
	s.registry.Register(ActionSkillEvaluation, skillMgr.Evaluation)

	// Billing actions
	billingMgr := actions.NewBillingManager(s.store)
	s.registry.Register(ActionBillingConfig, billingMgr.Config)
	s.registry.Register(ActionBillingLedger, billingMgr.Ledger)
	s.registry.Register(ActionBillingUploadTicket, billingMgr.UploadTicket)
	s.registry.Register(ActionBillingConfirmUpload, billingMgr.ConfirmUpload)

	// Search actions
	searchMgr := actions.NewSearchManager(s.store)
	s.registry.Register(ActionSearchSignalsReport, searchMgr.ReportSignals)
}

func (s *Server) Handler() http.Handler {
	return http.HandlerFunc(s.handleHTTP)
}

func (s *Server) handleHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()

	// Only accept POST
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Parse request
	var req Request
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}

	// Extract auth context
	ctx := s.extractContext(r)
	if ctx == nil {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	// Dispatch
	resp := s.registry.Dispatch(ctx, req)

	// Write response
	w.Header().Set("Content-Type", "application/json")
	if !resp.OK {
		w.WriteHeader(http.StatusBadRequest)
	}
	if err := json.NewEncoder(w).Encode(resp); err != nil {
		slog.Error("mcp: encode response failed", "err", err)
	}

	slog.Info("mcp: request", "action", req.Action, "ok", resp.OK, "duration_ms", time.Since(start).Milliseconds())
}

func (s *Server) extractContext(r *http.Request) *shared.Context {
	// Extract Authorization header
	authHeader := r.Header.Get("Authorization")
	if authHeader == "" {
		return nil
	}

	// Bearer token
	token := ""
	if len(authHeader) > 7 && authHeader[:7] == "Bearer " {
		token = authHeader[7:]
	} else {
		return nil
	}

	// Validate session/token
	installation, err := s.store.GetInstallationByToken(token)
	if err != nil || installation == nil || !installation.Enabled {
		return nil
	}

	return &shared.Context{
		Context:        r.Context(),
		TenantID:       installation.TenantID,
		ClientID:       installation.ClientID,
		DeviceToken:    token,
		InstallationID: installation.ID,
	}
}

// MCPHandler is the main entry point for MCP requests
// Can be mounted at /api/v2/mcp
func MCPHandler(s store.Store) http.Handler {
	server := NewServer(s, DefaultMCPServerConfig())
	return server.Handler()
}