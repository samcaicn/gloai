package api

import (
	"context"
	"net/http"
)

func contextWithParams(r *http.Request, params map[string]string) context.Context {
	return context.WithValue(r.Context(), paramsKey, params)
}
