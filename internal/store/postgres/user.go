package postgres

import (
	"github.com/ceoadmin/CEOadmin/internal/store"
)

func (db *DB) CreateUser(username, displayName string) (*store.User, error) {
	count, err := db.UserCount()
	if err != nil {
		return nil, err
	}
	role := store.RoleMember
	if count == 0 {
		role = store.RoleSuperAdmin
	}
	return db.CreateUserFull(username, "", displayName, "", role)
}

func (db *DB) CreateUserFull(username, email, displayName, passwordHash, role string) (*store.User, error) {
	_, err := db.Exec(`INSERT INTO users (id, username, email, display_name, password_hash, role, status, created_at, updated_at) VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, $6, $7, $8)`,
		username, email, displayName, passwordHash, role, store.StatusActive, db.now(), db.now())
	if err != nil {
		return nil, err
	}
	return db.GetUserByUsername(username)
}

func (db *DB) GetUserByID(id string) (*store.User, error) {
	var u store.User
	err := db.QueryRow(`SELECT id, username, email, display_name, password_hash, role, status, created_at, updated_at FROM users WHERE id = $1`, id).
		Scan(&u.ID, &u.Username, &u.Email, &u.DisplayName, &u.PasswordHash, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &u, nil
}

func (db *DB) GetUserByUsername(username string) (*store.User, error) {
	return db.getUser("username = $1", username)
}

func (db *DB) GetUserByEmail(email string) (*store.User, error) {
	return db.getUser("email = $1", email)
}

func (db *DB) getUser(where string, arg string) (*store.User, error) {
	var u store.User
	err := db.QueryRow(`SELECT id, username, email, display_name, password_hash, role, status, created_at, updated_at FROM users WHERE `+where, arg).
		Scan(&u.ID, &u.Username, &u.Email, &u.DisplayName, &u.PasswordHash, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &u, nil
}

func (db *DB) UserCount() (int, error) {
	var n int
	err := db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n)
	return n, err
}

func (db *DB) UpdateUser(user *store.User) error {
	_, err := db.Exec(`UPDATE users SET username=$1, email=$2, display_name=$3, password_hash=$4, role=$5, status=$6, updated_at=$7 WHERE id=$8`,
		user.Username, user.Email, user.DisplayName, user.PasswordHash, user.Role, user.Status, db.now(), user.ID)
	return err
}

func (db *DB) UpdateUserProfile(id, displayName, email string) error {
	_, err := db.Exec(`UPDATE users SET display_name=$1, email=$2, updated_at=$3 WHERE id=$4`, displayName, email, db.now(), id)
	return err
}

func (db *DB) UpdateUserPassword(id, passwordHash string) error {
	_, err := db.Exec(`UPDATE users SET password_hash=$1, updated_at=$2 WHERE id=$3`, passwordHash, db.now(), id)
	return err
}

func (db *DB) UpdateUserUsername(id, username string) error {
	_, err := db.Exec(`UPDATE users SET username=$1, updated_at=$2 WHERE id=$3`, username, db.now(), id)
	return err
}

func (db *DB) UpdateUserRole(id, role string) error {
	_, err := db.Exec(`UPDATE users SET role=$1, updated_at=$2 WHERE id=$3`, role, db.now(), id)
	return err
}

func (db *DB) UpdateUserStatus(id, status string) error {
	_, err := db.Exec(`UPDATE users SET status=$1, updated_at=$2 WHERE id=$3`, status, db.now(), id)
	return err
}

func (db *DB) ListUsers() ([]*store.User, error) {
	rows, err := db.Query(`SELECT id, username, email, display_name, password_hash, role, status, created_at, updated_at FROM users ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var users []*store.User
	for rows.Next() {
		var u store.User
		if err := rows.Scan(&u.ID, &u.Username, &u.Email, &u.DisplayName, &u.PasswordHash, &u.Role, &u.Status, &u.CreatedAt, &u.UpdatedAt); err != nil {
			return nil, err
		}
		users = append(users, &u)
	}
	return users, nil
}

func (db *DB) DeleteUser(id string) error {
	_, err := db.Exec(`DELETE FROM users WHERE id = $1`, id)
	return err
}

func (db *DB) FindTenantByJoinCode(code string) (*store.Tenant, error) {
	var t store.Tenant
	err := db.QueryRow(`SELECT id, name, owner_id, join_code, created_at, updated_at FROM tenants WHERE join_code = $1`, code).
		Scan(&t.ID, &t.Name, &t.OwnerID, &t.JoinCode, &t.CreatedAt, &t.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &t, nil
}

func (db *DB) CreatePasskey(p *store.Passkey) error {
	_, err := db.Exec(`INSERT INTO passkeys (id, user_id, public_key, attestation_type, transport, sign_count, backup_eligible, backup_state, created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,now())`,
		p.ID, p.UserID, p.PublicKey, p.AttestationType, p.Transport, p.SignCount, p.BackupEligible, p.BackupState)
	return err
}

func (db *DB) GetPasskey(id string) (*store.Passkey, error) {
	var p store.Passkey
	err := db.QueryRow(`SELECT id, user_id, public_key, attestation_type, transport, sign_count, backup_eligible, backup_state, created_at FROM passkeys WHERE id = $1`, id).
		Scan(&p.ID, &p.UserID, &p.PublicKey, &p.AttestationType, &p.Transport, &p.SignCount, &p.BackupEligible, &p.BackupState, &p.CreatedAt)
	if err != nil {
		return nil, err
	}
	return &p, nil
}

func (db *DB) ListPasskeys(userID string) ([]store.Passkey, error) {
	rows, err := db.Query(`SELECT id, user_id, public_key, attestation_type, transport, sign_count, backup_eligible, backup_state, created_at FROM passkeys WHERE user_id = $1 ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var passkeys []store.Passkey
	for rows.Next() {
		var p store.Passkey
		if err := rows.Scan(&p.ID, &p.UserID, &p.PublicKey, &p.AttestationType, &p.Transport, &p.SignCount, &p.BackupEligible, &p.BackupState, &p.CreatedAt); err != nil {
			return nil, err
		}
		passkeys = append(passkeys, p)
	}
	return passkeys, nil
}

func (db *DB) DeletePasskey(id string) error {
	_, err := db.Exec(`DELETE FROM passkeys WHERE id = $1`, id)
	return err
}

func (db *DB) CreateScanLoginSession(sessionID, userID string) error {
	_, err := db.Exec(`INSERT INTO scan_login_sessions (session_id, user_id, status, code, created_at, updated_at) VALUES ($1,$2,'pending',$3,now(),now())`,
		sessionID, userID, "")
	return err
}

func (db *DB) GetScanLoginSession(sessionID string) (*store.ScanLoginSession, error) {
	var s store.ScanLoginSession
	err := db.QueryRow(`SELECT session_id, user_id, status, code, created_at, updated_at FROM scan_login_sessions WHERE session_id = $1`, sessionID).
		Scan(&s.SessionID, &s.UserID, &s.Status, &s.Code, &s.CreatedAt, &s.UpdatedAt)
	if err != nil {
		return nil, err
	}
	return &s, nil
}

func (db *DB) UpdateScanLoginSession(session *store.ScanLoginSession) error {
	_, err := db.Exec(`UPDATE scan_login_sessions SET user_id=$1, status=$2, code=$3, updated_at=$4 WHERE session_id=$5`,
		session.UserID, session.Status, session.Code, db.now(), session.SessionID)
	return err
}

func (db *DB) DeleteScanLoginSession(sessionID string) error {
	_, err := db.Exec(`DELETE FROM scan_login_sessions WHERE session_id = $1`, sessionID)
	return err
}
