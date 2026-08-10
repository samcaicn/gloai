package ai

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// ModelInfo describes a single model returned by the OpenAI-compatible
// /models endpoint.
type ModelInfo struct {
	ID      string `json:"id"`
	Object  string `json:"object,omitempty"`
	OwnedBy string `json:"owned_by,omitempty"`
}

type modelsListResponse struct {
	Object string      `json:"object"`
	Data   []ModelInfo `json:"data"`
}

// ListModels queries the OpenAI-compatible /models endpoint for the given base
// URL and API key, returning the available models. When baseURL is empty the
// default OpenAI endpoint is used.
func ListModels(ctx context.Context, baseURL, apiKey string, customHeaders map[string]string) ([]ModelInfo, error) {
	if baseURL == "" {
		baseURL = defaultBaseURL
	}
	endpoint := strings.TrimRight(baseURL, "/") + "/models"

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+apiKey)
	for k, v := range customHeaders {
		if !isReservedHeader(k) {
			req.Header.Set(k, v)
		}
	}

	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("models request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("models endpoint returned HTTP %d", resp.StatusCode)
	}

	var out modelsListResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("decode models response: %w", err)
	}
	return out.Data, nil
}
