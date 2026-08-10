package skill

import (
	"archive/zip"
	"bytes"
	"net/url"
	"strings"
	"testing"
)

func zipOf(t *testing.T, files map[string]string) []byte {
	t.Helper()
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	for name, body := range files {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatalf("zip create %s: %v", name, err)
		}
		if _, err := w.Write([]byte(body)); err != nil {
			t.Fatalf("zip write %s: %v", name, err)
		}
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("zip close: %v", err)
	}
	return buf.Bytes()
}

const validSkillMD = `---
name: code-review
description: Reviews code changes and reports risky diffs.
version: 1.2.0
license: MIT
allowed-tools: Read, Grep, Bash
tags: [engineering, quality]
---

# Code Review

Do the review.
`

func TestParseValidBundle(t *testing.T) {
	data := zipOf(t, map[string]string{
		"SKILL.md":            validSkillMD,
		"scripts/lint.sh":     "#!/bin/sh\necho lint\n",
		"references/rules.md": "rules",
	})

	b, err := Parse(data)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if b.Meta.Name != "code-review" {
		t.Errorf("name = %q, want code-review", b.Meta.Name)
	}
	if b.Meta.Version != "1.2.0" {
		t.Errorf("version = %q, want 1.2.0", b.Meta.Version)
	}
	if b.Meta.License != "MIT" {
		t.Errorf("license = %q", b.Meta.License)
	}
	if got := strings.Join(b.Meta.AllowedTools, ","); got != "Read,Grep,Bash" {
		t.Errorf("allowed-tools = %q", got)
	}
	if got := strings.Join(b.Meta.Tags, ","); got != "engineering,quality" {
		t.Errorf("tags = %q", got)
	}
	if len(b.Files) != 3 {
		t.Errorf("files = %d, want 3", len(b.Files))
	}
	if b.SHA256 == "" || b.Size == 0 {
		t.Error("expected checksum and size to be computed")
	}
	if !strings.Contains(b.Body, "# Code Review") {
		t.Errorf("body missing markdown: %q", b.Body)
	}

	// The repacked archive must be readable and rooted at SKILL.md.
	zr, err := zip.NewReader(bytes.NewReader(b.Data), int64(len(b.Data)))
	if err != nil {
		t.Fatalf("repacked zip unreadable: %v", err)
	}
	var found bool
	for _, f := range zr.File {
		if f.Name == "SKILL.md" {
			found = true
		}
	}
	if !found {
		t.Error("repacked bundle has no root SKILL.md")
	}
}

func TestParseStripsTopLevelDirectory(t *testing.T) {
	data := zipOf(t, map[string]string{
		"code-review/SKILL.md":       validSkillMD,
		"code-review/scripts/run.sh": "echo hi",
		"code-review/__MACOSX/junk":  "junk",
		"code-review/dir/.DS_Store":  "junk",
	})

	b, err := Parse(data)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	for _, f := range b.Files {
		if strings.HasPrefix(f.Path, "code-review/") {
			t.Errorf("top-level directory not stripped: %s", f.Path)
		}
		if strings.Contains(f.Path, "__MACOSX") || strings.Contains(f.Path, ".DS_Store") {
			t.Errorf("junk entry kept: %s", f.Path)
		}
	}
	if len(b.Files) != 2 {
		t.Errorf("files = %v, want SKILL.md + scripts/run.sh", b.Files)
	}
}

func TestParseRejects(t *testing.T) {
	tests := []struct {
		name  string
		files map[string]string
		want  string
	}{
		{
			name:  "missing SKILL.md",
			files: map[string]string{"readme.md": "nope"},
			want:  "SKILL.md",
		},
		{
			name:  "path traversal",
			files: map[string]string{"SKILL.md": validSkillMD, "../evil.sh": "rm -rf /"},
			want:  "非法路径",
		},
		{
			name:  "missing name",
			files: map[string]string{"SKILL.md": "---\ndescription: no name here\n---\nbody"},
			want:  "name",
		},
		{
			name:  "missing description",
			files: map[string]string{"SKILL.md": "---\nname: foo\n---\nbody"},
			want:  "description",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Parse(zipOf(t, tc.files))
			if err == nil {
				t.Fatal("expected error, got nil")
			}
			if !strings.Contains(err.Error(), tc.want) {
				t.Errorf("error = %q, want it to mention %q", err, tc.want)
			}
		})
	}
}

func TestParseRejectsEmptyAndOversized(t *testing.T) {
	if _, err := Parse(nil); err == nil {
		t.Error("expected error for empty input")
	}
	if _, err := Parse(bytes.Repeat([]byte("x"), MaxBundleSize+1)); err == nil {
		t.Error("expected error for oversized input")
	}
}

func TestBuildFromMarkdown(t *testing.T) {
	data, err := BuildFromMarkdown([]byte(validSkillMD))
	if err != nil {
		t.Fatalf("BuildFromMarkdown: %v", err)
	}
	b, err := Parse(data)
	if err != nil {
		t.Fatalf("Parse(single-file bundle): %v", err)
	}
	if len(b.Files) != 1 || b.Files[0].Path != "SKILL.md" {
		t.Errorf("files = %v, want [SKILL.md]", b.Files)
	}
}

func TestParseFrontmatterBlockScalars(t *testing.T) {
	doc := `---
name: writer
description: |
  Line one.
  Line two.
category: writing
allowed-tools:
  - Read
  - Write
folded: >
  a
  b
---
Body here.
`
	meta, body := ParseFrontmatter(doc)
	if meta.Name != "writer" {
		t.Errorf("name = %q", meta.Name)
	}
	if meta.Description != "Line one.\nLine two." {
		t.Errorf("description = %q", meta.Description)
	}
	if meta.Category != "writing" {
		t.Errorf("category = %q", meta.Category)
	}
	if strings.Join(meta.AllowedTools, ",") != "Read,Write" {
		t.Errorf("allowed-tools = %v", meta.AllowedTools)
	}
	if got, _ := meta.Raw["folded"].(string); got != "a b" {
		t.Errorf("folded = %q, want %q", got, "a b")
	}
	if strings.TrimSpace(body) != "Body here." {
		t.Errorf("body = %q", body)
	}
}

func TestParseFrontmatterNoFrontmatter(t *testing.T) {
	meta, body := ParseFrontmatter("# Just markdown\n")
	if meta.Name != "" {
		t.Errorf("name = %q, want empty", meta.Name)
	}
	if !strings.Contains(body, "Just markdown") {
		t.Errorf("body = %q", body)
	}
}

func TestParseFrontmatterQuotedAndComments(t *testing.T) {
	doc := "---\n# a comment\nname: \"my-skill\"\ndescription: 'quoted desc'  # trailing\nicon: 🧠\n---\nx"
	meta, _ := ParseFrontmatter(doc)
	if meta.Name != "my-skill" {
		t.Errorf("name = %q", meta.Name)
	}
	if meta.Description != "quoted desc" {
		t.Errorf("description = %q", meta.Description)
	}
	if meta.Icon != "🧠" {
		t.Errorf("icon = %q", meta.Icon)
	}
}

func TestSlugify(t *testing.T) {
	tests := map[string]string{
		"Code Review":      "code-review",
		"my_skill":         "my-skill",
		"  Hello--World  ": "hello-world",
		"技能 Foo":           "foo",
		"UPPER":            "upper",
	}
	for in, want := range tests {
		if got := Slugify(in); got != want {
			t.Errorf("Slugify(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestValidSlug(t *testing.T) {
	valid := []string{"code-review", "a1", "skill-123"}
	invalid := []string{"", "a", "-bad", "Bad", "with space", "under_score"}
	for _, s := range valid {
		if !ValidSlug(s) {
			t.Errorf("ValidSlug(%q) = false, want true", s)
		}
	}
	for _, s := range invalid {
		if ValidSlug(s) {
			t.Errorf("ValidSlug(%q) = true, want false", s)
		}
	}
}

func TestExtractSubdir(t *testing.T) {
	archive := zipOf(t, map[string]string{
		"repo-main/README.md":               "root readme",
		"repo-main/skills/foo/SKILL.md":     validSkillMD,
		"repo-main/skills/foo/scripts/a.sh": "echo a",
		"repo-main/skills/bar/SKILL.md":     "other skill",
	})

	out, err := extractSubdir(archive, "skills/foo")
	if err != nil {
		t.Fatalf("extractSubdir: %v", err)
	}
	b, err := Parse(out)
	if err != nil {
		t.Fatalf("Parse(extracted): %v", err)
	}
	if len(b.Files) != 2 {
		t.Errorf("files = %v, want SKILL.md + scripts/a.sh", b.Files)
	}
	if b.Meta.Name != "code-review" {
		t.Errorf("name = %q", b.Meta.Name)
	}

	if _, err := extractSubdir(archive, "skills/missing"); err == nil {
		t.Error("expected error for missing subdir")
	}
}

func TestParseGitHubURLs(t *testing.T) {
	tests := []struct {
		url            string
		wantOwner      string
		wantRepo       string
		wantRef        string
		wantPath       string
		wantFile       bool
		wantRecognized bool
	}{
		{"https://github.com/o/r/tree/main/skills/foo", "o", "r", "main", "skills/foo", false, true},
		{"https://github.com/o/r/blob/main/skills/foo/SKILL.md", "o", "r", "main", "skills/foo/SKILL.md", true, true},
		{"https://raw.githubusercontent.com/o/r/main/skills/foo/SKILL.md", "o", "r", "main", "skills/foo/SKILL.md", true, true},
		{"https://github.com/o/r", "o", "r", "HEAD", "", false, true},
		{"https://example.com/foo.zip", "", "", "", "", false, false},
	}
	for _, tc := range tests {
		u := mustParseURL(t, tc.url)
		got, ok := parseGitHub(u)
		if ok != tc.wantRecognized {
			t.Fatalf("parseGitHub(%s) recognized = %v, want %v", tc.url, ok, tc.wantRecognized)
		}
		if !ok {
			continue
		}
		if got.Owner != tc.wantOwner || got.Repo != tc.wantRepo || got.Ref != tc.wantRef ||
			got.Path != tc.wantPath || got.IsFile != tc.wantFile {
			t.Errorf("parseGitHub(%s) = %+v", tc.url, got)
		}
	}
}

func mustParseURL(t *testing.T, raw string) *url.URL {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatalf("url.Parse(%q): %v", raw, err)
	}
	return u
}
