package store

import (
	"context"
	"encoding/json"
)

// TaskStatus represents the status of a task
type TaskStatus string

const (
	TaskStatusPending      TaskStatus = "pending"
	TaskStatusDelivered    TaskStatus = "delivered"
	TaskStatusAcknowledged TaskStatus = "acknowledged"
	TaskStatusCompleted    TaskStatus = "completed"
	TaskStatusFailed       TaskStatus = "failed"
	TaskStatusCancelled    TaskStatus = "cancelled"
)

func (s TaskStatus) String() string {
	return string(s)
}

// Task represents a unit of work assigned to a client
type Task struct {
	ID          string         `json:"id"`
	TenantID    string         `json:"tenant_id"`
	ClientID    string         `json:"client_id"`
	Name        string         `json:"name"`
	Type        string         `json:"type"`
	Payload     json.RawMessage `json:"payload"`
	Metadata    json.RawMessage `json:"metadata"`
	Status      TaskStatus     `json:"status"`
	Result      json.RawMessage `json:"result,omitempty"`
	ErrorMsg    string         `json:"error_message,omitempty"`
	CreatedAt   int64          `json:"created_at"`
	UpdatedAt   int64          `json:"updated_at"`
	DeliveredAt int64          `json:"delivered_at,omitempty"`
	AckedAt     int64          `json:"acked_at,omitempty"`
	CompletedAt int64          `json:"completed_at,omitempty"`
	FailedAt    int64          `json:"failed_at,omitempty"`
}

func (t *Task) ToDict() map[string]any {
	return map[string]any{
		"id":            t.ID,
		"tenant_id":     t.TenantID,
		"client_id":     t.ClientID,
		"name":          t.Name,
		"type":          t.Type,
		"payload":       t.Payload,
		"metadata":      t.Metadata,
		"status":        t.Status.String(),
		"result":        t.Result,
		"error_message": t.ErrorMsg,
		"created_at":    t.CreatedAt,
		"updated_at":    t.UpdatedAt,
		"delivered_at":  t.DeliveredAt,
		"acked_at":      t.AckedAt,
		"completed_at":  t.CompletedAt,
		"failed_at":     t.FailedAt,
	}
}

// TaskStore interface for task operations
type TaskStore interface {
	CreateTask(ctx context.Context, task *Task) error
	GetTask(ctx context.Context, id string) (*Task, error)
	GetTenantTasks(ctx context.Context, tenantID string, status *TaskStatus, limit int) ([]*Task, error)
	GetPendingTasksForClient(ctx context.Context, clientID string, limit int, sinceTaskID string) ([]*Task, error)
	MarkTaskDelivered(ctx context.Context, id string) bool
	AcknowledgeTask(ctx context.Context, id string) bool
	CompleteTask(ctx context.Context, id string, result map[string]any) bool
	FailTask(ctx context.Context, id string, errorMessage string) bool
	CancelTask(ctx context.Context, id string) bool
}