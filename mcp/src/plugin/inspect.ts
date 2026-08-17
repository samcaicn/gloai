import { parse as parseYaml } from 'yaml'
import type { GithubRepo, PluginInspection, PluginPackageJson } from '../types.js'
import { classifyPlugin, installSpecFor, parseRepoSpec } from './classify.js'
import type { GithubClient } from '../github/client.js'

const PATCH_CANDIDATES = ['cordis.patch.yml', 'cordis.patch.yaml']

/** Cordis loader evaluates `!!js` expressions; inspect only needs the file to parse. */
const CORDIS_JS_TAG = {
  tag: 'tag:yaml.org,2002:js',
  resolve(value: string) {
    return value
  },
}

/**
 * Parse a DSH `cordis.patch.yml`. Accepts Cordis `!!js` scalars as opaque strings.
 * @param text - patch file contents
 */
export function parsePatchYaml(text: string): unknown {
  return parseYaml(text, { customTags: [CORDIS_JS_TAG] })
}

/**
 * Load package.json, README, patch, and root listing, then classify the plugin.
 */
export async function inspectPlugin(
  github: GithubClient,
  repo: GithubRepo,
): Promise<PluginInspection> {
  const warnings: string[] = []
  const rootEntries = await github.listRoot(repo.owner, repo.name)

  let packageJson: PluginPackageJson | null = null
  const packageText = await github.getFileText(repo.owner, repo.name, 'package.json')
  if (packageText) {
    try {
      packageJson = JSON.parse(packageText) as PluginPackageJson
    } catch (error) {
      warnings.push(`package.json is not valid JSON: ${String(error)}`)
    }
  } else {
    warnings.push('no package.json at repository root')
  }

  const declaredPatch = packageJson?.dsh?.bundle?.patch?.replace(/^\.\//, '')
  const patchPath = declaredPatch
    ?? PATCH_CANDIDATES.find(name => rootEntries.includes(name))
    ?? null
  let patchText: string | null = null
  if (patchPath) {
    patchText = await github.getFileText(repo.owner, repo.name, patchPath)
    if (patchText) {
      try {
        parsePatchYaml(patchText)
      } catch (error) {
        warnings.push(`${patchPath} is not valid YAML: ${String(error)}`)
      }
    } else {
      warnings.push(`declared patch ${patchPath} was not found`)
    }
  }

  const readme = await github.getReadme(repo.owner, repo.name)
  const skillFiles = collectSkillFiles(rootEntries)

  const kinds = classifyPlugin({
    owner: repo.owner,
    name: repo.name,
    description: repo.description,
    topics: repo.topics,
    packageJson,
    rootEntries,
  })

  return {
    repo,
    kinds,
    packageName: packageJson?.name ?? null,
    isDshBundle: Boolean(packageJson?.dsh?.bundle?.patch),
    hasClient: packageJson?.dsh?.client !== undefined,
    patchPath,
    patchText,
    packageJson,
    readme,
    skillFiles,
    rootEntries,
    installSpec: installSpecFor(repo.owner, repo.name),
    inspectWarnings: warnings,
  }
}

function collectSkillFiles(rootEntries: string[]): string[] {
  return rootEntries.filter(name => name === 'SKILL.md' || name.endsWith('.skill.md') || name === 'skills')
}

export async function resolveRepo(
  github: GithubClient,
  catalogRepos: readonly GithubRepo[],
  spec: string,
): Promise<GithubRepo> {
  const { owner, repo } = parseRepoSpec(spec)
  const cached = catalogRepos.find(
    item => item.owner.toLowerCase() === owner.toLowerCase() && item.name.toLowerCase() === repo.toLowerCase(),
  )
  if (cached) return cached
  const payload = await github.json<{
    name: string
    full_name: string
    owner?: { login?: string }
    description: string | null
    html_url: string
    stargazers_count: number
    forks_count: number
    language: string | null
    topics?: string[]
    default_branch: string
    archived: boolean
    updated_at: string
  }>(`/repos/${owner}/${repo}`)
  return {
    owner: payload.owner?.login ?? owner,
    name: payload.name,
    fullName: payload.full_name,
    description: payload.description,
    htmlUrl: payload.html_url,
    stars: payload.stargazers_count,
    forks: payload.forks_count,
    language: payload.language,
    topics: payload.topics ?? [],
    defaultBranch: payload.default_branch,
    archived: payload.archived,
    updatedAt: payload.updated_at,
  }
}
