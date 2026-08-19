package version

import (
	"github.com/spf13/cobra"

	"github.com/colearn/colearn/cmd/colearn/internal"
	"github.com/colearn/colearn/cmd/colearn/internal/cliui"
	"github.com/colearn/colearn/pkg/config"
)

func NewVersionCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:     "version",
		Aliases: []string{"v"},
		Short:   "Show version information",
		Run: func(_ *cobra.Command, _ []string) {
			printVersion()
		},
	}

	return cmd
}

func printVersion() {
	build, goVer := config.FormatBuildInfo()
	cliui.PrintVersion(internal.Logo, "colearn "+config.FormatVersion(), build, goVer)
}
