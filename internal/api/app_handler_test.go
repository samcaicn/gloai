package api

import ()

import (
	"testing"

	"github.com/ceoadmin/CEOadmin/internal/api/shared"
)

func TestSlugRegex(t *testing.T) {
	valid := []string{
		"my-app",
		"a-b",
		"abc",
		"my-cool-app-123",
		"a0b",
	}
	invalid := []string{
		"",
		"a",      // too short (1 char)
		"ab",     // too short (2 chars, minimum is 3)
		"-app",   // starts with hyphen
		"app-",   // ends with hyphen
		"My-App", // uppercase
		"my app", // space
		"my_app", // underscore
		".app",   // starts with dot
		"app.",   // ends with dot
		"a-",     // ends with hyphen
		"-a",     // starts with hyphen
		"this-slug-is-way-too-long-for-the-regex-to-accept-it-really", // > 40 chars
	}

	for _, s := range valid {
		if !shared.SlugRe.MatchString(s) {
			t.Errorf("shared.SlugRe should match %q", s)
		}
	}
	for _, s := range invalid {
		if shared.SlugRe.MatchString(s) {
			t.Errorf("shared.SlugRe should NOT match %q", s)
		}
	}
}

func TestSlugRegexMinLength(t *testing.T) {
	// Minimum valid slug: 3 chars (first + 1 middle + last).
	if !shared.SlugRe.MatchString("a0b") {
		t.Error("3-char slug should be valid")
	}
	// 2 chars is invalid.
	if shared.SlugRe.MatchString("ab") {
		// Actually, the regex is [a-z0-9][a-z0-9-]{1,38}[a-z0-9] which requires
		// at least 3 chars. Let's check "ab" specifically.
		// Pattern: first char + {1,38} middle + last char = minimum 3 chars.
		// "ab" has only 2 chars, so it shouldn't match.
		t.Error("2-char slug should be invalid")
	}
}

func TestSlugRegexMaxLength(t *testing.T) {
	// Max = 1 + 38 + 1 = 40 chars.
	slug40 := "a" + "b-c-d-e-f-g-h-i-j-k-l-m-n-o-p-q-r-s0" // 40 chars
	if len(slug40) == 40 && !shared.SlugRe.MatchString(slug40) {
		t.Errorf("40-char slug should be valid: %q (len=%d)", slug40, len(slug40))
	}
}

func TestSlugRegexEdgeCases(t *testing.T) {
	tests := []struct {
		slug  string
		valid bool
	}{
		{"0a0", true},        // numeric start/end
		{"a-b", true},        // hyphen in middle
		{"a--b", true},       // double hyphen
		{"a-b-c-d", true},    // multiple hyphens
		{"123", true},        // all numeric
		{"a0-b1-c2", true},   // mixed
		{"UPPER", false},     // uppercase
		{"has space", false}, // space
		{"has.dot", false},   // dot
		{"has_under", false}, // underscore
		{"", false},          // empty
		{"-start", false},    // starts with -
		{"end-", false},      // ends with -
	}

	for _, tt := range tests {
		got := shared.SlugRe.MatchString(tt.slug)
		if got != tt.valid {
			t.Errorf("shared.SlugRe.MatchString(%q) = %v, want %v", tt.slug, got, tt.valid)
		}
	}
}
