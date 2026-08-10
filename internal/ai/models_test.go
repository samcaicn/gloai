package ai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestListModels(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/models" {
			t.Errorf("unexpected path %q", r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer test-key" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_ = json.NewEncoder(w).Encode(modelsListResponse{
			Object: "list",
			Data: []ModelInfo{
				{ID: "gpt-4o", OwnedBy: "openai"},
				{ID: "gpt-4o-mini", OwnedBy: "openai"},
			},
		})
	}))
	defer srv.Close()

	models, err := ListModels(context.Background(), srv.URL, "test-key", nil)
	if err != nil {
		t.Fatalf("ListModels: %v", err)
	}
	if len(models) != 2 || models[0].ID != "gpt-4o" || models[1].ID != "gpt-4o-mini" {
		t.Fatalf("unexpected models: %+v", models)
	}
}

func TestListModelsErrorStatus(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	if _, err := ListModels(context.Background(), srv.URL, "k", nil); err == nil {
		t.Fatal("expected error on non-200 status")
	}
}
