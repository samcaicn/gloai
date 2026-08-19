package tupai

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"
)

// sseEventData mirrors the SSE "message" event payload:
//
//	event: message
//	data: {"id":..., "object":"chat.completion.chunk",
//	       "choices":[{"index":0,"delta":{"content":"..."},"finish_reason":null}]}
type sseMessageEvent struct {
	ID      string `json:"id"`
	Object  string `json:"object"`
	Choices []struct {
		Index        int    `json:"index"`
		Delta        struct {
			Content string `json:"content"`
		} `json:"delta"`
		FinishReason any `json:"finish_reason"`
	} `json:"choices"`
}

// parseSSEStream reads the /api/v2/mcp SSE stream for llm.stream_request and
// yields OpenAI-compatible chunks. Terminates on "done"; surfaces "error".
func parseSSEStream(
	ctx context.Context,
	reader io.Reader,
	onChunk func(StreamChunk),
) (*LLMResponse, error) {
	var textContent strings.Builder
	var usage *UsageInfo
	finishReason := "stop"

	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 0, 1024*1024), 10*1024*1024)

	var eventType string
	var dataLines []string

	flush := func() (bool, error) {
		if eventType == "" && len(dataLines) == 0 {
			return false, nil
		}
		defer func() {
			eventType = ""
			dataLines = nil
		}()
		data := strings.Join(dataLines, "\n")
		dataLines = nil

		switch eventType {
		case "message":
			var ev sseMessageEvent
			if err := json.Unmarshal([]byte(data), &ev); err != nil {
				return false, fmt.Errorf("tup stream: decode message event: %w", err)
			}
			if len(ev.Choices) == 0 {
				return false, nil
			}
			choice := ev.Choices[0]
			if choice.Delta.Content != "" {
				textContent.WriteString(choice.Delta.Content)
				if onChunk != nil {
					onChunk(StreamChunk{Content: textContent.String()})
				}
			}
			if fr, ok := choice.FinishReason.(string); ok && fr != "" {
				finishReason = fr
			}
		case "usage":
			var raw map[string]any
			if json.Unmarshal([]byte(data), &raw) == nil {
				usage = parseUsage(raw)
			}
		case "done":
			return true, nil
		case "error":
			var raw map[string]any
			_ = json.Unmarshal([]byte(data), &raw)
			return false, fmt.Errorf("tup stream error: %s", errorString(&mcpError{
				Code:    stringField(raw, "code"),
				Message: stringField(raw, "message"),
			}))
		}
		return false, nil
	}

	for scanner.Scan() {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		line := strings.TrimRight(scanner.Text(), "\r")
		switch {
		case line == "":
			done, err := flush()
			if err != nil {
				return nil, err
			}
			if done {
				return &LLMResponse{
					Content:      textContent.String(),
					FinishReason: finishReason,
					Usage:        usage,
				}, nil
			}
		case strings.HasPrefix(line, ":"):
			// comment / heartbeat
		case strings.HasPrefix(line, "event:"):
			eventType = strings.TrimSpace(strings.TrimPrefix(line, "event:"))
		case strings.HasPrefix(line, "data:"):
			dataLines = append(dataLines, strings.TrimPrefix(strings.TrimPrefix(line, "data:"), " "))
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("tup stream: read error: %w", err)
	}

	// EOF without explicit done: return what we have.
	return &LLMResponse{
		Content:      textContent.String(),
		FinishReason: finishReason,
		Usage:        usage,
	}, nil
}

func stringField(m map[string]any, key string) string {
	if m == nil {
		return ""
	}
	if s, ok := m[key].(string); ok {
		return s
	}
	return ""
}