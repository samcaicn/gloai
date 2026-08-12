package shared

import (
	"context"
	"errors"
)

// Context carries tenant_id, client_id, and other auth info for MCP handlers
type Context struct {
	context.Context
	TenantID     string
	ClientID     string
	DeviceToken  string
	InstallationID string
	IsAdmin      bool
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

// WithInstallationID adds installation_id to context
func WithInstallationID(ctx context.Context, installationID string) context.Context {
	return context.WithValue(ctx, installationIDKey{}, installationID)
}

func GetInstallationID(ctx context.Context) string {
	if v := ctx.Value(installationIDKey{}); v != nil {
		return v.(string)
	}
	return ""
}

type installationIDKey struct{}

// Error types for MCP error codes
var (
	ErrMissingParam     = errors.New("missing parameter")
	ErrInvalidParam     = errors.New("invalid parameter")
	ErrNotFound         = errors.New("not found")
	ErrAlreadyDone      = errors.New("already done")
	ErrForbidden        = errors.New("forbidden")
	ErrInsufficientBalance = errors.New("insufficient balance")
	ErrResourceError    = errors.New("resource error")
	ErrActionUnknown    = errors.New("action unknown")
)

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