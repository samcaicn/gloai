// edict Go 服务入口（统一语言版本，替代原 edict Python 后端）。
//
// 子命令：
//   edict serve       启动 HTTP 服务（替代 dashboard/server.py），默认 :7891
//   edict kanban ...  任务看板 CLI（替代 scripts/kanban_update.py）
//   edict fetch-news  抓取 RSS 生成天下要闻（替代 scripts/fetch_morning_news.py）
//   edict dump-live   导出 live-status JSON（替代 scripts/refresh_live_data.py）
//   edict apply-model 应用模型变更（替代 scripts/apply_model_changes.py）
package main

import (
	"flag"
	"log"

	"edict/internal/cli"
	"edict/internal/db"
	"edict/internal/service"
	"edict/internal/store"
)

func main() {
	dbPath := flag.String("db", "edict.db", "SQLite 数据库文件路径")
	flag.Parse()

	database, err := db.Open(*dbPath)
	if err != nil {
		log.Fatalf("打开数据库失败: %v", err)
	}
	defer database.Close()

	st := store.New(database)
	svc := service.New(st)
	if err := st.EnsureAgents(service.DefaultAgents()); err != nil {
		log.Fatalf("播种 Agent 名册失败: %v", err)
	}

	if err := cli.Run(svc, flag.Args()); err != nil {
		log.Fatalf("执行失败: %v", err)
	}
}
