package store

import (
	"encoding/json"
	"fmt"
)

// User represents an authenticated account in the system.
type User struct {
	ID           string `json:"id"`
	Username     string `json:"username"`
	Email        string `json:"email"`
	DisplayName  string `json:"display_name"`
	PasswordHash string `json:"-"`
	Room         string `json:"room,omitempty"`
	Role         string `json:"role"`
	Status       string `json:"status"`
	CreatedAt    int64  `json:"created_at"`
	UpdatedAt    int64  `json:"updated_at"`
}

// Role represents the permission level of a user account.
type Role = string

const (
	RoleMember     = "member"
	RoleAdmin      = "admin"
	RoleSuperAdmin = "superadmin"
	RoleDeveloper  = "developer"
)

// Status represents the lifecycle state of a user account.
type Status = string

const (
	StatusActive   = "active"
	StatusDisabled = "disabled"
)

// IsValidRole checks if the given role string is valid.
func IsValidRole(s string) bool {
	switch s {
	case RoleMember, RoleAdmin, RoleSuperAdmin, RoleDeveloper:
		return true
	default:
		return false
	}
}

// IsAdmin reports whether the given role grants admin privileges.
func IsAdmin(role string) bool {
	return role == RoleAdmin || role == RoleSuperAdmin
}

// ValidateUsername checks that a username is safe to use (alphanumeric + dash/underscore, 3-32 chars).
func ValidateUsername(u string) error {
	if len(u) < 3 || len(u) > 32 {
		return fmt.Errorf("username must be 3-32 characters")
	}
	for _, r := range u {
		if !((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_') {
			return fmt.Errorf("username contains invalid characters")
		}
	}
	return nil
}

// Tenant represents an organization/tenant in a multi-tenant setup.
type Tenant struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	OwnerID   string `json:"owner_id"`
	JoinCode  string `json:"join_code"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
}

// ScanLoginSession represents a scan-to-login/auth session.
type ScanLoginSession struct {
	SessionID string `json:"session_id"`
	UserID    string `json:"user_id"`
	Status    string `json:"status"`
	Code      string `json:"code"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
}

// Passkey represents a WebAuthn passkey credential.
type Passkey struct {
	ID              string          `json:"id"`
	UserID          string          `json:"user_id"`
	PublicKey       []byte          `json:"-"`
	AttestationType string          `json:"attestation_type"`
	Transport       json.RawMessage `json:"transport"`
	SignCount       uint32          `json:"sign_count"`
	BackupEligible  uint32          `json:"backup_eligible"`
	BackupState     uint32          `json:"backup_state"`
	CreatedAt       int64           `json:"created_at"`
}

// SkillUsageRecord tracks usage metrics for a skill.
type SkillUsageRecord struct {
	SkillID    string `json:"skill_id"`
	VersionID  string `json:"version_id"`
	UserID     string `json:"user_id"`
	AgentID    string `json:"agent_id"`
	Timestamp  int64  `json:"timestamp"`
	DurationMs int64  `json:"duration_ms"`
}
