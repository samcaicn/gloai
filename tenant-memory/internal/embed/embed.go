package embed

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net/http"
	"strings"
	"time"
	"unicode"
)

// Embedder 把文本转为向量。
type Embedder interface {
	Embed(texts []string) ([][]float64, error)
}

// RemoteEmbedder 调用 OpenAI 兼容的 /embeddings 端点（可选，配了 EMBED_API_KEY 才启用）。
type RemoteEmbedder struct {
	BaseURL string
	APIKey  string
	Model   string
	HTTP    *http.Client
}

type embRequest struct {
	Model string   `json:"model"`
	Input []string `json:"input"`
}

type embResponse struct {
	Data  []struct{ Embedding []float64 `json:"embedding"` } `json:"data"`
	Error *struct{ Message string `json:"message"` }          `json:"error"`
}

// ErrNoKey 表示未配置远程 embedding 密钥。
var ErrNoKey = fmt.Errorf("remote embedder requires EMBED_API_KEY")

// Embed 批量向量化。
func (r *RemoteEmbedder) Embed(texts []string) ([][]float64, error) {
	if r.APIKey == "" {
		return nil, ErrNoKey
	}
	body, _ := json.Marshal(embRequest{Model: r.Model, Input: texts})
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, r.BaseURL+"/embeddings", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+r.APIKey)
	resp, err := r.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("embed http %d: %s", resp.StatusCode, string(data))
	}
	var er embResponse
	if err := json.Unmarshal(data, &er); err != nil {
		return nil, err
	}
	if er.Error != nil && er.Error.Message != "" {
		return nil, fmt.Errorf("embed error: %s", er.Error.Message)
	}
	out := make([][]float64, 0, len(er.Data))
	for _, d := range er.Data {
		out = append(out, d.Embedding)
	}
	return out, nil
}

// LocalEmbedder 本地 TF-IDF 向量（CJK 按字、英文按词），零依赖、可离线。
// 用于没有 embedding 端点时的语义近似召回。
type LocalEmbedder struct{}

// Embed 对一批文本做 TF-IDF 向量化并 L2 归一化（便于余弦相似度）。
func (l *LocalEmbedder) Embed(texts []string) ([][]float64, error) {
	docs := make([][]string, len(texts))
	for i, t := range texts {
		docs[i] = tokenize(t)
	}
	df := map[string]int{}
	for _, d := range docs {
		seen := map[string]bool{}
		for _, w := range d {
			if !seen[w] {
				df[w]++
				seen[w] = true
			}
		}
	}
	n := float64(len(docs))
	vocab := make([]string, 0, len(df))
	idx := make(map[string]int, len(df))
	for w := range df {
		idx[w] = len(vocab)
		vocab = append(vocab, w)
	}
	vecs := make([][]float64, len(docs))
	for i, d := range docs {
		v := make([]float64, len(vocab))
		tf := map[string]int{}
		for _, w := range d {
			tf[w]++
		}
		for w, c := range tf {
			if j, ok := idx[w]; ok {
				idf := math.Log((1+n)/(1+float64(df[w]))) + 1
				v[j] = float64(c) * idf
			}
		}
		vecs[i] = l2normalize(v)
	}
	return vecs, nil
}

// tokenize：中文按单字、英文/数字按词，统一小写。
func tokenize(s string) []string {
	var toks []string
	var cur strings.Builder
	flush := func() {
		if cur.Len() > 0 {
			toks = append(toks, cur.String())
			cur.Reset()
		}
	}
	for _, r := range s {
		if unicode.Is(unicode.Han, r) {
			flush()
			toks = append(toks, string(r))
		} else if unicode.IsLetter(r) || unicode.IsDigit(r) {
			cur.WriteRune(unicode.ToLower(r))
		} else {
			flush()
		}
	}
	flush()
	return toks
}

func l2normalize(v []float64) []float64 {
	var sum float64
	for _, x := range v {
		sum += x * x
	}
	if sum == 0 {
		return v
	}
	norm := math.Sqrt(sum)
	for i := range v {
		v[i] /= norm
	}
	return v
}

// Cosine 余弦相似度；向量已 L2 归一化时即等于点积。
func Cosine(a, b []float64) float64 {
	if len(a) != len(b) {
		return 0
	}
	var dot float64
	for i := range a {
		dot += a[i] * b[i]
	}
	return dot
}
