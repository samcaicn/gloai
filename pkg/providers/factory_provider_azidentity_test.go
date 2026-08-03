//go:build azidentity

// colearn - Ultra-lightweight personal AI agent
// License: MIT
//
// Copyright (c) 2026 colearn contributors

package providers

import (
	"testing"

	"github.com/colearn/colearn/pkg/config"
)

// With the azidentity build tag, an azure config with no api_key must succeed
// (falls back to DefaultAzureCredential). Construction does not require any
// real Azure environment — token acquisition happens on first Chat.
func TestCreateProviderFromConfig_AzureIdentityFallback(t *testing.T) {
	cfg := &config.ModelConfig{
		ModelName: "azure-gpt5",
		Model:     "azure/my-gpt5-deployment",
		APIBase:   "https://www.tuptup.top",
	}

	provider, modelID, err := CreateProviderFromConfig(cfg)
	if err != nil {
		t.Fatalf("CreateProviderFromConfig() error = %v", err)
	}
	if provider == nil {
		t.Fatal("CreateProviderFromConfig() returned nil provider")
	}
	if modelID != "my-gpt5-deployment" {
		t.Errorf("modelID = %q, want %q", modelID, "my-gpt5-deployment")
	}
}
