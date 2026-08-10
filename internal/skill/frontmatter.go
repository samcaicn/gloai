package skill

import (
	"strconv"
	"strings"
)

// ParseFrontmatter extracts the YAML frontmatter from a SKILL.md document and
// returns the parsed metadata plus the remaining markdown body.
//
// Only the YAML subset used by Agent Skills is supported: scalars, quoted
// scalars, inline flow sequences (`[a, b]`), block sequences (`- item`) and
// block scalars (`|` / `>`). This avoids pulling a full YAML parser into the
// hot path of an untrusted-upload code path.
func ParseFrontmatter(doc string) (Meta, string) {
	doc = strings.TrimPrefix(doc, "\ufeff")
	normalized := strings.ReplaceAll(doc, "\r\n", "\n")

	if !strings.HasPrefix(strings.TrimLeft(normalized, " \t"), "---") {
		return Meta{Raw: map[string]any{}}, normalized
	}

	lines := strings.Split(normalized, "\n")
	// Find the closing delimiter.
	end := -1
	for i := 1; i < len(lines); i++ {
		t := strings.TrimRight(lines[i], " \t")
		if t == "---" || t == "..." {
			end = i
			break
		}
	}
	if end < 0 {
		return Meta{Raw: map[string]any{}}, normalized
	}

	raw := parseYAMLSubset(lines[1:end])
	body := strings.TrimLeft(strings.Join(lines[end+1:], "\n"), "\n")
	return metaFromRaw(raw), body
}

func parseYAMLSubset(lines []string) map[string]any {
	out := map[string]any{}

	for i := 0; i < len(lines); i++ {
		line := lines[i]
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "#") {
			continue
		}
		// Only top-level keys (no leading indentation) start an entry.
		if line != strings.TrimLeft(line, " \t") {
			continue
		}
		colon := strings.Index(line, ":")
		if colon < 0 {
			continue
		}
		key := strings.TrimSpace(line[:colon])
		value := strings.TrimSpace(line[colon+1:])
		if key == "" {
			continue
		}

		switch {
		case value == "|" || value == ">" || value == "|-" || value == ">-" ||
			value == "|+" || value == ">+":
			block, consumed := readIndentedBlock(lines[i+1:])
			i += consumed
			if strings.HasPrefix(value, ">") {
				out[key] = foldLines(block)
			} else {
				out[key] = strings.TrimRight(strings.Join(block, "\n"), "\n")
			}

		case value == "":
			items, consumed := readBlockSequence(lines[i+1:])
			if len(items) > 0 {
				i += consumed
				out[key] = items
				continue
			}
			// Nested mapping (or empty value): keep the raw indented text.
			block, c := readIndentedBlock(lines[i+1:])
			if len(block) > 0 {
				i += c
				out[key] = strings.Join(block, "\n")
			} else {
				out[key] = ""
			}

		case strings.HasPrefix(value, "[") && strings.HasSuffix(value, "]"):
			out[key] = splitFlowSeq(value)

		default:
			out[key] = parseScalar(value)
		}
	}
	return out
}

// readIndentedBlock collects consecutive indented (or blank) lines and returns
// them with the common indentation removed.
func readIndentedBlock(lines []string) ([]string, int) {
	var block []string
	n := 0
	indent := -1
	for _, line := range lines {
		if strings.TrimSpace(line) == "" {
			// A blank line only belongs to the block if more indented content follows.
			block = append(block, "")
			n++
			continue
		}
		lead := len(line) - len(strings.TrimLeft(line, " \t"))
		if lead == 0 {
			break
		}
		if indent < 0 || lead < indent {
			indent = lead
		}
		block = append(block, line)
		n++
	}
	// Trim trailing blank lines that do not belong to the block.
	for len(block) > 0 && strings.TrimSpace(block[len(block)-1]) == "" {
		block = block[:len(block)-1]
		n--
	}
	if indent > 0 {
		for i, l := range block {
			if len(l) >= indent {
				block[i] = l[indent:]
			} else {
				block[i] = strings.TrimSpace(l)
			}
		}
	}
	return block, n
}

// readBlockSequence collects `- item` lines directly following a key.
func readBlockSequence(lines []string) ([]string, int) {
	var items []string
	n := 0
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			break
		}
		if line == strings.TrimLeft(line, " \t") && !strings.HasPrefix(trimmed, "- ") {
			break // next top-level key
		}
		if !strings.HasPrefix(trimmed, "- ") && trimmed != "-" {
			break
		}
		items = append(items, parseScalar(strings.TrimPrefix(trimmed, "-")))
		n++
	}
	return items, n
}

func splitFlowSeq(v string) []string {
	inner := strings.TrimSuffix(strings.TrimPrefix(v, "["), "]")
	var out []string
	for _, part := range strings.Split(inner, ",") {
		if p := parseScalar(part); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func foldLines(block []string) string {
	var sb strings.Builder
	for i, l := range block {
		if i > 0 {
			if strings.TrimSpace(l) == "" {
				sb.WriteString("\n")
				continue
			}
			sb.WriteString(" ")
		}
		sb.WriteString(strings.TrimSpace(l))
	}
	return strings.TrimSpace(sb.String())
}

// parseScalar unwraps an optionally quoted scalar and drops a trailing
// ` # comment`. Text after the closing quote is discarded.
func parseScalar(v string) string {
	v = strings.TrimSpace(v)
	if v == "" {
		return ""
	}
	if q := v[0]; q == '"' || q == '\'' {
		for i := 1; i < len(v); i++ {
			if q == '"' && v[i] == '\\' {
				i++
				continue
			}
			if v[i] == q {
				if q == '"' {
					if unq, err := strconv.Unquote(v[:i+1]); err == nil {
						return unq
					}
				}
				return v[1:i]
			}
		}
		return v[1:] // unterminated quote
	}
	if i := strings.Index(v, " #"); i >= 0 {
		v = strings.TrimSpace(v[:i])
	}
	return v
}

// --- mapping ---

func metaFromRaw(raw map[string]any) Meta {
	m := Meta{Raw: raw}
	m.Name = rawString(raw, "name")
	m.Description = rawString(raw, "description")
	m.Version = rawString(raw, "version")
	m.License = rawString(raw, "license")
	m.Homepage = firstNonEmpty(rawString(raw, "homepage"), rawString(raw, "url"))
	m.Author = rawString(raw, "author")
	m.Icon = rawString(raw, "icon")
	m.Category = rawString(raw, "category")
	m.Tags = firstNonEmptyList(rawList(raw, "tags"), rawList(raw, "keywords"))
	m.AllowedTools = firstNonEmptyList(
		rawList(raw, "allowed-tools"),
		rawList(raw, "allowed_tools"),
		rawList(raw, "allowedTools"),
	)
	return m
}

func rawString(raw map[string]any, key string) string {
	v, ok := raw[key]
	if !ok {
		return ""
	}
	switch t := v.(type) {
	case string:
		return strings.TrimSpace(t)
	case []string:
		return strings.Join(t, ", ")
	}
	return ""
}

func rawList(raw map[string]any, key string) []string {
	v, ok := raw[key]
	if !ok {
		return nil
	}
	switch t := v.(type) {
	case []string:
		return t
	case string:
		if strings.TrimSpace(t) == "" {
			return nil
		}
		var out []string
		for _, p := range strings.Split(t, ",") {
			if p = strings.TrimSpace(p); p != "" {
				out = append(out, p)
			}
		}
		return out
	}
	return nil
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func firstNonEmptyList(vals ...[]string) []string {
	for _, v := range vals {
		if len(v) > 0 {
			return v
		}
	}
	return nil
}
