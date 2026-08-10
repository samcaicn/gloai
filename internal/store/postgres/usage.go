package postgres

import (
	"database/sql"
	"fmt"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// RecordLLMUsage appends a single LLM token-usage row.
func (db *DB) RecordLLMUsage(r *store.LLMUsageRecord) error {
	if r.CreatedAt == 0 {
		r.CreatedAt = db.now().Unix()
	}
	_, err := db.Exec(`
		INSERT INTO llm_usage
			(tenant_id, channel_id, model, model_type,
			 prompt_tokens, completion_tokens, total_tokens,
			 cached_tokens, reasoning_tokens, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		r.TenantID, r.ChannelID, r.Model, r.ModelType,
		r.PromptTokens, r.CompletionTokens, r.TotalTokens,
		r.CachedTokens, r.ReasoningTokens, r.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("insert llm_usage: %w", err)
	}
	return nil
}

// ListLLMUsageAgg returns usage summed by (tenant, model, type).
func (db *DB) ListLLMUsageAgg(filter store.UsageFilter) ([]store.UsageAggregate, error) {
	where := ""
	args := []any{}
	if filter.TenantID != "" {
		where += " AND u.tenant_id = $1"
		args = append(args, filter.TenantID)
	}
	if filter.Model != "" {
		where += fmt.Sprintf(" AND u.model = $%d", len(args)+1)
		args = append(args, filter.Model)
	}
	if filter.ModelType != "" {
		where += fmt.Sprintf(" AND u.model_type = $%d", len(args)+1)
		args = append(args, filter.ModelType)
	}
	if filter.From > 0 {
		where += fmt.Sprintf(" AND u.created_at >= $%d", len(args)+1)
		args = append(args, filter.From)
	}
	if filter.To > 0 {
		where += fmt.Sprintf(" AND u.created_at <= $%d", len(args)+1)
		args = append(args, filter.To)
	}

	limit := filter.Limit
	if limit <= 0 || limit > 500 {
		limit = 200
	}

	query := `
		SELECT
			u.tenant_id,
			COALESCE(b.display_name, b.name, u.tenant_id) AS tenant_name,
			u.model,
			u.model_type,
			SUM(u.prompt_tokens)     AS prompt_tokens,
			SUM(u.completion_tokens) AS completion_tokens,
			SUM(u.total_tokens)      AS total_tokens,
			SUM(u.cached_tokens)     AS cached_tokens,
			SUM(u.reasoning_tokens)  AS reasoning_tokens,
			COUNT(*)                 AS call_count,
			MAX(u.created_at)        AS last_at
		FROM llm_usage u
		LEFT JOIN bots b ON b.id = u.tenant_id
		WHERE 1=1` + where + `
		GROUP BY u.tenant_id, u.model, u.model_type
		ORDER BY total_tokens DESC, last_at DESC
		LIMIT ` + fmt.Sprintf("%d", limit)

	rows, err := db.Query(query, args...)
	if err != nil {
		return nil, fmt.Errorf("query llm_usage agg: %w", err)
	}
	defer rows.Close()

	var out []store.UsageAggregate
	for rows.Next() {
		var a store.UsageAggregate
		var tenantName sql.NullString
		if err := rows.Scan(
			&a.TenantID, &tenantName, &a.Model, &a.ModelType,
			&a.PromptTokens, &a.CompletionTokens, &a.TotalTokens,
			&a.CachedTokens, &a.ReasoningTokens, &a.CallCount, &a.LastAt,
		); err != nil {
			return nil, fmt.Errorf("scan llm_usage agg: %w", err)
		}
		a.TenantName = tenantName.String
		out = append(out, a)
	}
	return out, rows.Err()
}

// RecordMediaUsage appends a single media-generation usage row.
func (db *DB) RecordMediaUsage(r *store.MediaUsageRecord) error {
	if r.CreatedAt == 0 {
		r.CreatedAt = db.now().Unix()
	}
	_, err := db.Exec(`
		INSERT INTO media_usage
			(tenant_id, channel_id, model, media_type, count, duration_seconds, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)`,
		r.TenantID, r.ChannelID, r.Model, string(r.MediaType),
		r.Count, r.DurationSeconds, r.CreatedAt,
	)
	if err != nil {
		return fmt.Errorf("insert media_usage: %w", err)
	}
	return nil
}

// ListMediaUsageAgg returns media usage summed by (tenant, model, media type).
func (db *DB) ListMediaUsageAgg(filter store.MediaUsageFilter) ([]store.MediaUsageAggregate, error) {
	where := ""
	args := []any{}
	if filter.TenantID != "" {
		where += " AND u.tenant_id = $1"
		args = append(args, filter.TenantID)
	}
	if filter.Model != "" {
		where += fmt.Sprintf(" AND u.model = $%d", len(args)+1)
		args = append(args, filter.Model)
	}
	if filter.MediaType != "" {
		where += fmt.Sprintf(" AND u.media_type = $%d", len(args)+1)
		args = append(args, string(filter.MediaType))
	}
	if filter.From > 0 {
		where += fmt.Sprintf(" AND u.created_at >= $%d", len(args)+1)
		args = append(args, filter.From)
	}
	if filter.To > 0 {
		where += fmt.Sprintf(" AND u.created_at <= $%d", len(args)+1)
		args = append(args, filter.To)
	}

	limit := filter.Limit
	if limit <= 0 || limit > 500 {
		limit = 200
	}

	query := `
		SELECT
			u.tenant_id,
			COALESCE(b.display_name, b.name, u.tenant_id) AS tenant_name,
			u.model,
			u.media_type,
			SUM(u.count)            AS total_count,
			SUM(u.duration_seconds) AS total_duration,
			COUNT(*)                AS call_count,
			MAX(u.created_at)       AS last_at
		FROM media_usage u
		LEFT JOIN bots b ON b.id = u.tenant_id
		WHERE 1=1` + where + `
		GROUP BY u.tenant_id, u.model, u.media_type
		ORDER BY total_duration DESC, total_count DESC, last_at DESC
		LIMIT ` + fmt.Sprintf("%d", limit)

	rows, err := db.Query(query, args...)
	if err != nil {
		return nil, fmt.Errorf("query media_usage agg: %w", err)
	}
	defer rows.Close()

	var out []store.MediaUsageAggregate
	for rows.Next() {
		var a store.MediaUsageAggregate
		var tenantName sql.NullString
		var mediaType string
		if err := rows.Scan(
			&a.TenantID, &tenantName, &a.Model, &mediaType,
			&a.Count, &a.DurationSeconds, &a.CallCount, &a.LastAt,
		); err != nil {
			return nil, fmt.Errorf("scan media_usage agg: %w", err)
		}
		a.TenantName = tenantName.String
		a.MediaType = store.MediaType(mediaType)
		out = append(out, a)
	}
	return out, rows.Err()
}
