package api

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

func skillMarkdown(name, version string) string {
	return fmt.Sprintf(`---
name: %s
description: A skill used by the marketplace tests.
version: %s
license: MIT
allowed-tools: Read, Grep
tags: [testing]
---

# %s

Body.
`, name, version, name)
}

func makeBundle(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range files {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatalf("zip create: %v", err)
		}
		if _, err := w.Write([]byte(body)); err != nil {
			t.Fatalf("zip write: %v", err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("zip close: %v", err)
	}
	return buf.Bytes()
}

// submitBundle POSTs a multipart skill submission and returns the response.
func submitBundle(t *testing.T, ts *httptest.Server, cookie *http.Cookie, bundle []byte, fields map[string]string) *http.Response {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	for k, v := range fields {
		if err := mw.WriteField(k, v); err != nil {
			t.Fatalf("WriteField: %v", err)
		}
	}
	fw, err := mw.CreateFormFile("bundle", "skill.zip")
	if err != nil {
		t.Fatalf("CreateFormFile: %v", err)
	}
	if _, err := fw.Write(bundle); err != nil {
		t.Fatalf("write bundle: %v", err)
	}
	mw.Close()

	req, err := http.NewRequest(http.MethodPost, ts.URL+"/api/skills/submit", &body)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.AddCookie(cookie)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Do: %v", err)
	}
	return resp
}

// createUserSession makes an extra user plus a session cookie.
func createUserSession(t *testing.T, s store.Store, username, role string) *http.Cookie {
	t.Helper()
	u, err := s.CreateUserFull(username, "", username, "hashed", role)
	if err != nil {
		t.Fatalf("CreateUserFull: %v", err)
	}
	if err := s.UpdateUserStatus(u.ID, store.StatusActive); err != nil {
		t.Fatalf("UpdateUserStatus: %v", err)
	}
	token, err := auth.CreateSession(s, u.ID)
	if err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	return &http.Cookie{Name: "session", Value: token}
}

// submitAndApprove runs a full submit → approve cycle and returns the skill ID.
func submitAndApprove(t *testing.T, env *testEnv, name, version string) (skillID, versionID string) {
	t.Helper()
	bundle := makeBundle(t, map[string]string{
		"SKILL.md":       skillMarkdown(name, version),
		"scripts/run.sh": "echo run",
	})
	resp := submitBundle(t, env.ts, env.cookie, bundle, map[string]string{"category": "engineering"})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("submit status = %d, want 201 (%s)", resp.StatusCode, readBody(t, resp))
	}
	out := decodeJSON(t, resp)
	skillID, _ = out["skill_id"].(string)
	versionID, _ = out["version_id"].(string)

	resp = doJSON(t, env.ts, http.MethodPut,
		"/api/admin/skills/versions/"+versionID+"/review",
		map[string]any{"status": "approved"}, withCookie(env.cookie))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("approve status = %d, want 200 (%s)", resp.StatusCode, readBody(t, resp))
	}
	resp.Body.Close()
	return skillID, versionID
}

func readBody(t *testing.T, resp *http.Response) string {
	t.Helper()
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	return string(b)
}

// ---------------------------------------------------------------------------
// tests
// ---------------------------------------------------------------------------

func TestSkillSubmitEntersReviewQueue(t *testing.T) {
	env := setupTestEnv(t)

	bundle := makeBundle(t, map[string]string{
		"SKILL.md":            skillMarkdown("code-review", "1.0.0"),
		"references/rules.md": "rules",
	})
	resp := submitBundle(t, env.ts, env.cookie, bundle, map[string]string{
		"category":  "engineering",
		"changelog": "first cut",
	})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d, want 201 (%s)", resp.StatusCode, readBody(t, resp))
	}
	out := decodeJSON(t, resp)
	if out["status"] != "pending" {
		t.Errorf("status = %v, want pending", out["status"])
	}
	if out["slug"] != "code-review" {
		t.Errorf("slug = %v, want code-review", out["slug"])
	}

	// Not visible in the public marketplace until approved.
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills", nil, withCookie(env.cookie))
	var listed []map[string]any
	json.NewDecoder(resp.Body).Decode(&listed)
	resp.Body.Close()
	if len(listed) != 0 {
		t.Errorf("marketplace = %v, want empty before approval", listed)
	}

	// But it is in the admin review queue.
	resp = doJSON(t, env.ts, http.MethodGet, "/api/admin/skills/pending", nil, withCookie(env.cookie))
	var pending []map[string]any
	json.NewDecoder(resp.Body).Decode(&pending)
	resp.Body.Close()
	if len(pending) != 1 {
		t.Fatalf("pending = %d, want 1", len(pending))
	}
	if pending[0]["skill_slug"] != "code-review" {
		t.Errorf("pending entry = %v", pending[0])
	}
	if pending[0]["bundle_sha256"] == "" {
		t.Error("expected bundle checksum on the pending version")
	}
}

func TestSkillSubmitRejectsInvalidBundle(t *testing.T) {
	env := setupTestEnv(t)

	tests := []struct {
		name   string
		files  map[string]string
		status int
	}{
		{"no SKILL.md", map[string]string{"readme.md": "hi"}, http.StatusBadRequest},
		{"no frontmatter name", map[string]string{"SKILL.md": "---\ndescription: x\n---\nbody"}, http.StatusBadRequest},
		{"path traversal", map[string]string{
			"SKILL.md":  skillMarkdown("evil", "1.0.0"),
			"../out.sh": "rm -rf /",
		}, http.StatusBadRequest},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			resp := submitBundle(t, env.ts, env.cookie, makeBundle(t, tc.files), nil)
			if resp.StatusCode != tc.status {
				t.Errorf("status = %d, want %d (%s)", resp.StatusCode, tc.status, readBody(t, resp))
			}
			resp.Body.Close()
		})
	}
}

func TestSkillApproveListsAndDownloads(t *testing.T) {
	env := setupTestEnv(t)
	skillID, versionID := submitAndApprove(t, env, "code-review", "1.0.0")

	resp := doJSON(t, env.ts, http.MethodGet, "/api/skills", nil, withCookie(env.cookie))
	var listed []map[string]any
	json.NewDecoder(resp.Body).Decode(&listed)
	resp.Body.Close()
	if len(listed) != 1 || listed[0]["slug"] != "code-review" {
		t.Fatalf("marketplace = %v, want the approved skill", listed)
	}
	if listed[0]["listing"] != "listed" {
		t.Errorf("listing = %v, want listed", listed[0]["listing"])
	}

	// Detail view exposes the approved version.
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills/"+skillID, nil, withCookie(env.cookie))
	detail := decodeJSON(t, resp)
	latest, _ := detail["latest_version"].(map[string]any)
	if latest == nil || latest["id"] != versionID {
		t.Fatalf("latest_version = %v, want %s", detail["latest_version"], versionID)
	}

	// The bundle downloads as a readable zip rooted at SKILL.md.
	resp = doJSON(t, env.ts, http.MethodGet,
		fmt.Sprintf("/api/skills/%s/versions/%s/download", skillID, versionID), nil, withCookie(env.cookie))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("download status = %d (%s)", resp.StatusCode, readBody(t, resp))
	}
	data, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("downloaded bundle unreadable: %v", err)
	}
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
	}
	if len(names) != 2 {
		t.Errorf("bundle files = %v, want SKILL.md + scripts/run.sh", names)
	}

	// Public registry serves the same skill without a session.
	resp, err = http.Get(env.ts.URL + "/api/registry/v1/skills.json")
	if err != nil {
		t.Fatalf("registry get: %v", err)
	}
	reg := decodeJSON(t, resp)
	if reg["count"].(float64) != 1 {
		t.Errorf("registry count = %v, want 1", reg["count"])
	}

	resp, err = http.Get(env.ts.URL + "/api/registry/v1/skills/code-review/download")
	if err != nil {
		t.Fatalf("registry download: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Errorf("registry download status = %d", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestSkillRejectRequiresReason(t *testing.T) {
	env := setupTestEnv(t)

	bundle := makeBundle(t, map[string]string{"SKILL.md": skillMarkdown("risky", "1.0.0")})
	resp := submitBundle(t, env.ts, env.cookie, bundle, nil)
	out := decodeJSON(t, resp)
	versionID := out["version_id"].(string)
	skillID := out["skill_id"].(string)

	resp = doJSON(t, env.ts, http.MethodPut, "/api/admin/skills/versions/"+versionID+"/review",
		map[string]any{"status": "rejected"}, withCookie(env.cookie))
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("reject without reason status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()

	resp = doJSON(t, env.ts, http.MethodPut, "/api/admin/skills/versions/"+versionID+"/review",
		map[string]any{"status": "rejected", "reason": "运行了未声明的网络请求"}, withCookie(env.cookie))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("reject status = %d (%s)", resp.StatusCode, readBody(t, resp))
	}
	resp.Body.Close()

	// Reviewing an already-reviewed version is refused.
	resp = doJSON(t, env.ts, http.MethodPut, "/api/admin/skills/versions/"+versionID+"/review",
		map[string]any{"status": "approved"}, withCookie(env.cookie))
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("double review status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()

	// The rejection reason reaches the owner through the detail view.
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills/"+skillID, nil, withCookie(env.cookie))
	detail := decodeJSON(t, resp)
	sk := detail["skill"].(map[string]any)
	if sk["listing"] != "rejected" {
		t.Errorf("listing = %v, want rejected", sk["listing"])
	}
	if sk["reject_reason"] != "运行了未声明的网络请求" {
		t.Errorf("reject_reason = %v", sk["reject_reason"])
	}
	logs, _ := detail["review_logs"].([]any)
	if len(logs) < 2 {
		t.Errorf("review_logs = %v, want submit + reject entries", logs)
	}
}

func TestSkillVersionConflictAndSupersede(t *testing.T) {
	env := setupTestEnv(t)

	bundle := makeBundle(t, map[string]string{"SKILL.md": skillMarkdown("writer", "1.0.0")})
	resp := submitBundle(t, env.ts, env.cookie, bundle, nil)
	first := decodeJSON(t, resp)

	// Same version string is refused.
	resp = submitBundle(t, env.ts, env.cookie, bundle, nil)
	if resp.StatusCode != http.StatusConflict {
		t.Errorf("duplicate version status = %d, want 409 (%s)", resp.StatusCode, readBody(t, resp))
	}
	resp.Body.Close()

	// A newer version supersedes the pending one.
	bundle2 := makeBundle(t, map[string]string{"SKILL.md": skillMarkdown("writer", "1.1.0")})
	resp = submitBundle(t, env.ts, env.cookie, bundle2, nil)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("second submit status = %d (%s)", resp.StatusCode, readBody(t, resp))
	}
	resp.Body.Close()

	resp = doJSON(t, env.ts, http.MethodGet, "/api/admin/skills/pending", nil, withCookie(env.cookie))
	var pending []map[string]any
	json.NewDecoder(resp.Body).Decode(&pending)
	resp.Body.Close()
	if len(pending) != 1 || pending[0]["version"] != "1.1.0" {
		t.Errorf("pending = %v, want only 1.1.0", pending)
	}

	// The superseded version is no longer cancellable.
	resp = doJSON(t, env.ts, http.MethodPost,
		fmt.Sprintf("/api/skills/%s/versions/%s/cancel", first["skill_id"], first["version_id"]),
		nil, withCookie(env.cookie))
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("cancel superseded status = %d, want 400", resp.StatusCode)
	}
	resp.Body.Close()
}

func TestSkillSlugOwnedByAnotherUser(t *testing.T) {
	env := setupTestEnv(t)
	submitAndApprove(t, env, "code-review", "1.0.0")

	otherCookie := createUserSession(t, env.store, "intruder", store.RoleMember)
	bundle := makeBundle(t, map[string]string{"SKILL.md": skillMarkdown("code-review", "2.0.0")})
	resp := submitBundle(t, env.ts, otherCookie, bundle, nil)
	if resp.StatusCode != http.StatusConflict {
		t.Errorf("status = %d, want 409 (%s)", resp.StatusCode, readBody(t, resp))
	}
	resp.Body.Close()
}

func TestSkillRatingAggregates(t *testing.T) {
	env := setupTestEnv(t)
	skillID, _ := submitAndApprove(t, env, "code-review", "1.0.0")

	resp := doJSON(t, env.ts, http.MethodPut, "/api/skills/"+skillID+"/rating",
		map[string]any{"rating": 5, "comment": "很好用"}, withCookie(env.cookie))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("rate status = %d (%s)", resp.StatusCode, readBody(t, resp))
	}
	resp.Body.Close()

	other := createUserSession(t, env.store, "rater2", store.RoleMember)
	resp = doJSON(t, env.ts, http.MethodPut, "/api/skills/"+skillID+"/rating",
		map[string]any{"rating": 3}, withCookie(other))
	out := decodeJSON(t, resp)
	if out["rating_count"].(float64) != 2 || out["rating_avg"].(float64) != 4 {
		t.Errorf("aggregate = %v, want count 2 avg 4", out)
	}

	// Out-of-range ratings are refused.
	for _, bad := range []int{0, 6, -1} {
		resp = doJSON(t, env.ts, http.MethodPut, "/api/skills/"+skillID+"/rating",
			map[string]any{"rating": bad}, withCookie(other))
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("rating %d status = %d, want 400", bad, resp.StatusCode)
		}
		resp.Body.Close()
	}

	// Re-rating replaces the earlier score.
	resp = doJSON(t, env.ts, http.MethodPut, "/api/skills/"+skillID+"/rating",
		map[string]any{"rating": 1}, withCookie(other))
	out = decodeJSON(t, resp)
	if out["rating_count"].(float64) != 2 || out["rating_avg"].(float64) != 3 {
		t.Errorf("after update = %v, want count 2 avg 3", out)
	}

	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills/"+skillID+"/ratings", nil, withCookie(env.cookie))
	var ratings []map[string]any
	json.NewDecoder(resp.Body).Decode(&ratings)
	resp.Body.Close()
	if len(ratings) != 2 {
		t.Errorf("ratings = %d, want 2", len(ratings))
	}

	resp = doJSON(t, env.ts, http.MethodDelete, "/api/skills/"+skillID+"/rating", nil, withCookie(other))
	resp.Body.Close()
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills/"+skillID, nil, withCookie(env.cookie))
	detail := decodeJSON(t, resp)
	sk := detail["skill"].(map[string]any)
	if sk["rating_count"].(float64) != 1 || sk["rating_avg"].(float64) != 5 {
		t.Errorf("after delete = %v", sk)
	}
}

func TestSkillInstallFlow(t *testing.T) {
	env := setupTestEnv(t)
	skillID, versionID := submitAndApprove(t, env, "code-review", "1.0.0")

	resp := doJSON(t, env.ts, http.MethodPost, "/api/skills/"+skillID+"/install",
		map[string]any{"agent_id": "zhongshu"}, withCookie(env.cookie))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("install status = %d (%s)", resp.StatusCode, readBody(t, resp))
	}
	out := decodeJSON(t, resp)
	if out["version_id"] != versionID {
		t.Errorf("version_id = %v, want %s", out["version_id"], versionID)
	}
	if out["download_url"] == "" {
		t.Error("expected a download_url")
	}

	resp = doJSON(t, env.ts, http.MethodGet, "/api/me/skill-installs", nil, withCookie(env.cookie))
	var installs []map[string]any
	json.NewDecoder(resp.Body).Decode(&installs)
	resp.Body.Close()
	if len(installs) != 1 || installs[0]["agent_id"] != "zhongshu" {
		t.Fatalf("installs = %v", installs)
	}

	// Installed skills are flagged in the marketplace listing.
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills", nil, withCookie(env.cookie))
	var listed []map[string]any
	json.NewDecoder(resp.Body).Decode(&listed)
	resp.Body.Close()
	if len(listed) != 1 || listed[0]["installed"] != true {
		t.Errorf("listing = %v, want installed=true", listed)
	}

	resp = doJSON(t, env.ts, http.MethodDelete,
		"/api/skills/"+skillID+"/install?agent_id=zhongshu", nil, withCookie(env.cookie))
	resp.Body.Close()
	resp = doJSON(t, env.ts, http.MethodGet, "/api/me/skill-installs", nil, withCookie(env.cookie))
	json.NewDecoder(resp.Body).Decode(&installs)
	resp.Body.Close()
	if len(installs) != 0 {
		t.Errorf("installs after uninstall = %v", installs)
	}
}

func TestSkillAdminEndpointsRequireAdmin(t *testing.T) {
	env := setupTestEnv(t)
	skillID, versionID := submitAndApprove(t, env, "code-review", "1.0.0")
	member := createUserSession(t, env.store, "plain_member", store.RoleMember)

	cases := []struct {
		method, path string
		body         any
	}{
		{http.MethodGet, "/api/admin/skills", nil},
		{http.MethodGet, "/api/admin/skills/pending", nil},
		{http.MethodPut, "/api/admin/skills/versions/" + versionID + "/review", map[string]any{"status": "approved"}},
		{http.MethodPut, "/api/admin/skills/" + skillID + "/listing", map[string]any{"listing": "unlisted"}},
		{http.MethodDelete, "/api/admin/skills/" + skillID, nil},
	}
	for _, c := range cases {
		resp := doJSON(t, env.ts, c.method, c.path, c.body, withCookie(member))
		if resp.StatusCode != http.StatusForbidden {
			t.Errorf("%s %s status = %d, want 403", c.method, c.path, resp.StatusCode)
		}
		resp.Body.Close()
	}
}

func TestSkillUnlistHidesFromMarketplace(t *testing.T) {
	env := setupTestEnv(t)
	skillID, _ := submitAndApprove(t, env, "code-review", "1.0.0")

	resp := doJSON(t, env.ts, http.MethodPut, "/api/admin/skills/"+skillID+"/listing",
		map[string]any{"listing": "unlisted", "reason": "重复内容"}, withCookie(env.cookie))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("unlist status = %d (%s)", resp.StatusCode, readBody(t, resp))
	}
	resp.Body.Close()

	member := createUserSession(t, env.store, "browser", store.RoleMember)
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills", nil, withCookie(member))
	var listed []map[string]any
	json.NewDecoder(resp.Body).Decode(&listed)
	resp.Body.Close()
	if len(listed) != 0 {
		t.Errorf("marketplace = %v, want empty after unlist", listed)
	}

	// A non-owner cannot even open the detail page of an unlisted skill.
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills/"+skillID, nil, withCookie(member))
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("detail status = %d, want 404", resp.StatusCode)
	}
	resp.Body.Close()

	// Re-listing brings it back.
	resp = doJSON(t, env.ts, http.MethodPut, "/api/admin/skills/"+skillID+"/listing",
		map[string]any{"listing": "listed"}, withCookie(env.cookie))
	resp.Body.Close()
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills", nil, withCookie(member))
	json.NewDecoder(resp.Body).Decode(&listed)
	resp.Body.Close()
	if len(listed) != 1 {
		t.Errorf("marketplace = %v, want the relisted skill", listed)
	}
}

func TestSkillMineListingShowsOwnDrafts(t *testing.T) {
	env := setupTestEnv(t)
	bundle := makeBundle(t, map[string]string{"SKILL.md": skillMarkdown("private-skill", "1.0.0")})
	resp := submitBundle(t, env.ts, env.cookie, bundle, nil)
	resp.Body.Close()

	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills?mine=1", nil, withCookie(env.cookie))
	var mine []map[string]any
	json.NewDecoder(resp.Body).Decode(&mine)
	resp.Body.Close()
	if len(mine) != 1 || mine[0]["listing"] != "pending" {
		t.Errorf("mine = %v, want one pending skill", mine)
	}

	other := createUserSession(t, env.store, "stranger", store.RoleMember)
	resp = doJSON(t, env.ts, http.MethodGet, "/api/skills?mine=1", nil, withCookie(other))
	json.NewDecoder(resp.Body).Decode(&mine)
	resp.Body.Close()
	if len(mine) != 0 {
		t.Errorf("stranger's mine = %v, want empty", mine)
	}
}
