import type { GithubClient } from '../github/client.js'
import type { PluginCatalog } from '../github/catalog.js'
import { inspectPlugin, resolveRepo } from '../plugin/inspect.js'
import { readInstalledProfile } from '../runtime/host.js'
import type { AppConfig } from '../config.js'

export const RESOURCE_TEMPLATES = [
  {
    uriTemplate: 'dsh-plugin://github/{owner}/{repo}',
    name: 'plugin-card',
    description: 'GitHub card plus inspect summary for one dsh-plugin',
    mimeType: 'application/json',
  },
  {
    uriTemplate: 'dsh-plugin://github/{owner}/{repo}/readme',
    name: 'plugin-readme',
    description: 'README of one dsh-plugin repository',
    mimeType: 'text/markdown',
  },
  {
    uriTemplate: 'dsh-plugin://github/{owner}/{repo}/package.json',
    name: 'plugin-package',
    description: 'Root package.json of one dsh-plugin repository',
    mimeType: 'application/json',
  },
  {
    uriTemplate: 'dsh-plugin://github/{owner}/{repo}/cordis.patch.yml',
    name: 'plugin-patch',
    description: 'cordis.patch.yml of one dsh-plugin bundle',
    mimeType: 'text/yaml',
  },
  {
    uriTemplate: 'dsh-plugin://installed/{profile}',
    name: 'installed-profile',
    description: 'Bundles installed in a DSH profile',
    mimeType: 'application/json',
  },
]

const STATIC_RESOURCES = [
  {
    uri: 'dsh-plugin://catalog',
    name: 'catalog',
    description: 'Cached GitHub topic:dsh-plugin listing',
    mimeType: 'application/json',
  },
  {
    uri: 'dsh-plugin://runtime/tools',
    name: 'runtime-tools',
    description: 'Currently bridged DSH tools',
    mimeType: 'application/json',
  },
]

export function listStaticResources() {
  return STATIC_RESOURCES
}

export async function readResource(
  uri: string,
  deps: { catalog: PluginCatalog; github: GithubClient; config: AppConfig; bridgedJson: () => unknown },
): Promise<{ mimeType: string; text: string }> {
  if (uri === 'dsh-plugin://catalog') {
    const snapshot = await deps.catalog.getSnapshot()
    return { mimeType: 'application/json', text: JSON.stringify(snapshot, null, 2) }
  }
  if (uri === 'dsh-plugin://runtime/tools') {
    return { mimeType: 'application/json', text: JSON.stringify(deps.bridgedJson(), null, 2) }
  }
  const installed = /^dsh-plugin:\/\/installed\/([^/]+)$/.exec(uri)
  if (installed?.[1]) {
    const profile = decodeURIComponent(installed[1])
    const data = await readInstalledProfile(profile)
    return { mimeType: 'application/json', text: JSON.stringify(data ?? { profile, installed: false }, null, 2) }
  }
  const github = /^dsh-plugin:\/\/github\/([^/]+)\/([^/]+)(?:\/(readme|package\.json|cordis\.patch\.yml))?$/.exec(uri)
  if (!github?.[1] || !github[2]) {
    throw new Error(`unknown resource ${uri}`)
  }
  const owner = decodeURIComponent(github[1])
  const repo = decodeURIComponent(github[2])
  const rest = github[3]
  const spec = `${owner}/${repo}`
  if (rest === 'readme') {
    const text = await deps.github.getReadme(owner, repo)
    if (text === null) throw new Error(`README not found for ${spec}`)
    return { mimeType: 'text/markdown', text }
  }
  if (rest === 'package.json') {
    const text = await deps.github.getFileText(owner, repo, 'package.json')
    if (text === null) throw new Error(`package.json not found for ${spec}`)
    return { mimeType: 'application/json', text }
  }
  if (rest === 'cordis.patch.yml') {
    const snapshot = await deps.catalog.getSnapshot()
    const card = await resolveRepo(deps.github, snapshot.repos, spec)
    const inspection = await inspectPlugin(deps.github, card)
    if (!inspection.patchText) throw new Error(`cordis.patch.yml not found for ${spec}`)
    return { mimeType: 'text/yaml', text: inspection.patchText }
  }
  const snapshot = await deps.catalog.getSnapshot()
  const card = await resolveRepo(deps.github, snapshot.repos, spec)
  const inspection = await inspectPlugin(deps.github, card)
  return { mimeType: 'application/json', text: JSON.stringify(inspection, null, 2) }
}
