package main

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"github.com/ceoadmin/CEOadmin/internal/compose"
	"github.com/ceoadmin/CEOadmin/internal/config"
	"github.com/ceoadmin/CEOadmin/internal/daemon"

	// Register providers
	_ "github.com/ceoadmin/CEOadmin/internal/provider/ilink"

	// Register builtin apps
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/bridge"
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/github"
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/mcpserver"
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/openclaw"
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/runner"

	// Register AI Transformation Cockpit builtin app (upstream submodule)
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/cockpit"

	// Register 甲乙方 AI 对聊 builtin app
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/tenantchat"

	// Register EDICT 御前奏对 builtin app (Go 版后端，源码在 edict/)
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/edict"

	// Register 供采市场 builtin app
	_ "github.com/ceoadmin/CEOadmin/internal/builtin/supplymarket"
)

// Set by goreleaser ldflags.
var (
	version = "dev"
	commit  = "none"
	date    = "unknown"
)

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "version":
			fmt.Printf("oih (CEOadmin Hub) %s (%s, %s)\n", version, commit, date)
			return
		case "install":
			listen := ":9800"
			if len(os.Args) > 2 {
				listen = os.Args[2]
			}
			if err := daemon.Install(listen, config.DataDir()); err != nil {
				fmt.Fprintf(os.Stderr, "install failed: %v\n", err)
				os.Exit(1)
			}
			return
		case "uninstall":
			if err := daemon.Uninstall(); err != nil {
				fmt.Fprintf(os.Stderr, "uninstall failed: %v\n", err)
				os.Exit(1)
			}
			return
		}
	}

	cfg := config.Load()

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	// Build wires every component together (see internal/compose). The lifecycle
	// Supervisor owns all long-running pieces and starts/stops them as a group.
	hub, err := compose.Build(ctx, cfg, version)
	if err != nil {
		slog.Error("failed to build hub", "err", err)
		os.Exit(1)
	}

	fmt.Printf("CEOadmin Hub %s (%s, %s) running on http://localhost%s\n", version, commit, date, cfg.ListenAddr)
	fmt.Printf("Data: %s\n", config.DataDir())

	// Blocks until SIGINT/SIGTERM, then stops every service in reverse order.
	if err := hub.Lifecycle.Run(ctx); err != nil {
		slog.Error("hub exited with error", "err", err)
		os.Exit(1)
	}
}
