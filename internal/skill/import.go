package skill

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// MaxArchiveSize caps repository archives, which are downloaded whole before a
// sub-directory is extracted. It is deliberately larger than MaxBundleSize —
// the extracted skill still has to fit within MaxBundleSize.
const MaxArchiveSize = 50 << 20

// Source describes where an imported bundle came from.
type Source struct {
	URL    string // canonical source URL
	Kind   string // github | url
	Commit string // resolved commit SHA when known
}

var httpClient = &http.Client{Timeout: 60 * time.Second}

// FetchBundle downloads a skill from a remote location and returns raw zip
// bytes ready for Parse. Supported inputs:
//
//   - GitHub directory:  https://github.com/o/r/tree/main/skills/foo
//   - GitHub file:       https://github.com/o/r/blob/main/skills/foo/SKILL.md
//   - GitHub repository: https://github.com/o/r
//   - Direct zip:        https://example.com/foo.zip
//   - Bare SKILL.md:     https://example.com/foo/SKILL.md (wrapped into a zip)
func FetchBundle(ctx context.Context, rawURL string) ([]byte, Source, error) {
	src := Source{URL: strings.TrimSpace(rawURL), Kind: "url"}
	if src.URL == "" {
		return nil, src, fmt.Errorf("缺少来源地址")
	}
	u, err := url.Parse(src.URL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, src, fmt.Errorf("来源地址必须是 http(s) URL")
	}

	if gh, ok := parseGitHub(u); ok {
		src.Kind = "github"
		return fetchGitHub(ctx, gh, src)
	}

	data, ct, err := httpGet(ctx, src.URL, MaxBundleSize)
	if err != nil {
		return nil, src, err
	}
	if isZip(data, ct, u.Path) {
		return data, src, nil
	}
	if looksMarkdown(ct, u.Path) {
		zipped, err := BuildFromMarkdown(data)
		return zipped, src, err
	}
	return nil, src, fmt.Errorf("无法识别的来源内容类型：%s（需要 .zip 或 SKILL.md）", ct)
}

// --- GitHub ---

type githubRef struct {
	Owner, Repo, Ref, Path string
	IsFile                 bool
}

func parseGitHub(u *url.URL) (githubRef, bool) {
	host := strings.ToLower(u.Host)
	if host != "github.com" && host != "www.github.com" && host != "raw.githubusercontent.com" {
		return githubRef{}, false
	}
	parts := strings.Split(strings.Trim(u.Path, "/"), "/")

	if host == "raw.githubusercontent.com" {
		// /owner/repo/ref/path...
		if len(parts) < 4 {
			return githubRef{}, false
		}
		return githubRef{Owner: parts[0], Repo: parts[1], Ref: parts[2],
			Path: strings.Join(parts[3:], "/"), IsFile: true}, true
	}

	if len(parts) < 2 {
		return githubRef{}, false
	}
	g := githubRef{Owner: parts[0], Repo: strings.TrimSuffix(parts[1], ".git")}
	if len(parts) == 2 {
		g.Ref = "HEAD"
		return g, true
	}
	switch parts[2] {
	case "tree", "blob":
		if len(parts) < 4 {
			return githubRef{}, false
		}
		g.Ref = parts[3]
		g.Path = strings.Join(parts[4:], "/")
		g.IsFile = parts[2] == "blob"
		return g, true
	}
	return githubRef{}, false
}

func fetchGitHub(ctx context.Context, g githubRef, src Source) ([]byte, Source, error) {
	src.Commit = githubCommit(ctx, g)

	if g.IsFile {
		raw := fmt.Sprintf("https://raw.githubusercontent.com/%s/%s/%s/%s", g.Owner, g.Repo, g.Ref, g.Path)
		data, ct, err := httpGet(ctx, raw, MaxBundleSize)
		if err != nil {
			return nil, src, err
		}
		if isZip(data, ct, g.Path) {
			return data, src, nil
		}
		if !looksMarkdown(ct, g.Path) {
			return nil, src, fmt.Errorf("GitHub 文件不是 Markdown 技能定义：%s", g.Path)
		}
		zipped, err := BuildFromMarkdown(data)
		return zipped, src, err
	}

	archiveURL := fmt.Sprintf("https://codeload.github.com/%s/%s/zip/%s", g.Owner, g.Repo, g.Ref)
	archive, _, err := httpGet(ctx, archiveURL, MaxArchiveSize)
	if err != nil {
		return nil, src, fmt.Errorf("下载仓库归档失败：%w", err)
	}
	sub, err := extractSubdir(archive, g.Path)
	if err != nil {
		return nil, src, err
	}
	return sub, src, nil
}

func githubCommit(ctx context.Context, g githubRef) string {
	api := fmt.Sprintf("https://api.github.com/repos/%s/%s/commits?sha=%s&per_page=1", g.Owner, g.Repo, url.QueryEscape(g.Ref))
	if g.Path != "" {
		api += "&path=" + url.QueryEscape(g.Path)
	}
	data, _, err := httpGet(ctx, api, 1<<20)
	if err != nil {
		return ""
	}
	var commits []struct {
		SHA string `json:"sha"`
	}
	if err := json.Unmarshal(data, &commits); err != nil || len(commits) == 0 {
		return ""
	}
	return commits[0].SHA
}

// extractSubdir repacks the entries of a repository archive that live under
// subdir (relative to the archive's single top-level directory).
func extractSubdir(archive []byte, subdir string) ([]byte, error) {
	zr, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
	if err != nil {
		return nil, fmt.Errorf("仓库归档解析失败：%w", err)
	}

	var names []string
	for _, f := range zr.File {
		if strings.HasSuffix(f.Name, "/") {
			continue
		}
		if n := normalizeName(f.Name); n != "" {
			names = append(names, n)
		}
	}
	top := commonTopDir(names)
	prefix := top
	if subdir != "" {
		if prefix != "" {
			prefix += "/"
		}
		prefix += strings.Trim(subdir, "/")
	}
	if prefix != "" {
		prefix += "/"
	}

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	total := 0
	count := 0
	for _, f := range zr.File {
		if strings.HasSuffix(f.Name, "/") {
			continue
		}
		name := normalizeName(f.Name)
		if name == "" || !strings.HasPrefix(name, prefix) {
			continue
		}
		rel := strings.TrimPrefix(name, prefix)
		if rel == "" || strings.Contains(rel, "..") {
			continue
		}
		if count >= MaxFiles {
			return nil, fmt.Errorf("目录下文件过多，上限 %d 个", MaxFiles)
		}
		rc, err := f.Open()
		if err != nil {
			return nil, err
		}
		body, err := io.ReadAll(io.LimitReader(rc, MaxUncompressed+1))
		rc.Close()
		if err != nil {
			return nil, err
		}
		total += len(body)
		if total > MaxUncompressed {
			return nil, fmt.Errorf("目录解压后过大，上限 %d 字节", MaxUncompressed)
		}
		w, err := zw.Create(rel)
		if err != nil {
			return nil, err
		}
		if _, err := w.Write(body); err != nil {
			return nil, err
		}
		count++
	}
	if err := zw.Close(); err != nil {
		return nil, err
	}
	if count == 0 {
		return nil, fmt.Errorf("在来源地址下没有找到任何文件")
	}
	return buf.Bytes(), nil
}

// --- http helpers ---

func httpGet(ctx context.Context, rawURL string, maxBytes int) ([]byte, string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, "", err
	}
	req.Header.Set("User-Agent", "CEOadmin-SkillMarketplace/1.0")
	req.Header.Set("Accept", "*/*")

	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, "", fmt.Errorf("请求失败：%w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, "", fmt.Errorf("来源返回 HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, int64(maxBytes)+1))
	if err != nil {
		return nil, "", fmt.Errorf("读取响应失败：%w", err)
	}
	if len(data) > maxBytes {
		return nil, "", fmt.Errorf("来源内容超过 %d 字节上限", maxBytes)
	}
	return data, resp.Header.Get("Content-Type"), nil
}

func isZip(data []byte, contentType, path string) bool {
	if len(data) >= 4 && data[0] == 'P' && data[1] == 'K' && (data[2] == 3 || data[2] == 5 || data[2] == 7) {
		return true
	}
	return strings.Contains(contentType, "zip") || strings.HasSuffix(strings.ToLower(path), ".zip")
}

func looksMarkdown(contentType, path string) bool {
	lower := strings.ToLower(path)
	if strings.HasSuffix(lower, ".md") || strings.HasSuffix(lower, ".markdown") {
		return true
	}
	return strings.Contains(contentType, "markdown") || strings.Contains(contentType, "text/plain")
}
