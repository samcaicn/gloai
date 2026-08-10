package memstore

import "github.com/ceoadmin/CEOadmin/internal/store"

// --- SkillStore (stub) ---
//
// The skill marketplace is a dashboard-only domain; the mock server never
// serves it, so these are inert stubs that satisfy store.Store.

func (s *Store) CreateSkill(*store.Skill) (*store.Skill, error) { return nil, errNotImplemented }
func (s *Store) GetSkill(string) (*store.Skill, error)          { return nil, errNotImplemented }
func (s *Store) GetSkillBySlug(string) (*store.Skill, error)    { return nil, errNotImplemented }
func (s *Store) ListSkills(store.SkillQuery) ([]store.Skill, error) {
	return nil, nil
}
func (s *Store) UpdateSkillMeta(string, *store.Skill) error { return errNotImplemented }
func (s *Store) SetSkillListing(string, string, string) error {
	return errNotImplemented
}
func (s *Store) DeleteSkill(string) error { return errNotImplemented }

func (s *Store) CreateSkillVersion(*store.SkillVersion) (*store.SkillVersion, error) {
	return nil, errNotImplemented
}
func (s *Store) GetSkillVersion(string) (*store.SkillVersion, error) { return nil, errNotImplemented }
func (s *Store) ListSkillVersions(string) ([]store.SkillVersion, error) {
	return nil, nil
}
func (s *Store) ListPendingSkillVersions() ([]store.SkillVersion, error) { return nil, nil }
func (s *Store) SupersedePendingSkillVersions(string) error              { return errNotImplemented }
func (s *Store) ReviewSkillVersion(string, string, string, string) error {
	return errNotImplemented
}
func (s *Store) CancelSkillVersion(string) error     { return errNotImplemented }
func (s *Store) IncrementSkillDownload(string) error { return errNotImplemented }

func (s *Store) UpsertSkillRating(*store.SkillRating) error { return errNotImplemented }
func (s *Store) GetSkillRating(string, string) (*store.SkillRating, error) {
	return nil, errNotImplemented
}
func (s *Store) DeleteSkillRating(string, string) error { return errNotImplemented }
func (s *Store) ListSkillRatings(string, int) ([]store.SkillRating, error) {
	return nil, nil
}

func (s *Store) RecordSkillInstall(string, string, string, string) error { return errNotImplemented }
func (s *Store) ListSkillInstalls(string) ([]store.SkillInstall, error)  { return nil, nil }
func (s *Store) DeleteSkillInstall(string, string, string) error         { return errNotImplemented }

func (s *Store) CreateSkillReviewLog(*store.SkillReviewLog) error { return errNotImplemented }
func (s *Store) ListSkillReviewLogs(string) ([]store.SkillReviewLog, error) {
	return nil, nil
}
