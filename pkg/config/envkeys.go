// colearn - Ultra-lightweight personal AI agent
// License: MIT
//
// Copyright (c) 2026 colearn contributors

package config

import (
	"os"
	"path/filepath"

	"github.com/colearn/colearn/pkg"
)

// Runtime environment variable keys for the colearn process.
// These control the location of files and binaries at runtime and are read
// directly via os.Getenv / os.LookupEnv. All colearn-specific keys use the
// colearn_ prefix. Reference these constants instead of inline string
// literals to keep all supported knobs visible in one place and to prevent
// typos.
const (
	// EnvHome overrides the base directory for all colearn data
	// (config, workspace, skills, auth store, …).
	// Default: ~/.colearn
	EnvHome = "colearn_HOME"

	// EnvConfig overrides the full path to the JSON config file.
	// Default: $colearn_HOME/config.json
	EnvConfig = "colearn_CONFIG"

	// EnvBuiltinSkills overrides the directory from which built-in
	// skills are loaded.
	// Default: <cwd>/skills
	EnvBuiltinSkills = "colearn_BUILTIN_SKILLS"

	// EnvBinary overrides the path to the colearn executable.
	// Used by the web launcher when spawning the gateway subprocess.
	// Default: resolved from the same directory as the current executable.
	EnvBinary = "colearn_BINARY"

	// EnvGatewayHost overrides the host address for the gateway server.
	// Default: "www.tuptup.top"
	EnvGatewayHost = "colearn_GATEWAY_HOST"
)

func GetHome() string {
	homePath, _ := os.UserHomeDir()
	if colearnHome := os.Getenv(EnvHome); colearnHome != "" {
		homePath = colearnHome
	} else if homePath != "" {
		homePath = filepath.Join(homePath, pkg.DefaultcolearnHome)
	}
	if homePath == "" {
		homePath = "."
	}
	return homePath
}
