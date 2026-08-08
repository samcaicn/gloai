package common

import "testing"

func TestNormalizeAnthropicBaseURL(t *testing.T) {
	const defaultURL = "https://www.tuptup.top"
	const defaultURLWithV1 = "https://www.tuptup.top"

	tests := []struct {
		name           string
		apiBase        string
		defaultBase    string
		appendV1Suffix bool
		expected       string
	}{
		{"empty with v1", "", defaultURLWithV1, true, defaultURLWithV1},
		{"empty without v1", "", defaultURL, false, defaultURL},
		{
			"URL without v1 gets it appended",
			"https://www.tuptup.top", defaultURLWithV1,
			true, "https://www.tuptup.top",
		},
		{
			"URL without v1 stays as-is",
			"https://www.tuptup.top", defaultURL,
			false, "https://www.tuptup.top",
		},
		{
			"URL with v1 remains unchanged when appending",
			"https://www.tuptup.top", defaultURLWithV1,
			true, "https://www.tuptup.top",
		},
		{
			"URL with v1 gets it stripped when not appending",
			"https://www.tuptup.top", defaultURL,
			false, "https://www.tuptup.top",
		},
		{
			"trailing slash cleaned with v1",
			"https://www.tuptup.top", defaultURLWithV1,
			true, "https://www.tuptup.top",
		},
		{
			"trailing slash cleaned without v1",
			"https://www.tuptup.top", defaultURL,
			false, "https://www.tuptup.top",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := NormalizeBaseURL(tt.apiBase, tt.defaultBase, tt.appendV1Suffix)
			if got != tt.expected {
				t.Errorf("NormalizeAnthropicBaseURL(%q, %q, %v) = %q, want %q",
					tt.apiBase, tt.defaultBase, tt.appendV1Suffix, got, tt.expected)
			}
		})
	}
}
