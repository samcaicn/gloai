package store

import "encoding/json"

// Skill listing states (marketplace visibility of the skill as a whole).
const (
	SkillListingDraft    = "draft"    // created, never submitted
	SkillListingPending  = "pending"  // a version is awaiting review
	SkillListingListed   = "listed"   // has an approved version, visible in marketplace
	SkillListingRejected = "rejected" // last review was a rejection, nothing approved yet
	SkillListingUnlisted = "unlisted" // taken down by admin / owner
)

// Skill version review states.
const (
	SkillVersionPending    = "pending"
	SkillVersionApproved   = "approved"
	SkillVersionRejected   = "rejected"
	SkillVersionSuperseded = "superseded" // replaced by a newer submission before review
	SkillVersionCancelled  = "cancelled"  // withdrawn by the submitter
)

// Skill review-log actions.
const (
	SkillActionSubmit  = "submit"
	SkillActionApprove = "approve"
	SkillActionReject  = "reject"
	SkillActionCancel  = "cancel"
	SkillActionUnlist  = "unlist"
	SkillActionRelist  = "relist"
)

// Skill is a marketplace skill (an Agent Skill package: SKILL.md + resources).
// One Skill row aggregates all of its versions; ratings are aggregated at the
// skill level while review happens per version.
type Skill struct {
	ID              string  `json:"id"`
	Slug            string  `json:"slug"`
	Name            string  `json:"name"`
	Description     string  `json:"description"`
	Icon            string  `json:"icon,omitempty"`
	Category        string  `json:"category,omitempty"`
	Tags            string  `json:"tags,omitempty"` // comma-separated
	Homepage        string  `json:"homepage,omitempty"`
	License         string  `json:"license,omitempty"`
	Author          string  `json:"author,omitempty"`
	OwnerID         string  `json:"owner_id"`
	Source          string  `json:"source"` // upload | url | github | builtin
	SourceURL       string  `json:"source_url,omitempty"`
	LatestVersionID string  `json:"latest_version_id,omitempty"`
	Listing         string  `json:"listing"`
	RejectReason    string  `json:"reject_reason,omitempty"`
	InstallCount    int     `json:"install_count"`
	RatingSum       int     `json:"-"`
	RatingCount     int     `json:"rating_count"`
	RatingAvg       float64 `json:"rating_avg"`
	CreatedAt       int64   `json:"created_at"`
	UpdatedAt       int64   `json:"updated_at"`

	// Joined / derived
	OwnerName     string `json:"owner_name,omitempty"`
	LatestVersion string `json:"latest_version,omitempty"`
}

// SkillFile describes a single entry inside a skill bundle.
type SkillFile struct {
	Path string `json:"path"`
	Size int64  `json:"size"`
}

// SkillVersion is one submitted revision of a skill. Every submission creates a
// pending version; admins approve or reject versions individually.
type SkillVersion struct {
	ID            string          `json:"id"`
	SkillID       string          `json:"skill_id"`
	Version       string          `json:"version"`
	Changelog     string          `json:"changelog,omitempty"`
	Manifest      json.RawMessage `json:"manifest"`         // SKILL.md frontmatter as JSON
	Readme        string          `json:"readme,omitempty"` // SKILL.md body (truncated preview)
	Entry         string          `json:"entry"`            // usually "SKILL.md"
	BundleKey     string          `json:"-"`                // object-storage key
	BundleURL     string          `json:"bundle_url,omitempty"`
	BundleSize    int64           `json:"bundle_size"`
	BundleSHA256  string          `json:"bundle_sha256,omitempty"`
	Files         json.RawMessage `json:"files"` // []SkillFile
	SourceURL     string          `json:"source_url,omitempty"`
	CommitHash    string          `json:"commit_hash,omitempty"`
	Status        string          `json:"status"`
	RejectReason  string          `json:"reject_reason,omitempty"`
	SubmittedBy   string          `json:"submitted_by,omitempty"`
	ReviewedBy    string          `json:"reviewed_by,omitempty"`
	ReviewedAt    int64           `json:"reviewed_at,omitempty"`
	DownloadCount int             `json:"download_count"`
	CreatedAt     int64           `json:"created_at"`

	// Joined
	SkillName     string `json:"skill_name,omitempty"`
	SkillSlug     string `json:"skill_slug,omitempty"`
	SkillIcon     string `json:"skill_icon,omitempty"`
	SubmitterName string `json:"submitter_name,omitempty"`
	ReviewerName  string `json:"reviewer_name,omitempty"`
}

// SkillRating is one user's rating of a skill (one row per user per skill).
type SkillRating struct {
	ID        string `json:"id"`
	SkillID   string `json:"skill_id"`
	UserID    string `json:"user_id"`
	Rating    int    `json:"rating"` // 1..5
	Comment   string `json:"comment,omitempty"`
	Version   string `json:"version,omitempty"` // version in use when rated
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`

	// Joined
	UserName string `json:"user_name,omitempty"`
}

// SkillInstall records a skill installed by a user (optionally scoped to a
// client agent, e.g. an edict agent ID).
type SkillInstall struct {
	ID        string `json:"id"`
	SkillID   string `json:"skill_id"`
	VersionID string `json:"version_id"`
	UserID    string `json:"user_id"`
	AgentID   string `json:"agent_id,omitempty"`
	CreatedAt int64  `json:"created_at"`

	// Joined
	SkillName string `json:"skill_name,omitempty"`
	SkillSlug string `json:"skill_slug,omitempty"`
	SkillIcon string `json:"skill_icon,omitempty"`
	Version   string `json:"version,omitempty"`
}

// SkillReviewLog is an audit record of a review-flow transition.
type SkillReviewLog struct {
	ID        string `json:"id"`
	SkillID   string `json:"skill_id"`
	VersionID string `json:"version_id,omitempty"`
	Action    string `json:"action"`
	ActorID   string `json:"actor_id"`
	Reason    string `json:"reason,omitempty"`
	Version   string `json:"version,omitempty"`
	CreatedAt int64  `json:"created_at"`

	// Joined
	ActorName string `json:"actor_name,omitempty"`
}

// SkillQuery filters and sorts a marketplace listing.
type SkillQuery struct {
	Listing  string // "" = any state
	OwnerID  string // "" = any owner
	Category string
	Search   string // matched against name / slug / description / tags
	Sort     string // rating | installs | newest | updated (default)
	Limit    int
	Offset   int
}

// SkillStore persists the skill marketplace: skills, their versions (review
// flow), ratings, installs and the review audit trail.
type SkillStore interface {
	CreateSkill(s *Skill) (*Skill, error)
	GetSkill(id string) (*Skill, error)
	GetSkillBySlug(slug string) (*Skill, error)
	ListSkills(q SkillQuery) ([]Skill, error)
	UpdateSkillMeta(id string, s *Skill) error
	SetSkillListing(id, listing, reason string) error
	DeleteSkill(id string) error

	CreateSkillVersion(v *SkillVersion) (*SkillVersion, error)
	GetSkillVersion(id string) (*SkillVersion, error)
	ListSkillVersions(skillID string) ([]SkillVersion, error)
	ListPendingSkillVersions() ([]SkillVersion, error)
	SupersedePendingSkillVersions(skillID string) error
	ReviewSkillVersion(versionID, status, reviewerID, reason string) error
	CancelSkillVersion(versionID string) error
	IncrementSkillDownload(versionID string) error

	UpsertSkillRating(r *SkillRating) error
	GetSkillRating(skillID, userID string) (*SkillRating, error)
	DeleteSkillRating(skillID, userID string) error
	ListSkillRatings(skillID string, limit int) ([]SkillRating, error)

	RecordSkillInstall(skillID, versionID, userID, agentID string) error
	ListSkillInstalls(userID string) ([]SkillInstall, error)
	DeleteSkillInstall(skillID, userID, agentID string) error

	CreateSkillReviewLog(l *SkillReviewLog) error
	ListSkillReviewLogs(skillID string) ([]SkillReviewLog, error)
}

// SkillListParams are the query parameters for listing skills.
type SkillListParams struct {
	Q        string
	Category string
	Sort     string
	Mine     bool
	Listing  string
}
