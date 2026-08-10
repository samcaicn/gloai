package config

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

// DataDir returns the platform-standard data directory for CEOadmin.
//
//	Linux:       ~/.local/share/CEOadmin/
//	macOS:       ~/Library/Application Support/CEOadmin/
//	root/service: /var/lib/CEOadmin/
func DataDir() string {
	if os.Getuid() == 0 {
		return "/var/lib/CEOadmin"
	}
	home, err := os.UserHomeDir()
	if err != nil || home == "" {
		fmt.Fprintf(os.Stderr, "warning: cannot determine home directory: %v, falling back to /var/lib/CEOadmin\n", err)
		return "/var/lib/CEOadmin"
	}
	switch runtime.GOOS {
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "CEOadmin")
	default:
		if xdg := os.Getenv("XDG_DATA_HOME"); xdg != "" {
			return filepath.Join(xdg, "CEOadmin")
		}
		return filepath.Join(home, ".local", "share", "CEOadmin")
	}
}

// DefaultDBPath returns the default SQLite database path.
func DefaultDBPath() string {
	return filepath.Join(DataDir(), "ceoadmin.db")
}

// EnsureDataDir creates the data directory if it doesn't exist.
func EnsureDataDir() error {
	return os.MkdirAll(DataDir(), 0700)
}
