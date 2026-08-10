package storetest

import (
	"encoding/json"
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/store"
)

// TestSkillLifecycle exercises the skill marketplace through the store layer:
// submission, the per-version review flow, listing transitions, aggregated
// ratings, install counting and the review audit trail.
func TestSkillLifecycle(t *testing.T, s store.Store) {
	owner := mustCreateUser(t, s, "skill_owner", "Skill Owner")
	admin := mustCreateUser(t, s, "skill_admin", "Skill Admin")
	rater := mustCreateUser(t, s, "skill_rater", "Skill Rater")

	var skillID, v1ID, v2ID string

	t.Run("CreateSkill", func(t *testing.T) {
		sk, err := s.CreateSkill(&store.Skill{
			Slug:        "code-review",
			Name:        "Code Review",
			Description: "Reviews diffs",
			Category:    "engineering",
			Tags:        "quality,review",
			OwnerID:     owner.ID,
			Source:      "upload",
		})
		if err != nil {
			t.Fatalf("CreateSkill: %v", err)
		}
		if sk.ID == "" {
			t.Fatal("expected generated ID")
		}
		if sk.Listing != store.SkillListingDraft {
			t.Errorf("listing = %q, want draft", sk.Listing)
		}
		skillID = sk.ID

		got, err := s.GetSkillBySlug("code-review")
		if err != nil {
			t.Fatalf("GetSkillBySlug: %v", err)
		}
		if got.ID != skillID || got.OwnerName != owner.Username {
			t.Errorf("GetSkillBySlug = %+v", got)
		}
	})

	t.Run("SubmitVersionMovesSkillToPending", func(t *testing.T) {
		v, err := s.CreateSkillVersion(&store.SkillVersion{
			SkillID:      skillID,
			Version:      "1.0.0",
			Changelog:    "first release",
			Manifest:     json.RawMessage(`{"name":"code-review"}`),
			Readme:       "# Code Review",
			BundleKey:    "skills/x/1.zip",
			BundleSize:   1234,
			BundleSHA256: "abc",
			Files:        json.RawMessage(`[{"path":"SKILL.md","size":100}]`),
			SubmittedBy:  owner.ID,
		})
		if err != nil {
			t.Fatalf("CreateSkillVersion: %v", err)
		}
		if v.Status != store.SkillVersionPending {
			t.Errorf("status = %q, want pending", v.Status)
		}
		v1ID = v.ID

		sk, _ := s.GetSkill(skillID)
		if sk.Listing != store.SkillListingPending {
			t.Errorf("skill listing = %q, want pending", sk.Listing)
		}

		pending, err := s.ListPendingSkillVersions()
		if err != nil {
			t.Fatalf("ListPendingSkillVersions: %v", err)
		}
		found := false
		for _, p := range pending {
			if p.ID == v1ID {
				found = true
				if p.SkillName != "Code Review" {
					t.Errorf("joined skill name = %q", p.SkillName)
				}
				if p.SubmitterName != owner.Username {
					t.Errorf("joined submitter = %q", p.SubmitterName)
				}
			}
		}
		if !found {
			t.Error("submitted version missing from review queue")
		}
	})

	t.Run("ApproveVersionListsSkill", func(t *testing.T) {
		if err := s.ReviewSkillVersion(v1ID, store.SkillVersionApproved, admin.ID, ""); err != nil {
			t.Fatalf("ReviewSkillVersion: %v", err)
		}
		sk, _ := s.GetSkill(skillID)
		if sk.Listing != store.SkillListingListed {
			t.Errorf("listing = %q, want listed", sk.Listing)
		}
		if sk.LatestVersionID != v1ID {
			t.Errorf("latest_version_id = %q, want %q", sk.LatestVersionID, v1ID)
		}
		if sk.LatestVersion != "1.0.0" {
			t.Errorf("joined latest version = %q", sk.LatestVersion)
		}

		v, _ := s.GetSkillVersion(v1ID)
		if v.Status != store.SkillVersionApproved || v.ReviewedBy != admin.ID || v.ReviewedAt == 0 {
			t.Errorf("version after approve = %+v", v)
		}
	})

	t.Run("RejectKeepsPreviouslyApprovedVersionLive", func(t *testing.T) {
		v, err := s.CreateSkillVersion(&store.SkillVersion{
			SkillID: skillID, Version: "1.1.0", SubmittedBy: owner.ID,
		})
		if err != nil {
			t.Fatalf("CreateSkillVersion: %v", err)
		}
		v2ID = v.ID

		if err := s.ReviewSkillVersion(v2ID, store.SkillVersionRejected, admin.ID, "unsafe script"); err != nil {
			t.Fatalf("ReviewSkillVersion(reject): %v", err)
		}
		sk, _ := s.GetSkill(skillID)
		if sk.Listing != store.SkillListingListed {
			t.Errorf("listing = %q, want listed (approved version still live)", sk.Listing)
		}
		if sk.LatestVersionID != v1ID {
			t.Errorf("latest_version_id = %q, want unchanged %q", sk.LatestVersionID, v1ID)
		}
		rejected, _ := s.GetSkillVersion(v2ID)
		if rejected.RejectReason != "unsafe script" {
			t.Errorf("reject reason = %q", rejected.RejectReason)
		}
	})

	t.Run("SupersedeAndCancelPendingVersions", func(t *testing.T) {
		older, err := s.CreateSkillVersion(&store.SkillVersion{
			SkillID: skillID, Version: "1.2.0", SubmittedBy: owner.ID,
		})
		if err != nil {
			t.Fatalf("CreateSkillVersion: %v", err)
		}
		if err := s.SupersedePendingSkillVersions(skillID); err != nil {
			t.Fatalf("SupersedePendingSkillVersions: %v", err)
		}
		got, _ := s.GetSkillVersion(older.ID)
		if got.Status != store.SkillVersionSuperseded {
			t.Errorf("status = %q, want superseded", got.Status)
		}

		newer, err := s.CreateSkillVersion(&store.SkillVersion{
			SkillID: skillID, Version: "1.3.0", SubmittedBy: owner.ID,
		})
		if err != nil {
			t.Fatalf("CreateSkillVersion: %v", err)
		}
		if err := s.CancelSkillVersion(newer.ID); err != nil {
			t.Fatalf("CancelSkillVersion: %v", err)
		}
		got, _ = s.GetSkillVersion(newer.ID)
		if got.Status != store.SkillVersionCancelled {
			t.Errorf("status = %q, want cancelled", got.Status)
		}
	})

	t.Run("VersionListingVisibility", func(t *testing.T) {
		versions, err := s.ListSkillVersions(skillID)
		if err != nil {
			t.Fatalf("ListSkillVersions: %v", err)
		}
		if len(versions) != 4 {
			t.Errorf("versions = %d, want 4", len(versions))
		}
		// Newest first.
		if versions[0].Version != "1.3.0" {
			t.Errorf("first version = %q, want 1.3.0", versions[0].Version)
		}
	})

	t.Run("Downloads", func(t *testing.T) {
		if err := s.IncrementSkillDownload(v1ID); err != nil {
			t.Fatalf("IncrementSkillDownload: %v", err)
		}
		if err := s.IncrementSkillDownload(v1ID); err != nil {
			t.Fatalf("IncrementSkillDownload: %v", err)
		}
		v, _ := s.GetSkillVersion(v1ID)
		if v.DownloadCount != 2 {
			t.Errorf("download_count = %d, want 2", v.DownloadCount)
		}
	})

	t.Run("RatingsAggregate", func(t *testing.T) {
		if err := s.UpsertSkillRating(&store.SkillRating{
			SkillID: skillID, UserID: owner.ID, Rating: 5, Comment: "great", Version: "1.0.0",
		}); err != nil {
			t.Fatalf("UpsertSkillRating: %v", err)
		}
		if err := s.UpsertSkillRating(&store.SkillRating{
			SkillID: skillID, UserID: rater.ID, Rating: 3, Comment: "ok",
		}); err != nil {
			t.Fatalf("UpsertSkillRating: %v", err)
		}

		sk, _ := s.GetSkill(skillID)
		if sk.RatingCount != 2 {
			t.Fatalf("rating_count = %d, want 2", sk.RatingCount)
		}
		if sk.RatingAvg != 4 {
			t.Errorf("rating_avg = %v, want 4", sk.RatingAvg)
		}

		// Re-rating replaces the previous score instead of adding a row.
		if err := s.UpsertSkillRating(&store.SkillRating{
			SkillID: skillID, UserID: rater.ID, Rating: 1, Comment: "changed my mind",
		}); err != nil {
			t.Fatalf("UpsertSkillRating(update): %v", err)
		}
		sk, _ = s.GetSkill(skillID)
		if sk.RatingCount != 2 || sk.RatingAvg != 3 {
			t.Errorf("after update: count=%d avg=%v, want 2 / 3", sk.RatingCount, sk.RatingAvg)
		}

		mine, err := s.GetSkillRating(skillID, rater.ID)
		if err != nil {
			t.Fatalf("GetSkillRating: %v", err)
		}
		if mine.Rating != 1 || mine.Comment != "changed my mind" || mine.UserName != rater.Username {
			t.Errorf("rating = %+v", mine)
		}

		list, err := s.ListSkillRatings(skillID, 10)
		if err != nil {
			t.Fatalf("ListSkillRatings: %v", err)
		}
		if len(list) != 2 {
			t.Errorf("ratings = %d, want 2", len(list))
		}

		if err := s.DeleteSkillRating(skillID, rater.ID); err != nil {
			t.Fatalf("DeleteSkillRating: %v", err)
		}
		sk, _ = s.GetSkill(skillID)
		if sk.RatingCount != 1 || sk.RatingAvg != 5 {
			t.Errorf("after delete: count=%d avg=%v, want 1 / 5", sk.RatingCount, sk.RatingAvg)
		}
	})

	t.Run("Installs", func(t *testing.T) {
		if err := s.RecordSkillInstall(skillID, v1ID, rater.ID, ""); err != nil {
			t.Fatalf("RecordSkillInstall: %v", err)
		}
		// Same user + agent installing twice must not double count.
		if err := s.RecordSkillInstall(skillID, v1ID, rater.ID, ""); err != nil {
			t.Fatalf("RecordSkillInstall(repeat): %v", err)
		}
		if err := s.RecordSkillInstall(skillID, v1ID, rater.ID, "agent-zhongshu"); err != nil {
			t.Fatalf("RecordSkillInstall(agent): %v", err)
		}

		sk, _ := s.GetSkill(skillID)
		if sk.InstallCount != 2 {
			t.Errorf("install_count = %d, want 2", sk.InstallCount)
		}

		installs, err := s.ListSkillInstalls(rater.ID)
		if err != nil {
			t.Fatalf("ListSkillInstalls: %v", err)
		}
		if len(installs) != 2 {
			t.Fatalf("installs = %d, want 2", len(installs))
		}
		if installs[0].SkillSlug != "code-review" || installs[0].Version != "1.0.0" {
			t.Errorf("install join = %+v", installs[0])
		}

		if err := s.DeleteSkillInstall(skillID, rater.ID, "agent-zhongshu"); err != nil {
			t.Fatalf("DeleteSkillInstall: %v", err)
		}
		sk, _ = s.GetSkill(skillID)
		if sk.InstallCount != 1 {
			t.Errorf("install_count = %d, want 1", sk.InstallCount)
		}
	})

	t.Run("ReviewAuditTrail", func(t *testing.T) {
		for _, l := range []store.SkillReviewLog{
			{SkillID: skillID, VersionID: v1ID, Action: store.SkillActionSubmit, ActorID: owner.ID, Version: "1.0.0"},
			{SkillID: skillID, VersionID: v1ID, Action: store.SkillActionApprove, ActorID: admin.ID, Version: "1.0.0"},
			{SkillID: skillID, VersionID: v2ID, Action: store.SkillActionReject, ActorID: admin.ID, Reason: "unsafe script"},
		} {
			log := l
			if err := s.CreateSkillReviewLog(&log); err != nil {
				t.Fatalf("CreateSkillReviewLog: %v", err)
			}
		}
		logs, err := s.ListSkillReviewLogs(skillID)
		if err != nil {
			t.Fatalf("ListSkillReviewLogs: %v", err)
		}
		if len(logs) != 3 {
			t.Fatalf("logs = %d, want 3", len(logs))
		}
		var sawReject bool
		for _, l := range logs {
			if l.Action == store.SkillActionReject && l.Reason == "unsafe script" && l.ActorName == admin.Username {
				sawReject = true
			}
		}
		if !sawReject {
			t.Error("reject entry missing from audit trail")
		}
	})

	t.Run("ListSkillsFilters", func(t *testing.T) {
		other, err := s.CreateSkill(&store.Skill{
			Slug: "note-taker", Name: "Note Taker", Description: "Takes notes",
			Category: "writing", OwnerID: admin.ID,
		})
		if err != nil {
			t.Fatalf("CreateSkill: %v", err)
		}

		listed, err := s.ListSkills(store.SkillQuery{Listing: store.SkillListingListed})
		if err != nil {
			t.Fatalf("ListSkills(listed): %v", err)
		}
		for _, sk := range listed {
			if sk.ID == other.ID {
				t.Error("draft skill leaked into listed results")
			}
		}

		mine, err := s.ListSkills(store.SkillQuery{OwnerID: admin.ID})
		if err != nil {
			t.Fatalf("ListSkills(owner): %v", err)
		}
		if len(mine) != 1 || mine[0].ID != other.ID {
			t.Errorf("owner filter = %+v", mine)
		}

		byCategory, err := s.ListSkills(store.SkillQuery{Category: "writing"})
		if err != nil {
			t.Fatalf("ListSkills(category): %v", err)
		}
		if len(byCategory) != 1 {
			t.Errorf("category filter = %d results, want 1", len(byCategory))
		}

		search, err := s.ListSkills(store.SkillQuery{Search: "note"})
		if err != nil {
			t.Fatalf("ListSkills(search): %v", err)
		}
		if len(search) != 1 || search[0].Slug != "note-taker" {
			t.Errorf("search = %+v", search)
		}

		byRating, err := s.ListSkills(store.SkillQuery{Sort: "rating", Limit: 10})
		if err != nil {
			t.Fatalf("ListSkills(rating): %v", err)
		}
		if len(byRating) == 0 || byRating[0].Slug != "code-review" {
			t.Errorf("rating sort = %+v", byRating)
		}

		if err := s.SetSkillListing(other.ID, store.SkillListingUnlisted, "spam"); err != nil {
			t.Fatalf("SetSkillListing: %v", err)
		}
		got, _ := s.GetSkill(other.ID)
		if got.Listing != store.SkillListingUnlisted || got.RejectReason != "spam" {
			t.Errorf("after unlist = %+v", got)
		}

		if err := s.DeleteSkill(other.ID); err != nil {
			t.Fatalf("DeleteSkill: %v", err)
		}
		if _, err := s.GetSkill(other.ID); err == nil {
			t.Error("expected error after delete")
		}
	})

	t.Run("DeleteCascadesVersions", func(t *testing.T) {
		sk, err := s.CreateSkill(&store.Skill{Slug: "temp-skill", Name: "Temp", OwnerID: owner.ID})
		if err != nil {
			t.Fatalf("CreateSkill: %v", err)
		}
		v, err := s.CreateSkillVersion(&store.SkillVersion{SkillID: sk.ID, Version: "0.1.0", SubmittedBy: owner.ID})
		if err != nil {
			t.Fatalf("CreateSkillVersion: %v", err)
		}
		if err := s.DeleteSkill(sk.ID); err != nil {
			t.Fatalf("DeleteSkill: %v", err)
		}
		if _, err := s.GetSkillVersion(v.ID); err == nil {
			t.Error("expected version to be removed with its skill")
		}
	})
}
