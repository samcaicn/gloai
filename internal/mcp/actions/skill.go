package actions

import (
	"log/slog"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/mcp/shared"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// SkillManager handles skill marketplace operations
type SkillManager struct {
	store store.Store
}

func NewSkillManager(s store.Store) *SkillManager {
	return &SkillManager{store: s}
}

func (m *SkillManager) Search(ctx *shared.Context, params map[string]any) (any, error) {
	query, _ := params["query"].(string)
	category, _ := params["category"].(string)
	limit := 20
	if l, ok := params["limit"].(float64); ok {
		limit = int(l)
	}
	offset := 0
	if o, ok := params["offset"].(float64); ok {
		offset = int(o)
	}

	skills, err := m.store.ListSkills(store.SkillQuery{
		Search:   query,
		Category: category,
		Limit:    limit,
		Offset:   offset,
	})
	if err != nil {
		return nil, err
	}

	result := make([]map[string]any, len(skills))
	for i, s := range skills {
		result[i] = map[string]any{
			"id":             s.ID,
			"name":           s.Name,
			"description":    s.Description,
			"category":       s.Category,
			"tags":           s.Tags,
			"author":         s.Author,
			"owner_id":       s.OwnerID,
			"latest_version": s.LatestVersionID,
			"listing":        s.Listing,
			"created_at":     s.CreatedAt,
			"updated_at":     s.UpdatedAt,
		}
	}

	return map[string]any{
		"skills": result,
		"total":  len(result),
	}, nil
}

func (m *SkillManager) Detail(ctx *shared.Context, params map[string]any) (any, error) {
	skillID, _ := params["skill_id"].(string)
	if skillID == "" {
		return nil, shared.MissingParam("skill_id")
	}

	skill, err := m.store.GetSkill(skillID)
	if err != nil {
		return nil, shared.NotFound("skill", skillID)
	}

	versions, _ := m.store.ListSkillVersions(skillID)
	ratings, _ := m.store.ListSkillRatings(skillID, 10)

	return map[string]any{
		"skill":    skill,
		"versions": versions,
		"ratings":  ratings,
	}, nil
}

func (m *SkillManager) Create(ctx *shared.Context, params map[string]any) (any, error) {
	// Generate COS upload ticket for skill bundle
	skillName, _ := params["skill_name"].(string)
	filename, _ := params["filename"].(string)
	if skillName == "" || filename == "" {
		return nil, shared.MissingParam("skill_name/filename")
	}

	version, _ := params["version"].(string)
	if version == "" {
		version = "1.0.0"
	}

	skillType, _ := params["skill_type"].(string)
	description, _ := params["description"].(string)
	requiredCaps, _ := params["required_capabilities"].([]any)
	contentType, _ := params["content_type"].(string)
	if contentType == "" {
		contentType = "application/octet-stream"
	}
	ttl := 600
	if t, ok := params["ttl_seconds"].(float64); ok {
		ttl = int(t)
	}

	// Generate upload ticket
	ticket, err := m.store.CreateSkillUploadTicket(ctx, &store.SkillUploadTicketRequest{
		SkillName:    skillName,
		Filename:     filename,
		Version:      version,
		SkillType:    skillType,
		Description:  description,
		RequiredCaps: requiredCaps,
		ContentType:  contentType,
		TTLSeconds:   ttl,
	})
	if err != nil {
		return nil, err
	}

	return map[string]any{
		"ticket_id":  ticket.TicketID,
		"upload_url": ticket.UploadURL,
		"method":     ticket.Method,
		"headers":    ticket.Headers,
		"key":        ticket.Key,
		"max_size":   ticket.MaxSize,
		"expires_at": ticket.ExpiresAt,
	}, nil
}

func (m *SkillManager) Upload(ctx *shared.Context, params map[string]any) (any, error) {
	// This is handled by direct upload to COS
	// After upload, client calls confirm
	return map[string]any{"status": "upload_directly_to_cos"}, nil
}

func (m *SkillManager) Call(ctx *shared.Context, params map[string]any) (any, error) {
	skillID, _ := params["skill_id"].(string)
	if skillID == "" {
		return nil, shared.MissingParam("skill_id")
	}

	skillParams, _ := params["params"].(map[string]any)
	if skillParams == nil {
		skillParams = map[string]any{}
	}

	// Execute skill via skill runner
	result, err := m.executeSkill(ctx, skillID, skillParams)
	if err != nil {
		return nil, err
	}

	// Report execution
	m.reportExecution(ctx, skillID, skillParams, result, nil)

	return result, nil
}

func (m *SkillManager) executeSkill(ctx *shared.Context, skillID string, params map[string]any) (map[string]any, error) {
	// Implementation would call skill runner
	return map[string]any{
		"result": "skill execution placeholder",
	}, nil
}

func (m *SkillManager) reportExecution(ctx *shared.Context, skillID string, params, result map[string]any, err error) {
	// Report to Hermes for evaluation
	slog.Debug("skill execution reported", "skill", skillID)
}

func (m *SkillManager) InstallConfirm(ctx *shared.Context, params map[string]any) (any, error) {
	skillID, _ := params["skill_id"].(string)
	if skillID == "" {
		return nil, shared.MissingParam("skill_id")
	}

	installPath, _ := params["install_path"].(string)
	installVersion, _ := params["install_version"].(string)
	installSizeBytes, _ := params["install_size_bytes"].(float64)
	isExternal, _ := params["is_external"].(bool)
	externalURL, _ := params["external_download_url"].(string)

	promoted, err := m.store.ConfirmSkillInstall(ctx, ctx.ClientID, &store.SkillInstallConfirm{
		SkillID:             skillID,
		InstallPath:         installPath,
		InstallVersion:      installVersion,
		InstallSizeBytes:    int64(installSizeBytes),
		IsExternal:          isExternal,
		ExternalDownloadURL: externalURL,
	})
	if err != nil {
		return nil, err
	}

	return map[string]any{
		"success": true,
		"promote": promoted,
		"message": "install confirmed",
	}, nil
}

func (m *SkillManager) ReportExecution(ctx *shared.Context, params map[string]any) (any, error) {
	skillID, _ := params["skill_id"].(string)
	skillVersion, _ := params["skill_version"].(string)
	execParams, _ := params["params"].(map[string]any)
	result, _ := params["result"].(map[string]any)
	errorMsg, _ := params["error_message"].(string)
	durationMs, _ := params["duration_ms"].(float64)

	record := &store.SkillExecutionReport{
		SkillID:      skillID,
		SkillVersion: skillVersion,
		ClientID:     ctx.ClientID,
		TenantID:     ctx.TenantID,
		Params:       execParams,
		Result:       result,
		ErrorMessage: errorMsg,
		DurationMs:   int64(durationMs),
		Timestamp:    time.Now().Unix(),
	}

	if err := m.store.ReportSkillExecution(ctx, record); err != nil {
		return nil, err
	}

	return map[string]any{"success": true}, nil
}

func (m *SkillManager) Evaluation(ctx *shared.Context, params map[string]any) (any, error) {
	skillID, _ := params["skill_id"].(string)
	if skillID == "" {
		return nil, shared.MissingParam("skill_id")
	}

	eval, err := m.store.GetSkillEvaluation(ctx, skillID)
	if err != nil {
		return nil, err
	}

	return eval, nil
}
