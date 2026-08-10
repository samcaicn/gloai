package llm

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Client 是 OpenAI 兼容的聊天端点客户端。
// 当 APIKey 为空或 "mock" 时进入离线模式，便于无密钥联调。
type Client struct {
	BaseURL string
	APIKey  string
	Model   string
	HTTP    *http.Client
}

type chatRequest struct {
	Model    string    `json:"model"`
	Messages []message `json:"messages"`
}

type message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type chatResponse struct {
	Choices []struct {
		Message message `json:"message"`
	} `json:"choices"`
	Usage *chatUsage `json:"usage"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error"`
}

// chatUsage 对齐 OpenAI 使用量字段。
type chatUsage struct {
	PromptTokens        int `json:"prompt_tokens"`
	CompletionTokens    int `json:"completion_tokens"`
	TotalTokens         int `json:"total_tokens"`
	PromptTokensDetails struct {
		CachedTokens int `json:"cached_tokens"`
	} `json:"prompt_tokens_details"`
	CompletionTokensDetails struct {
		ReasoningTokens int `json:"reasoning_tokens"`
	} `json:"completion_tokens_details"`
}

// Usage 一次对话补全的 token 用量，供上层记录。
type Usage struct {
	PromptTokens     int
	CompletionTokens int
	TotalTokens      int
	CachedTokens     int
	ReasoningTokens  int
	DurationMS       int64 // 调用耗时（毫秒）
}

// New 构造客户端。
func New(base, key, model string) *Client {
	if base == "" {
		base = "https://api.openai.com/v1"
	}
	if model == "" {
		model = "gpt-4o-mini"
	}
	return &Client{BaseURL: base, APIKey: key, Model: model, HTTP: &http.Client{Timeout: 60 * time.Second}}
}

// Health 探活：mock 模式或未配置 key 时返回 (false, nil)，表示离线/无需外网；
// 否则发一个极小对话确认 LLM 端点可达。供启动预检与自测使用。
func (c *Client) Health() (bool, error) {
	if c.APIKey == "" || c.APIKey == "mock" {
		return false, nil
	}
	_, _, err := c.Chat("ping", "reply with the single word ok")
	return err == nil, err
}

// Chat 发送一次对话补全。system 用于注入租户记忆上下文。返回回复文本、token
// 用量（离线 mock 模式返回零值用量）以及错误。
func (c *Client) Chat(system, user string) (string, *Usage, error) {
	if c.APIKey == "" || c.APIKey == "mock" {
		return fmt.Sprintf("[MOCK] 已结合租户记忆上下文作答。\n用户消息: %s", user), &Usage{}, nil
	}
	body, err := json.Marshal(chatRequest{
		Model: c.Model,
		Messages: []message{
			{Role: "system", Content: system},
			{Role: "user", Content: user},
		},
	})
	if err != nil {
		return "", nil, err
	}
	req, err := http.NewRequest(http.MethodPost, c.BaseURL+"/chat/completions", bytes.NewReader(body))
	if err != nil {
		return "", nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.APIKey)

	start := time.Now()
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return "", nil, err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	durationMS := time.Since(start).Milliseconds()
	if resp.StatusCode != http.StatusOK {
		return "", nil, fmt.Errorf("llm http %d: %s", resp.StatusCode, string(data))
	}
	var cr chatResponse
	if err := json.Unmarshal(data, &cr); err != nil {
		return "", nil, err
	}
	if cr.Error != nil && cr.Error.Message != "" {
		return "", nil, fmt.Errorf("llm error: %s", cr.Error.Message)
	}
	if len(cr.Choices) == 0 {
		return "", nil, fmt.Errorf("llm empty response")
	}
	u := &Usage{}
	if cr.Usage != nil {
		u.PromptTokens = cr.Usage.PromptTokens
		u.CompletionTokens = cr.Usage.CompletionTokens
		u.TotalTokens = cr.Usage.TotalTokens
		u.CachedTokens = cr.Usage.PromptTokensDetails.CachedTokens
		u.ReasoningTokens = cr.Usage.CompletionTokensDetails.ReasoningTokens
	}
	u.DurationMS = durationMS
	return cr.Choices[0].Message.Content, u, nil
}
