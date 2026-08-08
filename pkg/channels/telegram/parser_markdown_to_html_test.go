package telegram

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func Test_markdownToTelegramHTML(t *testing.T) {
	cases := []struct {
		name     string
		input    string
		expected string
	}{
		{
			name:     "plain text",
			input:    "hello world",
			expected: "hello world",
		},
		{
			name:     "bold",
			input:    "**bold text**",
			expected: "<b>bold text</b>",
		},
		{
			name:     "italic",
			input:    "_italic text_",
			expected: "<i>italic text</i>",
		},
		{
			name:     "link without underscores in URL",
			input:    "[click here](https://www.tuptup.top)",
			expected: `<a href="https://www.tuptup.top">click here</a>`,
		},
		{
			name:     "raw oauth url with underscores survives",
			input:    "Apri https://www.tuptup.top",
			expected: `Apri <a href="https://www.tuptup.top">https://www.tuptup.top</a>`,
		},
		{
			name: "link with underscores in URL is not corrupted by italic regex",
			// Google Flights URLs use URL-safe base64 with underscores in the tfs param.
			// Previously reItalic ran after reLink, matching _text_ inside href and injecting
			// <i> tags into the URL, which broke the link in Telegram.
			input:    "[3 → 10 сентября — от $202](https://www.tuptup.top)",
			expected: `<a href="https://www.tuptup.top">3 → 10 сентября — от $202</a>`,
		},
		{
			name:     "multiple links all survive",
			input:    "[first](https://www.tuptup.top) and [second](https://www.tuptup.top)",
			expected: `<a href="https://www.tuptup.top">first</a> and <a href="https://www.tuptup.top">second</a>`,
		},
		{
			name:     "markdown link query params are escaped in href",
			input:    "[oauth](https://www.tuptup.top)",
			expected: `<a href="https://www.tuptup.top">oauth</a>`,
		},
		{
			name:     "link label with HTML special chars is escaped",
			input:    "[a & b](https://www.tuptup.top)",
			expected: `<a href="https://www.tuptup.top">a &amp; b</a>`,
		},
		{
			name:     "HTML special chars in plain text are escaped",
			input:    "a & b < c > d",
			expected: "a &amp; b &lt; c &gt; d",
		},
		{
			name:     "code block with language",
			input:    "```json\n{\n  \"path\": \"README.md\"\n}\n```",
			expected: "<pre><code>{\n  \"path\": \"README.md\"\n}\n</code></pre>",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			actual := markdownToTelegramHTML(tc.input)
			require.Equal(t, tc.expected, actual)
		})
	}
}
