import type { PluginKind, PluginPackageJson } from '../types.js'

export interface ClassifyInput {
  owner: string
  name: string
  description: string | null
  topics: string[]
  packageJson: PluginPackageJson | null
  rootEntries: string[]
}

const UI_RE = /web-?ui|webui|sidebar|panel|skin|theme|pet|stickers/
const TUI_RE = /\btui\b|terminal ui|ink\b/
const TOOL_RE = /\btool\b|toolkit/
const SKILL_RE = /\bskill\b/
const MCP_RE = /\bmcp\b/
const DIR_RE = /awesome|directory|catalog|\bhub\b/
const DESKTOP_RE = /desktop|electron|macos workbench/
const WORKFLOW_RE = /workflow|orchestration/

function blobOf(input: ClassifyInput): string {
  return [
    input.name,
    input.description ?? '',
    input.topics.join(' '),
    input.packageJson?.name ?? '',
    input.packageJson?.description ?? '',
    input.rootEntries.join(' '),
  ].join(' ').toLowerCase()
}

/**
 * Derive plugin kinds from GitHub metadata and the inspected package.
 * A repository may match several kinds; `unknown` is used only when nothing else fits.
 */
export function classifyPlugin(input: ClassifyInput): PluginKind[] {
  const kinds = new Set<PluginKind>()
  const blob = blobOf(input)

  if (input.owner === 'deepseek-ai') kinds.add('official')
  if (input.packageJson?.dsh?.bundle?.patch) kinds.add('bundle')
  if (input.packageJson?.dsh?.client !== undefined) kinds.add('ui')

  if (UI_RE.test(blob)) kinds.add('ui')
  if (TUI_RE.test(blob)) kinds.add('tui')
  if (TOOL_RE.test(blob)) kinds.add('tool')
  if (SKILL_RE.test(blob) || input.rootEntries.some(name => name === 'SKILL.md' || name.endsWith('/SKILL.md'))) {
    kinds.add('skill')
  }
  if (MCP_RE.test(blob)) kinds.add('mcp')
  if (DIR_RE.test(blob)) kinds.add('directory')
  if (DESKTOP_RE.test(blob)) kinds.add('desktop')
  if (WORKFLOW_RE.test(blob)) kinds.add('workflow')

  if (kinds.size === 0) kinds.add('unknown')
  if (kinds.size > 1) kinds.delete('unknown')
  return [...kinds]
}

/** pnpm/git spec `dsh plugin add` accepts for a GitHub repository. */
export function installSpecFor(owner: string, repo: string): string {
  return `github:${owner}/${repo}`
}

export function parseRepoSpec(spec: string): { owner: string; repo: string } {
  const trimmed = spec.trim().replace(/\/+$/, '')
  const github = /^(?:https?:\/\/github\.com\/|github:)?([^/\s]+)\/([^/\s#?]+)(?:\.git)?$/i.exec(trimmed)
  if (!github?.[1] || !github[2]) {
    throw new Error(`expected owner/repo, github:owner/repo, or a GitHub URL, got ${JSON.stringify(spec)}`)
  }
  return { owner: github[1], repo: github[2].replace(/\.git$/i, '') }
}
