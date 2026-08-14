// Package cli 实现 edict 的命令行子命令，移植原 Python scripts/ 下的运维脚本：
//
//	edict serve        -> dashboard/server.py（HTTP 服务，已内置）
//	edict kanban ...   -> scripts/kanban_update.py（任务看板 CLI）
//	edict fetch-news   -> scripts/fetch_morning_news.py（RSS 抓取→天下要闻）
//	edict dump-live    -> scripts/refresh_live_data.py（导出 live-status）
//	edict apply-model  -> scripts/apply_model_changes.py（应用模型变更）
package cli

import (
	"context"
	"encoding/json"
	"encoding/xml"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"

	"edict/internal/api"
	"edict/internal/model"
	"edict/internal/service"
)

// Run 分发子命令。args 为排除程序名后的参数。
func Run(svc *service.Service, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("用法: edict <serve|kanban|fetch-news|dump-live|apply-model> [参数]")
	}
	switch args[0] {
	case "serve":
		return runServe(svc, args[1:])
	case "kanban":
		return runKanban(svc, args[1:])
	case "fetch-news":
		return runFetchNews(svc, args[1:])
	case "dump-live":
		return runDumpLive(svc, args[1:])
	case "apply-model":
		return runApplyModel(svc, args[1:])
	default:
		return fmt.Errorf("未知子命令: %s", args[0])
	}
}

// ── serve ──

func runServe(svc *service.Service, args []string) error {
	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	addr := fs.String("addr", ":7891", "HTTP 监听地址")
	web := fs.String("web", "", "已构建的 React 前端 dist 目录（可选）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	srv := api.New(svc, *web)
	fmt.Printf("⚔️  edict Go 服务启动: http://%s\n", *addr)
	return http.ListenAndServe(*addr, srv)
}

// ── kanban（移植 kanban_update.py）──

func runKanban(svc *service.Service, args []string) error {
	if len(args) == 0 {
		return fmt.Errorf("用法: edict kanban <create|state|flow|done|block|todo|progress> ...")
	}
	switch args[0] {
	case "create":
		// edict kanban create <id> <title> <org> [official]
		if len(args) < 4 {
			return fmt.Errorf("kanban create 需要 <id> <title> <org>")
		}
		official := ""
		if len(args) > 4 {
			official = args[4]
		}
		t, err := svc.CreateTask(model.CreateTaskPayload{ID: args[1], Title: args[2], Org: args[3], Official: official})
		if err != nil {
			return err
		}
		fmt.Printf("✅ 创建 %s | %s\n", t.ID, t.Title)
	case "state":
		// edict kanban state <id> <newState> [comment]
		if len(args) < 3 {
			return fmt.Errorf("kanban state 需要 <id> <newState>")
		}
		comment := ""
		if len(args) > 3 {
			comment = args[3]
		}
		if _, err := svc.AdvanceTo(args[1], args[2], comment); err != nil {
			return err
		}
		fmt.Printf("✅ %s 状态更新: %s\n", args[1], args[2])
	case "flow":
		// edict kanban flow <id> <from> <to> <remark>
		if len(args) < 5 {
			return fmt.Errorf("kanban flow 需要 <id> <from> <to> <remark>")
		}
		if _, err := svc.AdvanceTo(args[1], args[3], args[4]); err != nil {
			return err
		}
		fmt.Printf("✅ %s 流转: %s → %s\n", args[1], args[2], args[3])
	case "done":
		// edict kanban done <id> <output>
		if len(args) < 3 {
			return fmt.Errorf("kanban done 需要 <id> <output>")
		}
		if _, err := svc.CompleteTask(args[1], args[2]); err != nil {
			return err
		}
		fmt.Printf("✅ %s 已完成\n", args[1])
	case "block":
		// edict kanban block <id> <reason>
		if len(args) < 3 {
			return fmt.Errorf("kanban block 需要 <id> <reason>")
		}
		if _, err := svc.TaskAction(args[1], "block", args[2]); err != nil {
			return err
		}
		fmt.Printf("⚠️  %s 已阻塞: %s\n", args[1], args[2])
	case "todo":
		// edict kanban todo <id> <todoId> <title> <status>
		if len(args) < 5 {
			return fmt.Errorf("kanban todo 需要 <id> <todoId> <title> <status>")
		}
		if _, err := svc.SetTodo(args[1], args[2], args[3], args[4], ""); err != nil {
			return err
		}
		fmt.Printf("✅ %s todo [%s]: %s → %s\n", args[1], args[2], args[3], args[4])
	case "progress":
		// edict kanban progress <id> <text> [todosPipe] [tokens] [cost] [elapsed]
		if len(args) < 3 {
			return fmt.Errorf("kanban progress 需要 <id> <text>")
		}
		todosPipe, tokens, cost, elapsed := "", 0, 0.0, 0
		if len(args) > 3 {
			todosPipe = args[3]
		}
		if len(args) > 4 {
			fmt.Sscanf(args[4], "%d", &tokens)
		}
		if len(args) > 5 {
			fmt.Sscanf(args[5], "%f", &cost)
		}
		if len(args) > 6 {
			fmt.Sscanf(args[6], "%d", &elapsed)
		}
		if _, err := svc.AppendProgress(args[1], args[2], todosPipe, tokens, cost, elapsed); err != nil {
			return err
		}
		fmt.Printf("✅ %s 进展更新\n", args[1])
	default:
		return fmt.Errorf("未知 kanban 子命令: %s", args[0])
	}
	return nil
}

// ── fetch-news（移植 fetch_morning_news.py）──

// defaultFeeds 默认 RSS 源（含已集成的“技术博客”分类）。
var defaultFeeds = map[string][]source{
	"政治": {
		{"新华社", "https://feeds.xinhuanet.com/news.xml"},
	},
	"军事": {
		{"Defense News", "https://www.defensenews.com/feed/"},
	},
	"经济": {
		{"Reuters Business", "https://feeds.reuters.com/reuters/businessNews"},
		{"BBC Business", "https://feeds.bbci.co.uk/news/business/rss.xml"},
	},
	"AI大模型": {
		{"Hacker News", "https://hnrss.org/newest?q=AI+LLM+model&points=50"},
		{"VentureBeat AI", "https://venturebeat.com/category/ai/feed/"},
		{"MIT Tech Review", "https://www.technologyreview.com/feed/"},
	},
	// 技术博客精选：来源于 ai-daily-digest（已集成的需求分支）
	"技术博客": {
		{"Simon Willison", "https://simonwillison.net/atom/everything/"},
		{"Paul Graham", "https://www.aaronsw.com/2002/feeds/pgessays.rss"},
		{"antirez (Redis)", "https://antirez.com/rss"},
		{"overreacted.io", "https://overreacted.io/rss.xml"},
		{"Eli Bendersky", "https://eli.thegreenplace.net/feeds/all.atom.xml"},
		{"Krebs on Security", "https://krebsonsecurity.com/feed/"},
		{"Daring Fireball", "https://daringfireball.net/feeds/main"},
		{"Fabien Sanglard", "https://fabiensanglard.net/rss.xml"},
	},
}

type source struct {
	Name string
	URL  string
}

// categoryKeywords 简易分类关键词（对齐 Python CATEGORY_KEYWORDS）。
var categoryKeywords = map[string][]string{
	"军事":    {"war", "military", "troops", "attack", "missile", "army", "navy", "战", "军", "导弹"},
	"AI大模型": {"ai", "llm", "gpt", "claude", "gemini", "openai", "anthropic", "deepseek", "大模型", "人工智能", "chatgpt"},
	"经济":    {"economy", "market", "stock", "gdp", "经济", "股市", "金融"},
}

func runFetchNews(svc *service.Service, args []string) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	cats := map[string][]model.MorningNewsItem{}
	client := &http.Client{}
	for cat, srcs := range defaultFeeds {
		for _, s := range srcs {
			items, err := fetchFeed(ctx, client, s.URL)
			if err != nil {
				fmt.Fprintf(os.Stderr, "⚠️  抓取 %s 失败: %v\n", s.URL, err)
				continue
			}
			for _, it := range items {
				it.Source = s.Name
				classified := classify(it)
				if classified == "" {
					classified = cat // 落入其源所属分类
				}
				cats[classified] = append(cats[classified], it)
			}
		}
	}
	// 每个分类最多保留 20 条
	for c := range cats {
		sort.SliceStable(cats[c], func(i, j int) bool { return cats[c][i].Link < cats[c][j].Link })
		if len(cats[c]) > 20 {
			cats[c] = cats[c][:20]
		}
	}
	b, _ := json.Marshal(cats)
	if _, err := svc.Store.DB.Exec(
		`INSERT INTO morning_brief (id, date, generated_at, categories) VALUES (1,?,?,?)
		 ON CONFLICT(id) DO UPDATE SET date=excluded.date, generated_at=excluded.generated_at, categories=excluded.categories`,
		time.Now().Format("2006-01-02"), time.Now().UTC().Format(time.RFC3339), string(b),
	); err != nil {
		return err
	}
	fmt.Printf("✅ 天下要闻已更新，覆盖 %d 个分类\n", len(cats))
	return nil
}

func classify(it model.MorningNewsItem) string {
	text := strings.ToLower(it.Title + " " + it.Desc)
	for cat, kws := range categoryKeywords {
		for _, kw := range kws {
			if strings.Contains(text, strings.ToLower(kw)) {
				return cat
			}
		}
	}
	return ""
}

func fetchFeed(ctx context.Context, client *http.Client, url string) ([]model.MorningNewsItem, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "Mozilla/5.0 (compatible; MorningBrief/1.0)")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, err
	}
	var doc struct {
		Channel struct {
			Items []struct {
				Title string `xml:"title"`
				Link  string `xml:"link"`
				Desc  string `xml:"description"`
			} `xml:"item"`
		} `xml:"channel"`
		Entries []struct {
			Title   string `xml:"title"`
			Link    string `xml:"link,attr"`
			Summary string `xml:"summary"`
		} `xml:"entry"`
	}
	if err := xml.Unmarshal(body, &doc); err != nil {
		return nil, err
	}
	var out []model.MorningNewsItem
	for _, it := range doc.Channel.Items {
		out = append(out, model.MorningNewsItem{Title: it.Title, Link: it.Link, Desc: it.Desc})
	}
	for _, e := range doc.Entries {
		link := e.Link
		if link == "" {
			// Atom 的 link 可能是子元素
			var a struct {
				Link []struct {
					Href string `xml:"href,attr"`
				} `xml:"link"`
			}
			_ = xml.Unmarshal(body, &a)
			if len(a.Link) > 0 {
				link = a.Link[0].Href
			}
		}
		out = append(out, model.MorningNewsItem{Title: e.Title, Link: link, Desc: e.Summary})
	}
	return out, nil
}

// ── dump-live（移植 refresh_live_data.py）──

func runDumpLive(svc *service.Service, args []string) error {
	fs := flag.NewFlagSet("dump-live", flag.ContinueOnError)
	out := fs.String("o", "", "输出文件路径（默认 stdout）")
	if err := fs.Parse(args); err != nil {
		return err
	}
	tasks, err := svc.Store.ListTasks(false)
	if err != nil {
		return err
	}
	data, err := json.MarshalIndent(model.LiveStatus{Tasks: tasks, SyncStatus: model.SyncStatus{OK: true}}, "", "  ")
	if err != nil {
		return err
	}
	if *out == "" {
		fmt.Println(string(data))
		return nil
	}
	return os.WriteFile(*out, data, 0o644)
}

// ── apply-model（移植 apply_model_changes.py）──

func runApplyModel(svc *service.Service, args []string) error {
	fs := flag.NewFlagSet("apply-model", flag.ContinueOnError)
	agent := fs.String("agent", "", "Agent ID")
	modelName := fs.String("model", "", "目标模型")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *agent == "" || *modelName == "" {
		return fmt.Errorf("apply-model 需要 -agent 和 -model")
	}
	if err := svc.Store.SetAgentModel(*agent, *modelName); err != nil {
		return err
	}
	fmt.Printf("✅ 模型已应用到 %s: %s\n", *agent, *modelName)
	return nil
}
