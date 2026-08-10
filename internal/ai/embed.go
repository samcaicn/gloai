package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// embedRequest is the OpenAI-compatible /v1/embeddings request body.
type embedRequest struct {
	Model string   `json:"model"`
	Input []string `json:"input"`
}

type embedData struct {
	Embedding []float32 `json:"embedding"`
	Index     int       `json:"index"`
}

type embedResponse struct {
	Data  []embedData `json:"data"`
	Usage *struct {
		PromptTokens int `json:"prompt_tokens"`
		TotalTokens  int `json:"total_tokens"`
	} `json:"usage,omitempty"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

// Embed calls the OpenAI-compatible /v1/embeddings endpoint for the given texts
// and returns embeddings aligned with the input order. When EmbeddingModel is
// empty it falls back to the chat Model.
func Embed(ctx context.Context, cfg store.AIConfig, texts []string) ([][]float32, error) {
	if len(texts) == 0 {
		return nil, nil
	}
	if cfg.APIKey == "" {
		return nil, fmt.Errorf("ai: no api key configured for embeddings")
	}
	baseURL := cfg.BaseURL
	if baseURL == "" {
		baseURL = defaultBaseURL
	}
	model := cfg.EmbeddingModel
	if model == "" {
		model = cfg.Model
	}

	reqBody, _ := json.Marshal(embedRequest{Model: model, Input: texts})
	endpoint := strings.TrimRight(baseURL, "/") + "/embeddings"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(reqBody))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+cfg.APIKey)
	for k, v := range cfg.CustomHeaders {
		if !isReservedHeader(k) {
			httpReq.Header.Set(k, v)
		}
	}

	start := time.Now()
	resp, err := http.DefaultClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("ai embed request failed: %w", err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	durationMS := time.Since(start).Milliseconds()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("ai embed api returned %d: %s", resp.StatusCode, truncate(string(body), 1000))
	}
	var out embedResponse
	if err := json.Unmarshal(body, &out); err != nil {
		return nil, fmt.Errorf("ai embed response parse failed: %s", truncate(string(body), 1000))
	}
	if out.Error != nil {
		return nil, fmt.Errorf("ai embed error: %s", out.Error.Message)
	}

	// Account token usage for per-tenant billing (embedding).
	if out.Usage != nil {
		recordUsage(ctx, model, "embedding", &Usage{
			PromptTokens: out.Usage.PromptTokens,
			TotalTokens:  out.Usage.TotalTokens,
		}, durationMS)
	}

	byIdx := make(map[int][]float32, len(out.Data))
	for _, d := range out.Data {
		byIdx[d.Index] = d.Embedding
	}
	out2 := make([][]float32, len(texts))
	for i := range texts {
		out2[i] = byIdx[i]
	}
	return out2, nil
}
