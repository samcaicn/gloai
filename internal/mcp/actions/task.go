package actions

import (
	
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// TaskManager handles task operations
type TaskManager struct {
	store store.Store
}

func NewTaskManager(s store.Store) *TaskManager {
	return &TaskManager{store: s}
}

func (m *TaskManager) CreateTask(ctx *shared.Context, params map[string]any) (any, error) {
	taskName, _ := params["task_name"].(string)
	if taskName == "" {
		return nil, shared.MissingParam("task_name")
	}

	taskType, _ := params["task_type"].(string)
	if taskType == "" {
		taskType = "default"
	}

	payload, _ := params["payload"].(map[string]any)
	if payload == nil {
		payload = map[string]any{}
	}

	metadata, _ := params["metadata"].(map[string]any)
	if metadata == nil {
		metadata = map[string]any{}
	}

	// Add tenant and client info to metadata
	metadata["tenant_id"] = ctx.TenantID
	metadata["client_id"] = ctx.ClientID

	payloadJSON, _ := json.Marshal(payload)
	metadataJSON, _ := json.Marshal(metadata)

	task := &store.Task{
		ID:        fmt.Sprintf("task_%d", time.Now().UnixNano()),
		TenantID:  ctx.TenantID,
		ClientID:  ctx.ClientID,
		Name:      taskName,
		Type:      taskType,
		Payload:   payloadJSON,
		Metadata:  metadataJSON,
		Status:    store.TaskStatusPending,
		CreatedAt: time.Now().Unix(),
		UpdatedAt: time.Now().Unix(),
	}

	if err := m.store.CreateTask(ctx, task); err != nil {
		return nil, err
	}

	return map[string]any{"task_id": task.ID}, nil
}

func (m *TaskManager) GetTask(ctx *shared.Context, params map[string]any) (any, error) {
	taskID, _ := params["task_id"].(string)
	if taskID == "" {
		return nil, shared.MissingParam("task_id")
	}

	task, err := m.store.GetTask(ctx, taskID)
	if err != nil {
		return nil, shared.NotFound("task", taskID)
	}

	// Verify tenant ownership
	if task.TenantID != ctx.TenantID {
		return nil, shared.NotFound("task", taskID)
	}

	return task.ToDict(), nil
}

func (m *TaskManager) ListTasks(ctx *shared.Context, params map[string]any) (any, error) {
	statusStr, _ := params["status"].(string)
	limit := 50
	if l, ok := params["limit"].(float64); ok {
		limit = int(l)
	}
	if limit < 1 || limit > 100 {
		limit = 50
	}

	var status *store.TaskStatus
	if statusStr != "" {
		s := store.TaskStatus(statusStr)
		status = &s
	}

	tasks, err := m.store.GetTenantTasks(ctx, ctx.TenantID, status, limit)
	if err != nil {
		return nil, err
	}

	result := make([]map[string]any, len(tasks))
	for i, t := range tasks {
		result[i] = t.ToDict()
	}
	return map[string]any{"tasks": result}, nil
}

func (m *TaskManager) PollPendingTasks(ctx *shared.Context, params map[string]any) (any, error) {
	clientID := ctx.ClientID
	if cid, ok := params["client_id"].(string); ok && cid != "" {
		clientID = cid
	}
	if clientID == "" {
		return nil, shared.MissingParam("client_id")
	}

	limit := 20
	if l, ok := params["limit"].(float64); ok {
		limit = int(l)
	}
	if limit < 1 || limit > 50 {
		limit = 20
	}

	sinceTaskID, _ := params["since_task_id"].(string)

	tasks, err := m.store.GetPendingTasksForClient(ctx, clientID, limit, sinceTaskID)
	if err != nil {
		return nil, err
	}

	// Filter by tenant
	filtered := make([]*store.Task, 0, len(tasks))
	for _, t := range tasks {
		if t.TenantID == ctx.TenantID {
			filtered = append(filtered, t)
		}
	}

	lastTaskID := ""
	if len(filtered) > 0 {
		lastTaskID = filtered[len(filtered)-1].ID
	}

	result := make([]map[string]any, len(filtered))
	for i, t := range filtered {
		result[i] = t.ToDict()
	}

	return map[string]any{
		"tasks": result,
		"cursor": map[string]any{
			"last_task_id": lastTaskID,
			"has_more":     len(filtered) >= limit,
		},
	}, nil
}

func (m *TaskManager) MarkDelivered(ctx *shared.Context, params map[string]any) (any, error) {
	taskID, _ := params["task_id"].(string)
	if taskID == "" {
		return nil, shared.MissingParam("task_id")
	}

	if err := m.verifyTaskOwnership(ctx, taskID); err != nil {
		return nil, err
	}

	if !m.store.MarkTaskDelivered(ctx, taskID) {
		task, _ := m.store.GetTask(ctx, taskID)
		return nil, shared.AlreadyDone("task", task.Status.String())
	}

	return map[string]any{"status": "delivered"}, nil
}

func (m *TaskManager) AcknowledgeTask(ctx *shared.Context, params map[string]any) (any, error) {
	taskID, _ := params["task_id"].(string)
	if taskID == "" {
		return nil, shared.MissingParam("task_id")
	}

	if err := m.verifyTaskOwnership(ctx, taskID); err != nil {
		return nil, err
	}

	if !m.store.AcknowledgeTask(ctx, taskID) {
		task, _ := m.store.GetTask(ctx, taskID)
		return nil, shared.AlreadyDone("task", task.Status.String())
	}

	return map[string]any{"status": "acknowledged"}, nil
}

func (m *TaskManager) CompleteTask(ctx *shared.Context, params map[string]any) (any, error) {
	taskID, _ := params["task_id"].(string)
	if taskID == "" {
		return nil, shared.MissingParam("task_id")
	}

	if err := m.verifyTaskOwnership(ctx, taskID); err != nil {
		return nil, err
	}

	result, _ := params["result"].(map[string]any)
	if result == nil {
		result = map[string]any{}
	}

	if !m.store.CompleteTask(ctx, taskID, result) {
		task, _ := m.store.GetTask(ctx, taskID)
		return nil, shared.AlreadyDone("task", task.Status.String())
	}

	// Sync AINL node and notify (best effort)
	go m.syncAINLAndNotify(taskID, "COMPLETED", result)

	return map[string]any{"status": "completed"}, nil
}

func (m *TaskManager) FailTask(ctx *shared.Context, params map[string]any) (any, error) {
	taskID, _ := params["task_id"].(string)
	if taskID == "" {
		return nil, shared.MissingParam("task_id")
	}

	if err := m.verifyTaskOwnership(ctx, taskID); err != nil {
		return nil, err
	}

	errorMsg, _ := params["error_message"].(string)

	if !m.store.FailTask(ctx, taskID, errorMsg) {
		task, _ := m.store.GetTask(ctx, taskID)
		return nil, shared.AlreadyDone("task", task.Status.String())
	}

	// Sync AINL node and notify (best effort)
	go m.syncAINLAndNotify(taskID, "FAILED", map[string]any{"error_message": errorMsg})

	return map[string]any{"status": "failed"}, nil
}

func (m *TaskManager) CancelTask(ctx *shared.Context, params map[string]any) (any, error) {
	taskID, _ := params["task_id"].(string)
	if taskID == "" {
		return nil, shared.MissingParam("task_id")
	}

	if err := m.verifyTaskOwnership(ctx, taskID); err != nil {
		return nil, err
	}

	if !m.store.CancelTask(ctx, taskID) {
		task, _ := m.store.GetTask(ctx, taskID)
		return nil, shared.AlreadyDone("task", task.Status.String())
	}

	return map[string]any{"status": "cancelled"}, nil
}

func (m *TaskManager) verifyTaskOwnership(ctx *shared.Context, taskID string) error {
	task, err := m.store.GetTask(ctx, taskID)
	if err != nil || task == nil {
		return shared.NotFound("task", taskID)
	}
	if task.TenantID != ctx.TenantID {
		return shared.NotFound("task", taskID)
	}
	return nil
}

func (m *TaskManager) syncAINLAndNotify(taskID, status string, result map[string]any) {
	// Best effort: sync AINL DAG node status + notify ilink submitter
	// Implementation depends on AINL engine integration
	slog.Debug("sync AINL", "task_id", taskID, "status", status)
}