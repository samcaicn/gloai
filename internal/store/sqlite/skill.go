package sqlite

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// GetSkillEvaluation returns a stub skill evaluation.
func (db *DB) GetSkillEvaluation(ctx context.Context, skillID string) (*store.SkillEvaluation, error) {
	return &store.SkillEvaluation{
		SkillID:        skillID,
		OverallScore:   0,
		QualityScore:   0,
		UsageScore:     0,
		SampleCount:    0,
		LastEvaluated:  0,
	}, nil
}

// --- helpers ---

const skillCols = `s.id, s.slug, s.name, s.description, s.icon, s.category, s.tags, s.homepage,
	s.license, s.author, s.owner_id, s.source, s.source_url, s.latest_version_id, s.listing,
	s.reject_reason, s.install_count, s.rating_sum, s.rating_count, s.created_at, s.updated_at,
	COALESCE(u.username, ''), COALESCE(v.version, '')`

const skillFrom = `FROM skills s
	LEFT JOIN users u ON u.id = s.owner_id
	LEFT JOIN skill_versions v ON v.id = s.latest_version_id`

func scanSkill(sc interface{ Scan(...any) error }) (store.Skill, error) {
	var s store.Skill
	err := sc.Scan(&s.ID, &s.Slug, &s.Name, &s.Description, &s.Icon, &s.Category, &s.Tags, &s.Homepage,
		&s.License, &s.Author, &s.OwnerID, &s.Source, &s.SourceURL, &s.LatestVersionID, &s.Listing,
		&s.RejectReason, &s.InstallCount, &s.RatingSum, &s.RatingCount, &s.CreatedAt, &s.UpdatedAt,
		&s.OwnerName, &s.LatestVersion)
	if err != nil {
		return s, err
	}
	if s.RatingCount > 0 {
		s.RatingAvg = float64(s.RatingSum) / float64(s.RatingCount)
	}
	return s, nil
}

const skillVersionCols = `v.id, v.skill_id, v.version, v.changelog, v.manifest, v.readme, v.entry,
	v.bundle_key, v.bundle_url, v.bundle_size, v.bundle_sha256, v.files, v.source_url, v.commit_hash,
	v.status, v.reject_reason, v.submitted_by, v.reviewed_by, v.reviewed_at, v.download_count, v.created_at,
	COALESCE(s.name, ''), COALESCE(s.slug, ''), COALESCE(s.icon, ''),
	COALESCE(sub.username, ''), COALESCE(rev.username, '')`

const skillVersionFrom = `FROM skill_versions v
	LEFT JOIN skills s ON s.id = v.skill_id
	LEFT JOIN users sub ON sub.id = v.submitted_by
	LEFT JOIN users rev ON rev.id = v.reviewed_by`

func scanSkillVersion(sc interface{ Scan(...any) error }) (store.SkillVersion, error) {
	var v store.SkillVersion
	var manifest, files string
	err := sc.Scan(&v.ID, &v.SkillID, &v.Version, &v.Changelog, &manifest, &v.Readme, &v.Entry,
		&v.BundleKey, &v.BundleURL, &v.BundleSize, &v.BundleSHA256, &files, &v.SourceURL, &v.CommitHash,
		&v.Status, &v.RejectReason, &v.SubmittedBy, &v.ReviewedBy, &v.ReviewedAt, &v.DownloadCount, &v.CreatedAt,
		&v.SkillName, &v.SkillSlug, &v.SkillIcon, &v.SubmitterName, &v.ReviewerName)
	if err != nil {
		return v, err
	}
	v.Manifest = json.RawMessage(orDefault(manifest, "{}"))
	v.Files = json.RawMessage(orDefault(files, "[]"))
	return v, nil
}

func orDefault(s, def string) string {
	if strings.TrimSpace(s) == "" {
		return def
	}
	return s
}

// --- Skills ---

func (db *DB) CreateSkill(s *store.Skill) (*store.Skill, error) {
	if s.ID == "" {
		s.ID = uuid.New().String()
	}
	if s.Listing == "" {
		s.Listing = store.SkillListingDraft
	}
	if s.Source == "" {
		s.Source = "upload"
	}
	now := db.now()
	s.CreatedAt, s.UpdatedAt = now, now
	_, err := db.Exec(`INSERT INTO skills
		(id, slug, name, description, icon, category, tags, homepage, license, author, owner_id,
		 source, source_url, listing, created_at, updated_at)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		s.ID, s.Slug, s.Name, s.Description, s.Icon, s.Category, s.Tags, s.Homepage, s.License, s.Author,
		s.OwnerID, s.Source, s.SourceURL, s.Listing, now, now)
	if err != nil {
		return nil, err
	}
	return s, nil
}

// CreateSkillUploadTicket creates a skill upload ticket.
func (db *DB) CreateSkillUploadTicket(ctx context.Context, req *store.SkillUploadTicketRequest) (*store.SkillUploadTicket, error) {
	ticket := &store.SkillUploadTicket{
		TicketID:  uuid.New().String(),
		UploadURL: "",
		Method:    "PUT",
		Headers:   map[string]string{},
		Key:       "",
		MaxSize:   100 * 1024 * 1024, // 100MB
		ExpiresAt: time.Now().Add(15 * time.Minute).Unix(),
	}
	_, err := db.ExecContext(ctx, `INSERT INTO skill_upload_tickets
		(id, skill_name, version, expires_at, created_at)
		VALUES (?,?,?,?,?)`,
		ticket.TicketID, req.SkillName, req.Version, ticket.ExpiresAt, time.Now().Unix())
	if err != nil {
		return nil, err
	}
	return ticket, nil
}

// CreateUploadTicket creates a general upload ticket for skills.
func (db *DB) CreateUploadTicket(ctx context.Context, skillID string, ttl int) (*store.UploadTicket, error) {
	ticket := &store.UploadTicket{
		TicketID:  uuid.New().String(),
		UploadURL: "",
		Method:    "PUT",
		Headers:   map[string]string{},
		Key:       "",
		MaxSize:   100 * 1024 * 1024, // 100MB
		ExpiresAt: time.Now().Add(time.Duration(ttl) * time.Second).Unix(),
	}
	_, err := db.ExecContext(ctx, `INSERT INTO upload_tickets
		(id, skill_id, expires_at, created_at)
		VALUES (?,?,?,?)`,
		ticket.TicketID, skillID, ticket.ExpiresAt, time.Now().Unix())
	if err != nil {
		return nil, err
	}
	return ticket, nil
}

func (db *DB) GetSkill(id string) (*store.Skill, error) {
	row := db.QueryRow(`SELECT `+skillCols+` `+skillFrom+` WHERE s.id = ?`, id)
	s, err := scanSkill(row)
	if err != nil {
		return nil, err
	}
	return &s, nil
}

func (db *DB) GetSkillBySlug(slug string) (*store.Skill, error) {
	row := db.QueryRow(`SELECT `+skillCols+` `+skillFrom+` WHERE s.slug = ?`, slug)
	s, err := scanSkill(row)
	if err != nil {
		return nil, err
	}
	return &s, nil
}

func (db *DB) ListSkills(q store.SkillQuery) ([]store.Skill, error) {
	var where []string
	var args []any
	if q.Listing != "" {
		where = append(where, "s.listing = ?")
		args = append(args, q.Listing)
	}
	if q.OwnerID != "" {
		where = append(where, "s.owner_id = ?")
		args = append(args, q.OwnerID)
	}
	if q.Category != "" {
		where = append(where, "s.category = ?")
		args = append(args, q.Category)
	}
	if q.Search != "" {
		where = append(where, "(LOWER(s.name) LIKE ? OR LOWER(s.slug) LIKE ? OR LOWER(s.description) LIKE ? OR LOWER(s.tags) LIKE ?)")
		pat := "%" + strings.ToLower(q.Search) + "%"
		args = append(args, pat, pat, pat, pat)
	}

	sql := `SELECT ` + skillCols + ` ` + skillFrom
	if len(where) > 0 {
		sql += " WHERE " + strings.Join(where, " AND ")
	}
	sql += " ORDER BY " + skillOrderBy(q.Sort)
	if q.Limit > 0 {
		sql += fmt.Sprintf(" LIMIT %d", q.Limit)
		if q.Offset > 0 {
			sql += fmt.Sprintf(" OFFSET %d", q.Offset)
		}
	}

	rows, err := db.Query(sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []store.Skill{}
	for rows.Next() {
		s, err := scanSkill(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// skillOrderBy maps an API sort key to a deterministic ORDER BY clause.
// The value is never user-controlled beyond this whitelist.
func skillOrderBy(sort string) string {
	switch sort {
	case "rating":
		// Bayesian-ish: unrated skills sort last, ties broken by install count.
		return "(CASE WHEN s.rating_count = 0 THEN 0 ELSE CAST(s.rating_sum AS REAL) / s.rating_count END) DESC, s.rating_count DESC, s.install_count DESC"
	case "installs":
		return "s.install_count DESC, s.updated_at DESC"
	case "newest":
		return "s.created_at DESC"
	default:
		return "s.updated_at DESC"
	}
}

func (db *DB) UpdateSkillMeta(id string, s *store.Skill) error {
	_, err := db.Exec(`UPDATE skills SET name=?, description=?, icon=?, category=?, tags=?,
		homepage=?, license=?, author=?, source=?, source_url=?, updated_at=? WHERE id=?`,
		s.Name, s.Description, s.Icon, s.Category, s.Tags, s.Homepage, s.License, s.Author,
		s.Source, s.SourceURL, db.now(), id)
	return err
}

func (db *DB) SetSkillListing(id, listing, reason string) error {
	_, err := db.Exec(`UPDATE skills SET listing=?, reject_reason=?, updated_at=? WHERE id=?`,
		listing, reason, db.now(), id)
	return err
}

func (db *DB) DeleteSkill(id string) error {
	_, err := db.Exec(`DELETE FROM skills WHERE id = ?`, id)
	return err
}

// --- Versions ---

func (db *DB) CreateSkillVersion(v *store.SkillVersion) (*store.SkillVersion, error) {
	if v.ID == "" {
		v.ID = uuid.New().String()
	}
	if v.Status == "" {
		v.Status = store.SkillVersionPending
	}
	if v.Entry == "" {
		v.Entry = "SKILL.md"
	}
	if v.Version == "" {
		v.Version = "1.0.0"
	}
	v.CreatedAt = db.now()
	_, err := db.Exec(`INSERT INTO skill_versions
		(id, skill_id, version, changelog, manifest, readme, entry, bundle_key, bundle_url, bundle_size,
		 bundle_sha256, files, source_url, commit_hash, status, submitted_by, created_at)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		v.ID, v.SkillID, v.Version, v.Changelog, orDefault(string(v.Manifest), "{}"), v.Readme, v.Entry,
		v.BundleKey, v.BundleURL, v.BundleSize, v.BundleSHA256, orDefault(string(v.Files), "[]"),
		v.SourceURL, v.CommitHash, v.Status, v.SubmittedBy, v.CreatedAt)
	if err != nil {
		return nil, err
	}
	// A new submission moves the skill into the review queue unless it is
	// already listed (an update to a live skill keeps the old version live).
	if _, err := db.Exec(`UPDATE skills SET listing = CASE WHEN listing IN ('draft','rejected') THEN 'pending' ELSE listing END,
		updated_at = ? WHERE id = ?`, db.now(), v.SkillID); err != nil {
		return nil, err
	}
	return v, nil
}

func (db *DB) GetSkillVersion(id string) (*store.SkillVersion, error) {
	row := db.QueryRow(`SELECT `+skillVersionCols+` `+skillVersionFrom+` WHERE v.id = ?`, id)
	v, err := scanSkillVersion(row)
	if err != nil {
		return nil, err
	}
	return &v, nil
}

func (db *DB) ListSkillVersions(skillID string) ([]store.SkillVersion, error) {
	rows, err := db.Query(`SELECT `+skillVersionCols+` `+skillVersionFrom+
		// v.version is only a tiebreaker for rows created in the same second.
		` WHERE v.skill_id = ? ORDER BY v.created_at DESC, v.version DESC`, skillID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []store.SkillVersion{}
	for rows.Next() {
		v, err := scanSkillVersion(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (db *DB) ListPendingSkillVersions() ([]store.SkillVersion, error) {
	rows, err := db.Query(`SELECT ` + skillVersionCols + ` ` + skillVersionFrom +
		` WHERE v.status = 'pending' ORDER BY v.created_at ASC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []store.SkillVersion{}
	for rows.Next() {
		v, err := scanSkillVersion(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (db *DB) SupersedePendingSkillVersions(skillID string) error {
	_, err := db.Exec(`UPDATE skill_versions SET status = 'superseded'
		WHERE skill_id = ? AND status = 'pending'`, skillID)
	return err
}

func (db *DB) ReviewSkillVersion(versionID, status, reviewerID, reason string) error {
	now := db.now()
	if _, err := db.Exec(`UPDATE skill_versions SET status=?, reviewed_by=?, reviewed_at=?, reject_reason=?
		WHERE id=?`, status, reviewerID, now, reason, versionID); err != nil {
		return err
	}

	var skillID string
	if err := db.QueryRow(`SELECT skill_id FROM skill_versions WHERE id = ?`, versionID).Scan(&skillID); err != nil {
		return err
	}

	switch status {
	case store.SkillVersionApproved:
		_, err := db.Exec(`UPDATE skills SET latest_version_id=?, listing='listed', reject_reason='', updated_at=?
			WHERE id=?`, versionID, now, skillID)
		return err
	case store.SkillVersionRejected:
		// Only drop the whole skill back to "rejected" when nothing is live yet.
		var approved int
		db.QueryRow(`SELECT COUNT(*) FROM skill_versions WHERE skill_id = ? AND status = 'approved'`, skillID).Scan(&approved)
		if approved == 0 {
			_, err := db.Exec(`UPDATE skills SET listing='rejected', reject_reason=?, updated_at=? WHERE id=?`,
				reason, now, skillID)
			return err
		}
		_, err := db.Exec(`UPDATE skills SET updated_at=? WHERE id=?`, now, skillID)
		return err
	}
	return nil
}

func (db *DB) CancelSkillVersion(versionID string) error {
	_, err := db.Exec(`UPDATE skill_versions SET status='cancelled' WHERE id=? AND status='pending'`, versionID)
	return err
}

func (db *DB) IncrementSkillDownload(versionID string) error {
	_, err := db.Exec(`UPDATE skill_versions SET download_count = download_count + 1 WHERE id = ?`, versionID)
	return err
}

// --- Ratings ---

func (db *DB) UpsertSkillRating(r *store.SkillRating) error {
	if r.ID == "" {
		r.ID = uuid.New().String()
	}
	now := db.now()
	r.UpdatedAt = now
	if _, err := db.Exec(`INSERT INTO skill_ratings (id, skill_id, user_id, rating, comment, version, created_at, updated_at)
		VALUES (?,?,?,?,?,?,?,?)
		ON CONFLICT (skill_id, user_id) DO UPDATE SET rating=excluded.rating, comment=excluded.comment,
			version=excluded.version, updated_at=excluded.updated_at`,
		r.ID, r.SkillID, r.UserID, r.Rating, r.Comment, r.Version, now, now); err != nil {
		return err
	}
	return db.recomputeSkillRating(r.SkillID)
}

func (db *DB) recomputeSkillRating(skillID string) error {
	_, err := db.Exec(`UPDATE skills SET
		rating_sum   = COALESCE((SELECT SUM(rating) FROM skill_ratings WHERE skill_id = ?), 0),
		rating_count = COALESCE((SELECT COUNT(*)    FROM skill_ratings WHERE skill_id = ?), 0)
		WHERE id = ?`, skillID, skillID, skillID)
	return err
}

func (db *DB) GetSkillRating(skillID, userID string) (*store.SkillRating, error) {
	r := &store.SkillRating{}
	err := db.QueryRow(`SELECT r.id, r.skill_id, r.user_id, r.rating, r.comment, r.version, r.created_at, r.updated_at,
		COALESCE(u.username, '')
		FROM skill_ratings r LEFT JOIN users u ON u.id = r.user_id
		WHERE r.skill_id = ? AND r.user_id = ?`, skillID, userID).
		Scan(&r.ID, &r.SkillID, &r.UserID, &r.Rating, &r.Comment, &r.Version, &r.CreatedAt, &r.UpdatedAt, &r.UserName)
	if err != nil {
		return nil, err
	}
	return r, nil
}

func (db *DB) DeleteSkillRating(skillID, userID string) error {
	if _, err := db.Exec(`DELETE FROM skill_ratings WHERE skill_id = ? AND user_id = ?`, skillID, userID); err != nil {
		return err
	}
	return db.recomputeSkillRating(skillID)
}

func (db *DB) ListSkillRatings(skillID string, limit int) ([]store.SkillRating, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	rows, err := db.Query(`SELECT r.id, r.skill_id, r.user_id, r.rating, r.comment, r.version, r.created_at, r.updated_at,
		COALESCE(u.username, '')
		FROM skill_ratings r LEFT JOIN users u ON u.id = r.user_id
		WHERE r.skill_id = ? ORDER BY r.updated_at DESC LIMIT ?`, skillID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []store.SkillRating{}
	for rows.Next() {
		var r store.SkillRating
		if err := rows.Scan(&r.ID, &r.SkillID, &r.UserID, &r.Rating, &r.Comment, &r.Version,
			&r.CreatedAt, &r.UpdatedAt, &r.UserName); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// --- Installs ---

func (db *DB) RecordSkillInstall(skillID, versionID, userID, agentID string) error {
	now := db.now()
	if _, err := db.Exec(`INSERT INTO skill_installs (id, skill_id, version_id, user_id, agent_id, created_at)
		VALUES (?,?,?,?,?,?)
		ON CONFLICT (skill_id, user_id, agent_id) DO UPDATE SET version_id=excluded.version_id, created_at=excluded.created_at`,
		uuid.New().String(), skillID, versionID, userID, agentID, now); err != nil {
		return err
	}
	_, err := db.Exec(`UPDATE skills SET install_count = (SELECT COUNT(*) FROM skill_installs WHERE skill_id = ?)
		WHERE id = ?`, skillID, skillID)
	return err
}

func (db *DB) ListSkillInstalls(userID string) ([]store.SkillInstall, error) {
	rows, err := db.Query(`SELECT i.id, i.skill_id, i.version_id, i.user_id, i.agent_id, i.created_at,
		COALESCE(s.name, ''), COALESCE(s.slug, ''), COALESCE(s.icon, ''), COALESCE(v.version, '')
		FROM skill_installs i
		LEFT JOIN skills s ON s.id = i.skill_id
		LEFT JOIN skill_versions v ON v.id = i.version_id
		WHERE i.user_id = ? ORDER BY i.created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []store.SkillInstall{}
	for rows.Next() {
		var in store.SkillInstall
		if err := rows.Scan(&in.ID, &in.SkillID, &in.VersionID, &in.UserID, &in.AgentID, &in.CreatedAt,
			&in.SkillName, &in.SkillSlug, &in.SkillIcon, &in.Version); err != nil {
			return nil, err
		}
		out = append(out, in)
	}
	return out, rows.Err()
}

func (db *DB) DeleteSkillInstall(skillID, userID, agentID string) error {
	if _, err := db.Exec(`DELETE FROM skill_installs WHERE skill_id=? AND user_id=? AND agent_id=?`,
		skillID, userID, agentID); err != nil {
		return err
	}
	_, err := db.Exec(`UPDATE skills SET install_count = (SELECT COUNT(*) FROM skill_installs WHERE skill_id = ?)
		WHERE id = ?`, skillID, skillID)
	return err
}

// --- Review audit log ---

func (db *DB) CreateSkillReviewLog(l *store.SkillReviewLog) error {
	if l.ID == "" {
		l.ID = uuid.New().String()
	}
	l.CreatedAt = db.now()
	_, err := db.Exec(`INSERT INTO skill_reviews (id, skill_id, version_id, action, actor_id, reason, version, created_at)
		VALUES (?,?,?,?,?,?,?,?)`,
		l.ID, l.SkillID, l.VersionID, l.Action, l.ActorID, l.Reason, l.Version, l.CreatedAt)
	return err
}

func (db *DB) ListSkillReviewLogs(skillID string) ([]store.SkillReviewLog, error) {
	rows, err := db.Query(`SELECT r.id, r.skill_id, r.version_id, r.action, r.actor_id, r.reason, r.version, r.created_at,
		COALESCE(u.username, '')
		FROM skill_reviews r LEFT JOIN users u ON u.id = r.actor_id
		WHERE r.skill_id = ? ORDER BY r.created_at DESC`, skillID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []store.SkillReviewLog{}
	for rows.Next() {
		var l store.SkillReviewLog
		if err := rows.Scan(&l.ID, &l.SkillID, &l.VersionID, &l.Action, &l.ActorID, &l.Reason,
			&l.Version, &l.CreatedAt, &l.ActorName); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}
