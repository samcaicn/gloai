package api

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/ceoadmin/CEOadmin/internal/api/shared"
	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/supplymarket"
)

// supplyMarketCategories exposes the known category names for the publish form.
func (s *Server) handleSupplyMarketCategories(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(supplymarket.Categories())
}

// GET /api/supply-market/items — the calling user's own items.
func (s *Server) handleSupplyMarketMyItems(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	state := r.URL.Query().Get("state")
	itemType := r.URL.Query().Get("item_type")
	items, err := supplymarket.Default.MyItems(uid, state, itemType)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, items)
}

// POST /api/supply-market/items — publish a supply / procurement item.
func (s *Server) handleSupplyMarketPublish(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	var req struct {
		ItemType    string  `json:"item_type"`
		Title       string  `json:"title"`
		Description string  `json:"description"`
		Category    string  `json:"category"`
		Price       float64 `json:"price"`
		Currency    string  `json:"currency"`
		Location    string  `json:"location"`
		Contact     string  `json:"contact"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	res, err := supplymarket.Default.Publish(req.ItemType, uid, req.Title, req.Description, req.Category, req.Price, req.Currency, req.Location, req.Contact)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(res)
}

// GET /api/supply-market/items/{id} — a single item (cross-tenant readable).
func (s *Server) handleSupplyMarketGet(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	it, err := supplymarket.Default.Get(id, uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(it)
}

// POST /api/supply-market/items/{id}/clarify — the owner answers a round of
// clarifying questions.
func (s *Server) handleSupplyMarketClarify(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	var req struct {
		Answers []supplymarket.ClarificationAnswer `json:"answers"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	res, err := supplymarket.Default.Clarify(id, uid, req.Answers)
	if err != nil {
		status := http.StatusBadRequest
		if _, ok := err.(*supplymarket.ErrForbidden); ok {
			status = http.StatusForbidden
		}
		shared.JSONError(w, err.Error(), status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(res)
}

// POST /api/supply-market/items/{id}/close — the owner closes (下架) an item.
func (s *Server) handleSupplyMarketClose(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	if err := supplymarket.Default.Close(id, uid); err != nil {
		status := http.StatusBadRequest
		if _, ok := err.(*supplymarket.ErrForbidden); ok {
			status = http.StatusForbidden
		}
		shared.JSONError(w, err.Error(), status)
		return
	}
	shared.JSONOK(w)
}

// DELETE /api/supply-market/items/{id} — the owner deletes an item.
func (s *Server) handleSupplyMarketDelete(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	if err := supplymarket.Default.Delete(id, uid); err != nil {
		status := http.StatusBadRequest
		if _, ok := err.(*supplymarket.ErrForbidden); ok {
			status = http.StatusForbidden
		}
		shared.JSONError(w, err.Error(), status)
		return
	}
	shared.JSONOK(w)
}

// GET /api/supply-market/marketplace — the public VERIFIED listing with filters.
func (s *Server) handleSupplyMarketList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	itemType := q.Get("item_type")
	category := q.Get("category")
	location := q.Get("location")
	var priceMin, priceMax *float64
	if v := q.Get("price_min"); v != "" {
		if f, err := parseFloat(v); err == nil {
			priceMin = &f
		}
	}
	if v := q.Get("price_max"); v != "" {
		if f, err := parseFloat(v); err == nil {
			priceMax = &f
		}
	}
	limit := 100
	if v := q.Get("limit"); v != "" {
		if n, err := parseLimit(v); err == nil {
			limit = n
		}
	}
	items, err := supplymarket.Default.MarketplaceList(itemType, category, location, priceMin, priceMax, limit)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, items)
}

// GET /api/supply-market/match?item_id=... — matching recommendations.
func (s *Server) handleSupplyMarketMatch(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	itemID := r.URL.Query().Get("item_id")
	if itemID == "" {
		shared.JSONError(w, "item_id required", http.StatusBadRequest)
		return
	}
	limit := 10
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := parseLimit(v); err == nil {
			limit = n
		}
	}
	res, err := supplymarket.Default.Match(itemID, uid, limit)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(res)
}

// POST /api/supply-market/chats — the inquirer starts (or reuses) a session.
func (s *Server) handleSupplyMarketChatStart(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	var req struct {
		ItemID string `json:"item_id"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.ItemID == "" {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	sess, err := supplymarket.Default.StartChat(req.ItemID, uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(sess)
}

// GET /api/supply-market/chats/mine — the calling user's chat sessions.
func (s *Server) handleSupplyMarketChatsMine(w http.ResponseWriter, r *http.Request) {
	uid := auth.UserIDFromContext(r.Context())
	sessions, err := supplymarket.Default.MyChats(uid)
	if err != nil {
		shared.JSONError(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, sessions)
}

// GET /api/supply-market/chats/{id} — one session's history (participants only).
func (s *Server) handleSupplyMarketChatGet(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	sess, err := supplymarket.Default.GetChat(id, uid)
	if err != nil {
		status := http.StatusNotFound
		if _, ok := err.(*supplymarket.ErrForbidden); ok {
			status = http.StatusForbidden
		}
		shared.JSONError(w, err.Error(), status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(sess)
}

// POST /api/supply-market/chats/{id}/messages — send a message.
func (s *Server) handleSupplyMarketChatSend(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	uid := auth.UserIDFromContext(r.Context())
	var req struct {
		Text string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	sess, err := supplymarket.Default.SendChatMessage(id, uid, req.Text)
	if err != nil {
		status := http.StatusBadRequest
		if _, ok := err.(*supplymarket.ErrForbidden); ok {
			status = http.StatusForbidden
		}
		shared.JSONError(w, err.Error(), status)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(sess)
}

// writeJSON is a small helper to keep the supply-market handlers terse.
func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func parseFloat(s string) (float64, error) {
	return strconv.ParseFloat(s, 64)
}

func parseLimit(s string) (int, error) {
	n, err := strconv.Atoi(s)
	if err != nil || n <= 0 {
		return 0, err
	}
	if n > 500 {
		n = 500
	}
	return n, nil
}
