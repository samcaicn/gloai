package mcp

import (
	"context"
	"encoding/json"

	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
)

// Action is the MCP action name format: "category.action"
type Action string

const (
	// Task actions
	ActionTaskCreate        Action = "task.create"
	ActionTaskGet           Action = "task.get"
	ActionTaskList          Action = "task.list"
	ActionTaskPollPending   Action = "task.poll_pending"
	ActionTaskDelivered     Action = "task.delivered"
	ActionTaskAcknowledge   Action = "task.acknowledge"
	ActionTaskComplete      Action = "task.complete"
	ActionTaskFail          Action = "task.fail"
	ActionTaskCancel        Action = "task.cancel"

	// Client/Device actions
	ActionClientHeartbeat       Action = "client.heartbeat"
	ActionClientFingerprintBind Action = "client.fingerprint.bind"
	ActionClientFingerprintStatus Action = "client.fingerprint.status"
	ActionClientUnbind          Action = "client.unbind"
	ActionClientUnbindStatus    Action = "client.unbind.status"
	ActionClientBind            Action = "client.bind"
	ActionClientBindStatus      Action = "client.bind.status"
	ActionClientCheckUpdate     Action = "client.check_update"

	// LLM actions
	ActionLLMRequest       Action = "llm.request"
	ActionLLMStreamRequest Action = "llm.stream_request"

	// Skill actions
	ActionSkillSearch      Action = "skill.search"
	ActionSkillDetail      Action = "skill.detail"
	ActionSkillCreate      Action = "skill.create"
	ActionSkillUpload      Action = "skill.upload"
	ActionSkillCall        Action = "skill.call"
	ActionSkillInstallConfirm Action = "skill.install_confirm"
	ActionSkillReportExec  Action = "skill.report_execution"
	ActionSkillEvaluation  Action = "skill.evaluation"

	// Billing actions
	ActionBillingConfig    Action = "billing.config"
	ActionBillingLedger    Action = "billing.ledger"
	ActionBillingUploadTicket Action = "billing.upload_ticket"
	ActionBillingConfirmUpload Action = "billing.confirm_upload"

	// Search actions
	ActionSearchSignalsReport Action = "search.signals.report"
)

// Request is the incoming MCP JSON-RPC request
type Request struct {
	ID     string                 `json:"id"`
	Action Action                 `json:"action"`
	Params map[string]any         `json:"params,omitempty"`
}

// Response is the MCP JSON-RPC response
type Response struct {
	ID     string         `json:"id"`
	OK     bool           `json:"ok"`
	Data   any            `json:"data,omitempty"`
	Error  *ErrorResponse `json:"error,omitempty"`
}

type ErrorResponse struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Context carries tenant_id, client_id, and other auth info
type Context = shared.Context

// Handler processes an MCP action
type Handler func(ctx *Context, params map[string]any) (any, error)

// Registry maps actions to handlers
type Registry struct {
	handlers map[Action]Handler
}

func NewRegistry() *Registry {
	return &Registry{handlers: make(map[Action]Handler)}
}

func (r *Registry) Register(action Action, h Handler) {
	r.handlers[action] = h
}

func (r *Registry) Get(action Action) (Handler, bool) {
	h, ok := r.handlers[action]
	return h, ok
}

func (r *Registry) Dispatch(ctx *Context, req Request) Response {
	h, ok := r.handlers[req.Action]
	if !ok {
		return Response{
			ID: req.ID,
			OK: false,
			Error: &ErrorResponse{
				Code:    "action_unknown",
				Message: "unknown action: " + string(req.Action),
			},
		}
	}

	data, err := h(ctx, req.Params)
	if err != nil {
		return Response{
			ID: req.ID,
			OK: false,
			Error: &ErrorResponse{
				Code:    errorCode(err),
				Message: err.Error(),
			},
		}
	}

	return Response{
		ID:   req.ID,
		OK:   true,
		Data: data,
	}
}

func errorCode(err error) string {
	switch err.(type) {
	case *MissingParamError:
		return "missing_param"
	case *InvalidParamError:
		return "invalid_param"
	case *NotFoundError:
		return "not_found"
	case *AlreadyDoneError:
		return "already_done"
	case *ForbiddenError:
		return "forbidden"
	case *InsufficientBalanceError:
		return "insufficient_balance"
	case *ResourceError:
		return "resource_error"
	default:
		return "internal_error"
	}
}

// Error types for MCP error codes
type MissingParamError struct{ Param string }
func (e *MissingParamError) Error() string { return "missing param: " + e.Param }

type InvalidParamError struct{ Param, Reason string }
func (e *InvalidParamError) Error() string { return "invalid param " + e.Param + ": " + e.Reason }

type NotFoundError struct{ Resource, ID string }
func (e *NotFoundError) Error() string { return e.Resource + " not found: " + e.ID }

type AlreadyDoneError struct{ Resource, Status string }
func (e *AlreadyDoneError) Error() string { return e.Resource + " already " + e.Status }

type ForbiddenError struct{ Reason string }
func (e *ForbiddenError) Error() string { return "forbidden: " + e.Reason }

type InsufficientBalanceError struct{}
func (e *InsufficientBalanceError) Error() string { return "insufficient balance" }

type ResourceError struct{ Code, Reason string }
func (e *ResourceError) Error() string { return e.Code + ": " + e.Reason }

func MissingParam(param string) error         { return &MissingParamError{Param: param} }
func InvalidParam(param, reason string) error { return &InvalidParamError{Param: param, Reason: reason} }
func NotFound(resource, id string) error      { return &NotFoundError{Resource: resource, ID: id} }
func AlreadyDone(resource, status string) error { return &AlreadyDoneError{Resource: resource, Status: status} }
func Forbidden(reason string) error           { return &ForbiddenError{Reason: reason} }
func InsufficientBalance() error              { return &InsufficientBalanceError{} }
func ResourceErr(code, reason string) error   { return &ResourceError{Code: code, Reason: reason} }

// Convenience: parse request from JSON
func ParseRequest(data []byte) (Request, error) {
	var req Request
	err := json.Unmarshal(data, &req)
	return req, err
}

// MarshalResponse encodes response to JSON
func MarshalResponse(r Response) ([]byte, error) {
	return json.Marshal(r)
}

// WithTenantID adds tenant_id to context
func WithTenantID(ctx context.Context, tenantID string) context.Context {
	return context.WithValue(ctx, tenantIDKey{}, tenantID)
}

func GetTenantID(ctx context.Context) string {
	if v := ctx.Value(tenantIDKey{}); v != nil {
		return v.(string)
	}
	return ""
}

type tenantIDKey struct{}

// WithClientID adds client_id to context
func WithClientID(ctx context.Context, clientID string) context.Context {
	return context.WithValue(ctx, clientIDKey{}, clientID)
}

func GetClientID(ctx context.Context) string {
	if v := ctx.Value(clientIDKey{}); v != nil {
		return v.(string)
	}
	return ""
}

type clientIDKey struct{}

// WithDeviceToken adds device_token to context
func WithDeviceToken(ctx context.Context, token string) context.Context {
	return context.WithValue(ctx, deviceTokenKey{}, token)
}

func GetDeviceToken(ctx context.Context) string {
	if v := ctx.Value(deviceTokenKey{}); v != nil {
		return v.(string)
	}
	return ""
}

type deviceTokenKey struct{}

// MCPServerConfig holds config for MCP server
type MCPServerConfig struct {
	// Reserve decorator for token ledger
	TokenReserveDecoratorEnabled bool
	// Max tokens for LLM requests
	LLMMaxTokens int
	// Default model
	LLMDefaultModel string
}

func DefaultMCPServerConfig() MCPServerConfig {
	return MCPServerConfig{
		TokenReserveDecoratorEnabled: true,
		LLMMaxTokens:                 8192,
		LLMDefaultModel:              "gpt-4o-mini",
	}
}