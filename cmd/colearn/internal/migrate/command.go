package migrate

import (
	"github.com/spf13/cobra"

	"github.com/colearn/colearn/pkg/migrate"
)

func NewMigrateCommand() *cobra.Command {
	var opts migrate.Options

	cmd := &cobra.Command{
		Use:   "migrate",
		Short: "Migrate from xxxclaw(openclaw, etc.) to colearn",
		Args:  cobra.NoArgs,
		Example: `  colearn migrate
  colearn migrate --from openclaw
  colearn migrate --dry-run
  colearn migrate --refresh
  colearn migrate --force`,
		RunE: func(cmd *cobra.Command, _ []string) error {
			m := migrate.NewMigrateInstance(opts)
			result, err := m.Run(opts)
			if err != nil {
				return err
			}
			if !opts.DryRun {
				m.PrintSummary(result)
			}
			return nil
		},
	}

	cmd.Flags().BoolVar(&opts.DryRun, "dry-run", false,
		"Show what would be migrated without making changes")
	cmd.Flags().StringVar(&opts.Source, "from", "openclaw",
		"Source to migrate from (e.g., openclaw)")
	cmd.Flags().BoolVar(&opts.Refresh, "refresh", false,
		"Re-sync workspace files from OpenClaw (repeatable)")
	cmd.Flags().BoolVar(&opts.ConfigOnly, "config-only", false,
		"Only migrate config, skip workspace files")
	cmd.Flags().BoolVar(&opts.WorkspaceOnly, "workspace-only", false,
		"Only migrate workspace files, skip config")
	cmd.Flags().BoolVar(&opts.Force, "force", false,
		"Skip confirmation prompts")
	cmd.Flags().StringVar(&opts.SourceHome, "source-home", "",
		"Override source home directory (default: ~/.openclaw)")
	cmd.Flags().StringVar(&opts.TargetHome, "target-home", "",
		"Override target home directory (default: ~/.colearn)")

	return cmd
}
