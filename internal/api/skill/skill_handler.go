package skillapi

import "github.com/ceoadmin/CEOadmin/internal/api/shared"

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"github.com/google/uuid"

	"github.com/ceoadmin/CEOadmin/internal/auth"
	"github.com/ceoadmin/CEOadmin/internal/skill"
	"github.com/ceoadmin/CEOadmin/internal/storage"
	"github.com/ceoadmin/CEOadmin/internal/store"
)

// maxSubmitBody bounds a multipart submission (bundle + form fields).
const maxSubmitBody = skill.MaxBundleSize + (1 << 20)

// skillStorage returns the object store used for skill bundles.
func (s *SkillHandler) skillStorage() storage.Store {
	if s.SkillStorage != nil {
		return s.SkillStorage
	}
	return s.ObjectStore
}

// currentUser loads the authenticated user for role checks.
func (s *SkillHandler) currentUser(r *http.Request) *store.User {
	u, err := s.Store.GetUserByID(auth.UserIDFromContext(r.Context()))
	if err != nil {
		return nil
	}
	return u
}

// canManageSkill reports whether the caller owns the skill or is an admin.
func (s *SkillHandler) canManageSkill(r *http.Request, sk *store.Skill) bool {
	userID := auth.UserIDFromContext(r.Context())
	if sk.OwnerID != "" && sk.OwnerID == userID {
		return true
	}
	u := s.currentUser(r)
	return u != nil && store.IsAdmin(u.Role)
}

// ---------------------------------------------------------------------------
// Browsing
// ---------------------------------------------------------------------------

// GET /api/skills — marketplace listing.
//
// Query: q, category, sort (rating|installs|newest|updated), limit, offset,
// mine=1 (own submissions, any state), listing=<state> (admins only).
func (s *SkillHandler) HandleListSkills(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())
	q := store.SkillQuery{
		Listing:  store.SkillListingListed,
		Category: r.URL.Query().Get("category"),
		Search:   strings.TrimSpace(r.URL.Query().Get("q")),
		Sort:     r.URL.Query().Get("sort"),
		Limit:    atoiDefault(r.URL.Query().Get("limit"), 100),
		Offset:   atoiDefault(r.URL.Query().Get("offset"), 0),
	}

	if r.URL.Query().Get("mine") == "1" {
		q.OwnerID = userID
		q.Listing = ""
	}
	if l := r.URL.Query().Get("listing"); l != "" {
		u := s.currentUser(r)
		if u == nil || !store.IsAdmin(u.Role) {
			shared.JSONError(w, "admin required", http.StatusForbidden)
			return
		}
		q.Listing = l
		if l == "all" {
			q.Listing = ""
		}
	}

	skills, err := s.Store.ListSkills(q)
	if err != nil {
		slog.Error("list skills failed", "err", err)
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}

	installed := s.installedSkillIDs(userID)
	type entry struct {
		store.Skill
		Installed bool `json:"installed"`
	}
	out := make([]entry, 0, len(skills))
	for _, sk := range skills {
		out = append(out, entry{Skill: sk, Installed: installed[sk.ID]})
	}
	writeJSON(w, out)
}

func (s *SkillHandler) installedSkillIDs(userID string) map[string]bool {
	out := map[string]bool{}
	installs, err := s.Store.ListSkillInstalls(userID)
	if err != nil {
		return out
	}
	for _, in := range installs {
		out[in.SkillID] = true
	}
	return out
}

// GET /api/skills/{id} — detail view (accepts an ID or a slug).
func (s *SkillHandler) HandleGetSkill(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	canManage := s.canManageSkill(r, sk)
	if sk.Listing != store.SkillListingListed && !canManage {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}

	userID := auth.UserIDFromContext(r.Context())

	var latest *store.SkillVersion
	if sk.LatestVersionID != "" {
		latest, _ = s.Store.GetSkillVersion(sk.LatestVersionID)
	}
	myRating, _ := s.Store.GetSkillRating(sk.ID, userID)
	ratings, _ := s.Store.ListSkillRatings(sk.ID, 50)

	resp := map[string]any{
		"skill":          sk,
		"latest_version": latest,
		"my_rating":      myRating,
		"ratings":        ratings,
		"installed":      s.installedSkillIDs(userID)[sk.ID],
		"can_manage":     canManage,
	}
	if canManage {
		versions, _ := s.Store.ListSkillVersions(sk.ID)
		logs, _ := s.Store.ListSkillReviewLogs(sk.ID)
		resp["versions"] = versions
		resp["review_logs"] = logs
	}
	writeJSON(w, resp)
}

// GET /api/skills/{id}/versions — version history.
// Non-owners only see approved versions.
func (s *SkillHandler) HandleListSkillVersions(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	versions, err := s.Store.ListSkillVersions(sk.ID)
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	if !s.canManageSkill(r, sk) {
		filtered := versions[:0]
		for _, v := range versions {
			if v.Status == store.SkillVersionApproved {
				filtered = append(filtered, v)
			}
		}
		versions = filtered
	}
	writeJSON(w, versions)
}

// GET /api/skills/{id}/reviews — review audit trail (owner or admin).
func (s *SkillHandler) HandleListSkillReviewLogs(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	if !s.canManageSkill(r, sk) {
		shared.JSONError(w, "forbidden", http.StatusForbidden)
		return
	}
	logs, err := s.Store.ListSkillReviewLogs(sk.ID)
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	writeJSON(w, logs)
}

// ---------------------------------------------------------------------------
// Submission (import + review request)
// ---------------------------------------------------------------------------

type skillSubmitForm struct {
	SourceURL string `json:"source_url"`
	Slug      string `json:"slug"`
	Category  string `json:"category"`
	Tags      string `json:"tags"`
	Changelog string `json:"changelog"`
	Icon      string `json:"icon"`
	Homepage  string `json:"homepage"`
}

// POST /api/skills/submit
//
// Two ways to submit, both landing in the same review queue:
//   - multipart/form-data with a "bundle" zip file
//   - JSON {"source_url": "..."} imported from GitHub / any HTTPS URL
func (s *SkillHandler) HandleSubmitSkill(w http.ResponseWriter, r *http.Request) {
	userID := auth.UserIDFromContext(r.Context())

	objStore := s.skillStorage()
	if objStore == nil {
		shared.JSONError(w, "对象存储未配置，无法接收技能包", http.StatusServiceUnavailable)
		return
	}

	var (
		form   skillSubmitForm
		data   []byte
		source = skill.Source{Kind: "upload"}
		err    error
	)

	if strings.HasPrefix(r.Header.Get("Content-Type"), "multipart/form-data") {
		if err := r.ParseMultipartForm(maxSubmitBody); err != nil {
			shared.JSONError(w, "解析上传内容失败："+err.Error(), http.StatusBadRequest)
			return
		}
		form = skillSubmitForm{
			SourceURL: r.FormValue("source_url"),
			Slug:      r.FormValue("slug"),
			Category:  r.FormValue("category"),
			Tags:      r.FormValue("tags"),
			Changelog: r.FormValue("changelog"),
			Icon:      r.FormValue("icon"),
			Homepage:  r.FormValue("homepage"),
		}
		file, _, ferr := r.FormFile("bundle")
		if ferr != nil {
			shared.JSONError(w, "缺少技能包文件（字段名 bundle）", http.StatusBadRequest)
			return
		}
		defer file.Close()
		data, err = io.ReadAll(io.LimitReader(file, skill.MaxBundleSize+1))
		if err != nil {
			shared.JSONError(w, "读取技能包失败", http.StatusBadRequest)
			return
		}
		if len(data) > skill.MaxBundleSize {
			shared.JSONError(w, fmt.Sprintf("技能包超过 %d 字节上限", skill.MaxBundleSize), http.StatusRequestEntityTooLarge)
			return
		}
		source.URL = form.SourceURL
	} else {
		if err := json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&form); err != nil {
			shared.JSONError(w, "invalid request", http.StatusBadRequest)
			return
		}
		if strings.TrimSpace(form.SourceURL) == "" {
			shared.JSONError(w, "需要上传 bundle 文件或提供 source_url", http.StatusBadRequest)
			return
		}
		data, source, err = skill.FetchBundle(r.Context(), form.SourceURL)
		if err != nil {
			shared.JSONError(w, "导入失败："+err.Error(), http.StatusBadRequest)
			return
		}
	}

	bundle, err := skill.Parse(data)
	if err != nil {
		shared.JSONError(w, "技能包校验失败："+err.Error(), http.StatusBadRequest)
		return
	}

	slug := strings.TrimSpace(form.Slug)
	if slug == "" {
		slug = skill.Slugify(bundle.Meta.Name)
	}
	if !skill.ValidSlug(slug) {
		shared.JSONError(w, "技能标识（slug）非法，仅允许小写字母、数字与连字符，长度 2-64", http.StatusBadRequest)
		return
	}

	// Find or create the skill record.
	sk, _ := s.Store.GetSkillBySlug(slug)
	if sk != nil && !s.canManageSkill(r, sk) {
		shared.JSONError(w, "该技能标识已被其他用户占用", http.StatusConflict)
		return
	}

	tags := strings.TrimSpace(form.Tags)
	if tags == "" {
		tags = strings.Join(bundle.Meta.Tags, ",")
	}
	category := firstNonEmptyStr(form.Category, bundle.Meta.Category)
	icon := firstNonEmptyStr(form.Icon, bundle.Meta.Icon)
	homepage := firstNonEmptyStr(form.Homepage, bundle.Meta.Homepage)

	if sk == nil {
		sk, err = s.Store.CreateSkill(&store.Skill{
			Slug:        slug,
			Name:        bundle.Meta.Name,
			Description: bundle.Meta.Description,
			Icon:        icon,
			Category:    category,
			Tags:        tags,
			Homepage:    homepage,
			License:     bundle.Meta.License,
			Author:      bundle.Meta.Author,
			OwnerID:     userID,
			Source:      source.Kind,
			SourceURL:   source.URL,
			Listing:     store.SkillListingDraft,
		})
		if err != nil {
			slog.Error("create skill failed", "slug", slug, "err", err)
			shared.JSONError(w, "创建技能失败", http.StatusInternalServerError)
			return
		}
	} else {
		sk.Name = bundle.Meta.Name
		sk.Description = bundle.Meta.Description
		sk.Icon = icon
		sk.Category = category
		sk.Tags = tags
		sk.Homepage = homepage
		sk.License = bundle.Meta.License
		sk.Author = bundle.Meta.Author
		sk.Source = source.Kind
		sk.SourceURL = source.URL
		if err := s.Store.UpdateSkillMeta(sk.ID, sk); err != nil {
			slog.Error("update skill meta failed", "id", sk.ID, "err", err)
		}
	}

	version := strings.TrimSpace(bundle.Meta.Version)
	if version == "" {
		version = "1.0.0"
	}
	existing, _ := s.Store.ListSkillVersions(sk.ID)
	for _, v := range existing {
		if v.Version == version {
			shared.JSONError(w, fmt.Sprintf("版本 %s 已存在，请更新 SKILL.md 中的 version 字段", version), http.StatusConflict)
			return
		}
	}

	// Upload the normalized bundle before creating the version row so a
	// failed upload never leaves a version pointing at a missing object.
	versionID := uuid.New().String()
	key := fmt.Sprintf("skills/%s/%s.zip", sk.ID, versionID)
	bundleURL, err := objStore.Put(r.Context(), key, "application/zip", bundle.Data)
	if err != nil {
		slog.Error("skill bundle upload failed", "skill", sk.ID, "err", err)
		shared.JSONError(w, "技能包存储失败", http.StatusInternalServerError)
		return
	}

	// A fresh submission replaces any earlier pending one.
	if err := s.Store.SupersedePendingSkillVersions(sk.ID); err != nil {
		slog.Warn("supersede pending skill versions failed", "skill", sk.ID, "err", err)
	}

	ver, err := s.Store.CreateSkillVersion(&store.SkillVersion{
		ID:           versionID,
		SkillID:      sk.ID,
		Version:      version,
		Changelog:    form.Changelog,
		Manifest:     bundle.ManifestJSON(),
		Readme:       bundle.Readme(),
		Entry:        skill.EntryFile,
		BundleKey:    key,
		BundleURL:    bundleURL,
		BundleSize:   bundle.Size,
		BundleSHA256: bundle.SHA256,
		Files:        bundle.FilesJSON(),
		SourceURL:    source.URL,
		CommitHash:   source.Commit,
		Status:       store.SkillVersionPending,
		SubmittedBy:  userID,
	})
	if err != nil {
		slog.Error("create skill version failed", "skill", sk.ID, "err", err)
		shared.JSONError(w, "创建版本失败", http.StatusInternalServerError)
		return
	}

	s.logSkillReview(sk.ID, ver.ID, store.SkillActionSubmit, userID, "", version)

	w.WriteHeader(http.StatusCreated)
	writeJSON(w, map[string]any{
		"skill_id":   sk.ID,
		"slug":       sk.Slug,
		"version_id": ver.ID,
		"version":    ver.Version,
		"status":     ver.Status,
		"files":      bundle.Files,
	})
}

// POST /api/skills/{id}/versions/{vid}/cancel — submitter withdraws a pending version.
func (s *SkillHandler) HandleCancelSkillVersion(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil || !s.canManageSkill(r, sk) {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	ver, err := s.Store.GetSkillVersion(r.PathValue("vid"))
	if err != nil || ver.SkillID != sk.ID {
		shared.JSONError(w, "version not found", http.StatusNotFound)
		return
	}
	if ver.Status != store.SkillVersionPending {
		shared.JSONError(w, "只能撤回待审核的版本", http.StatusBadRequest)
		return
	}
	if err := s.Store.CancelSkillVersion(ver.ID); err != nil {
		shared.JSONError(w, "cancel failed", http.StatusInternalServerError)
		return
	}
	// Nothing else in flight and nothing approved → back to draft.
	if sk.LatestVersionID == "" {
		_ = s.Store.SetSkillListing(sk.ID, store.SkillListingDraft, "")
	}
	s.logSkillReview(sk.ID, ver.ID, store.SkillActionCancel, auth.UserIDFromContext(r.Context()), "", ver.Version)
	shared.JSONOK(w)
}

// DELETE /api/skills/{id} — owner or admin removes a skill entirely.
func (s *SkillHandler) HandleDeleteSkill(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil || !s.canManageSkill(r, sk) {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	if err := s.Store.DeleteSkill(sk.ID); err != nil {
		shared.JSONError(w, "delete failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// ---------------------------------------------------------------------------
// Ratings
// ---------------------------------------------------------------------------

// PUT /api/skills/{id}/rating — create or update the caller's rating.
func (s *SkillHandler) HandleRateSkill(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	var req struct {
		Rating  int    `json:"rating"`
		Comment string `json:"comment"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<18)).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Rating < 1 || req.Rating > 5 {
		shared.JSONError(w, "评分必须为 1-5", http.StatusBadRequest)
		return
	}
	if len([]rune(req.Comment)) > 2000 {
		shared.JSONError(w, "评价内容过长（上限 2000 字）", http.StatusBadRequest)
		return
	}

	version := ""
	if sk.LatestVersionID != "" {
		if v, err := s.Store.GetSkillVersion(sk.LatestVersionID); err == nil {
			version = v.Version
		}
	}

	rating := &store.SkillRating{
		SkillID: sk.ID,
		UserID:  auth.UserIDFromContext(r.Context()),
		Rating:  req.Rating,
		Comment: strings.TrimSpace(req.Comment),
		Version: version,
	}
	if err := s.Store.UpsertSkillRating(rating); err != nil {
		slog.Error("rate skill failed", "skill", sk.ID, "err", err)
		shared.JSONError(w, "评分失败", http.StatusInternalServerError)
		return
	}
	updated, _ := s.Store.GetSkill(sk.ID)
	writeJSON(w, map[string]any{
		"ok":           true,
		"rating_avg":   updated.RatingAvg,
		"rating_count": updated.RatingCount,
	})
}

// DELETE /api/skills/{id}/rating — remove the caller's rating.
func (s *SkillHandler) HandleDeleteSkillRating(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	if err := s.Store.DeleteSkillRating(sk.ID, auth.UserIDFromContext(r.Context())); err != nil {
		shared.JSONError(w, "delete failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// GET /api/skills/{id}/ratings — public rating list for a skill.
func (s *SkillHandler) HandleListSkillRatings(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	ratings, err := s.Store.ListSkillRatings(sk.ID, atoiDefault(r.URL.Query().Get("limit"), 50))
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	writeJSON(w, ratings)
}

// ---------------------------------------------------------------------------
// Install / download
// ---------------------------------------------------------------------------

// POST /api/skills/{id}/install — record an install and return bundle info.
func (s *SkillHandler) HandleInstallSkill(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	if sk.Listing != store.SkillListingListed && !s.canManageSkill(r, sk) {
		shared.JSONError(w, "技能尚未上架", http.StatusForbidden)
		return
	}
	if sk.LatestVersionID == "" {
		shared.JSONError(w, "技能暂无已通过审核的版本", http.StatusBadRequest)
		return
	}

	var req struct {
		AgentID   string `json:"agent_id"`
		VersionID string `json:"version_id"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&req)

	versionID := sk.LatestVersionID
	if req.VersionID != "" {
		v, err := s.Store.GetSkillVersion(req.VersionID)
		if err != nil || v.SkillID != sk.ID || v.Status != store.SkillVersionApproved {
			shared.JSONError(w, "指定版本不可安装", http.StatusBadRequest)
			return
		}
		versionID = v.ID
	}

	userID := auth.UserIDFromContext(r.Context())
	if err := s.Store.RecordSkillInstall(sk.ID, versionID, userID, req.AgentID); err != nil {
		slog.Error("record skill install failed", "skill", sk.ID, "err", err)
		shared.JSONError(w, "安装记录失败", http.StatusInternalServerError)
		return
	}

	ver, _ := s.Store.GetSkillVersion(versionID)
	resp := map[string]any{
		"ok":           true,
		"skill_id":     sk.ID,
		"slug":         sk.Slug,
		"version_id":   versionID,
		"download_url": fmt.Sprintf("/api/skills/%s/versions/%s/download", sk.ID, versionID),
	}
	if ver != nil {
		resp["version"] = ver.Version
		resp["sha256"] = ver.BundleSHA256
		resp["size"] = ver.BundleSize
	}
	writeJSON(w, resp)
}

// DELETE /api/skills/{id}/install — drop an install record.
func (s *SkillHandler) HandleUninstallSkill(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	agentID := r.URL.Query().Get("agent_id")
	if err := s.Store.DeleteSkillInstall(sk.ID, auth.UserIDFromContext(r.Context()), agentID); err != nil {
		shared.JSONError(w, "uninstall failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// GET /api/me/skill-installs — skills the caller installed.
func (s *SkillHandler) HandleMySkillInstalls(w http.ResponseWriter, r *http.Request) {
	installs, err := s.Store.ListSkillInstalls(auth.UserIDFromContext(r.Context()))
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	writeJSON(w, installs)
}

// GET /api/skills/{id}/versions/{vid}/download — download a bundle.
// Owners and admins may download any version; everyone else only approved
// versions of a listed skill.
func (s *SkillHandler) HandleDownloadSkillBundle(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	ver, err := s.Store.GetSkillVersion(r.PathValue("vid"))
	if err != nil || ver.SkillID != sk.ID {
		shared.JSONError(w, "version not found", http.StatusNotFound)
		return
	}
	if !s.canManageSkill(r, sk) &&
		(ver.Status != store.SkillVersionApproved || sk.Listing != store.SkillListingListed) {
		shared.JSONError(w, "版本不可下载", http.StatusForbidden)
		return
	}
	s.serveSkillBundle(w, r, sk, ver)
}

func (s *SkillHandler) serveSkillBundle(w http.ResponseWriter, r *http.Request, sk *store.Skill, ver *store.SkillVersion) {
	objStore := s.skillStorage()
	if objStore == nil || ver.BundleKey == "" {
		shared.JSONError(w, "技能包不可用", http.StatusNotFound)
		return
	}
	data, err := objStore.Get(r.Context(), ver.BundleKey)
	if err != nil {
		slog.Error("skill bundle fetch failed", "key", ver.BundleKey, "err", err)
		shared.JSONError(w, "技能包不可用", http.StatusNotFound)
		return
	}
	if err := s.Store.IncrementSkillDownload(ver.ID); err != nil {
		slog.Warn("increment skill download failed", "version", ver.ID, "err", err)
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("X-Skill-SHA256", ver.BundleSHA256)
	w.Header().Set("Content-Disposition",
		fmt.Sprintf("attachment; filename=%q", sk.Slug+"-"+ver.Version+".zip"))
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Write(data)
}

// ---------------------------------------------------------------------------
// Admin review
// ---------------------------------------------------------------------------

// GET /api/admin/skills/pending — the review queue.
func (s *SkillHandler) HandleAdminPendingSkills(w http.ResponseWriter, r *http.Request) {
	versions, err := s.Store.ListPendingSkillVersions()
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	writeJSON(w, versions)
}

// GET /api/admin/skills — every skill regardless of state.
func (s *SkillHandler) HandleAdminListSkills(w http.ResponseWriter, r *http.Request) {
	skills, err := s.Store.ListSkills(store.SkillQuery{
		Listing: r.URL.Query().Get("listing"),
		Search:  strings.TrimSpace(r.URL.Query().Get("q")),
		Sort:    r.URL.Query().Get("sort"),
	})
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}
	writeJSON(w, skills)
}

// PUT /api/admin/skills/versions/{vid}/review — approve or reject a version.
func (s *SkillHandler) HandleReviewSkillVersion(w http.ResponseWriter, r *http.Request) {
	versionID := r.PathValue("vid")
	actorID := auth.UserIDFromContext(r.Context())

	var req struct {
		Status string `json:"status"`
		Reason string `json:"reason"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<18)).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	if req.Status != store.SkillVersionApproved && req.Status != store.SkillVersionRejected {
		shared.JSONError(w, "status 必须为 approved 或 rejected", http.StatusBadRequest)
		return
	}
	if req.Status == store.SkillVersionRejected && strings.TrimSpace(req.Reason) == "" {
		shared.JSONError(w, "拒绝时必须填写原因", http.StatusBadRequest)
		return
	}

	ver, err := s.Store.GetSkillVersion(versionID)
	if err != nil {
		shared.JSONError(w, "version not found", http.StatusNotFound)
		return
	}
	if ver.Status != store.SkillVersionPending {
		shared.JSONError(w, "该版本不在待审核状态", http.StatusBadRequest)
		return
	}

	if err := s.Store.ReviewSkillVersion(versionID, req.Status, actorID, strings.TrimSpace(req.Reason)); err != nil {
		slog.Error("review skill version failed", "version", versionID, "err", err)
		shared.JSONError(w, "审核失败", http.StatusInternalServerError)
		return
	}

	action := store.SkillActionApprove
	if req.Status == store.SkillVersionRejected {
		action = store.SkillActionReject
	}
	s.logSkillReview(ver.SkillID, versionID, action, actorID, strings.TrimSpace(req.Reason), ver.Version)
	shared.JSONOK(w)
}

// PUT /api/admin/skills/{id}/listing — take a skill down or put it back.
func (s *SkillHandler) HandleAdminSetSkillListing(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	var req struct {
		Listing string `json:"listing"`
		Reason  string `json:"reason"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<18)).Decode(&req); err != nil {
		shared.JSONError(w, "invalid request", http.StatusBadRequest)
		return
	}
	switch req.Listing {
	case store.SkillListingListed:
		if sk.LatestVersionID == "" {
			shared.JSONError(w, "技能没有已通过审核的版本，无法上架", http.StatusBadRequest)
			return
		}
	case store.SkillListingUnlisted:
	default:
		shared.JSONError(w, "listing 必须为 listed 或 unlisted", http.StatusBadRequest)
		return
	}

	if err := s.Store.SetSkillListing(sk.ID, req.Listing, strings.TrimSpace(req.Reason)); err != nil {
		shared.JSONError(w, "update failed", http.StatusInternalServerError)
		return
	}
	action := store.SkillActionRelist
	if req.Listing == store.SkillListingUnlisted {
		action = store.SkillActionUnlist
	}
	s.logSkillReview(sk.ID, "", action, auth.UserIDFromContext(r.Context()), strings.TrimSpace(req.Reason), "")
	shared.JSONOK(w)
}

// DELETE /api/admin/skills/{id}
func (s *SkillHandler) HandleAdminDeleteSkill(w http.ResponseWriter, r *http.Request) {
	sk := s.lookupSkill(r.PathValue("id"))
	if sk == nil {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	if err := s.Store.DeleteSkill(sk.ID); err != nil {
		shared.JSONError(w, "delete failed", http.StatusInternalServerError)
		return
	}
	shared.JSONOK(w)
}

// ---------------------------------------------------------------------------
// Public registry (consumed by clients such as edict agents)
// ---------------------------------------------------------------------------

// GET /api/registry/v1/skills.json — listed skills with their approved version.
func (s *SkillHandler) HandleRegistrySkills(w http.ResponseWriter, r *http.Request) {
	skills, err := s.Store.ListSkills(store.SkillQuery{
		Listing:  store.SkillListingListed,
		Category: r.URL.Query().Get("category"),
		Search:   strings.TrimSpace(r.URL.Query().Get("q")),
		Sort:     r.URL.Query().Get("sort"),
	})
	if err != nil {
		shared.JSONError(w, "list failed", http.StatusInternalServerError)
		return
	}

	type item struct {
		Slug        string   `json:"slug"`
		Name        string   `json:"name"`
		Description string   `json:"description"`
		Icon        string   `json:"icon,omitempty"`
		Category    string   `json:"category,omitempty"`
		Tags        []string `json:"tags,omitempty"`
		License     string   `json:"license,omitempty"`
		Author      string   `json:"author,omitempty"`
		Homepage    string   `json:"homepage,omitempty"`
		Version     string   `json:"version,omitempty"`
		SHA256      string   `json:"sha256,omitempty"`
		Size        int64    `json:"size,omitempty"`
		DownloadURL string   `json:"download_url"`
		RatingAvg   float64  `json:"rating_avg"`
		RatingCount int      `json:"rating_count"`
		Installs    int      `json:"installs"`
		UpdatedAt   int64    `json:"updated_at"`
	}

	out := make([]item, 0, len(skills))
	for _, sk := range skills {
		if sk.LatestVersionID == "" {
			continue
		}
		it := item{
			Slug: sk.Slug, Name: sk.Name, Description: sk.Description, Icon: sk.Icon,
			Category: sk.Category, Tags: splitTags(sk.Tags), License: sk.License,
			Author: sk.Author, Homepage: sk.Homepage, Version: sk.LatestVersion,
			RatingAvg: sk.RatingAvg, RatingCount: sk.RatingCount, Installs: sk.InstallCount,
			UpdatedAt:   sk.UpdatedAt,
			DownloadURL: "/api/registry/v1/skills/" + sk.Slug + "/download",
		}
		if v, err := s.Store.GetSkillVersion(sk.LatestVersionID); err == nil {
			it.SHA256 = v.BundleSHA256
			it.Size = v.BundleSize
		}
		out = append(out, it)
	}
	writeJSON(w, map[string]any{"skills": out, "count": len(out)})
}

// GET /api/registry/v1/skills/{slug}/download — public bundle download for the
// latest approved version of a listed skill.
func (s *SkillHandler) HandleRegistrySkillDownload(w http.ResponseWriter, r *http.Request) {
	sk, err := s.Store.GetSkillBySlug(r.PathValue("slug"))
	if err != nil || sk == nil || sk.Listing != store.SkillListingListed || sk.LatestVersionID == "" {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	ver, err := s.Store.GetSkillVersion(sk.LatestVersionID)
	if err != nil || ver.Status != store.SkillVersionApproved {
		shared.JSONError(w, "not found", http.StatusNotFound)
		return
	}
	s.serveSkillBundle(w, r, sk, ver)
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

// lookupSkill resolves a path parameter that may be either an ID or a slug.
func (s *SkillHandler) lookupSkill(idOrSlug string) *store.Skill {
	if idOrSlug == "" {
		return nil
	}
	if sk, err := s.Store.GetSkill(idOrSlug); err == nil && sk != nil {
		return sk
	}
	if sk, err := s.Store.GetSkillBySlug(idOrSlug); err == nil && sk != nil {
		return sk
	}
	return nil
}

func (s *SkillHandler) logSkillReview(skillID, versionID, action, actorID, reason, version string) {
	if err := s.Store.CreateSkillReviewLog(&store.SkillReviewLog{
		SkillID: skillID, VersionID: versionID, Action: action,
		ActorID: actorID, Reason: reason, Version: version,
	}); err != nil {
		slog.Warn("create skill review log failed", "skill", skillID, "action", action, "err", err)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func atoiDefault(s string, def int) int {
	if s == "" {
		return def
	}
	n, err := strconv.Atoi(s)
	if err != nil || n < 0 {
		return def
	}
	return n
}

func firstNonEmptyStr(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func splitTags(tags string) []string {
	var out []string
	for _, t := range strings.Split(tags, ",") {
		if t = strings.TrimSpace(t); t != "" {
			out = append(out, t)
		}
	}
	return out
}
