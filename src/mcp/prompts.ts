export const PROMPTS = [
  {
    name: 'search-dsh-plugins',
    description: 'Search the public dsh-plugin catalog for plugins that match a task.',
    arguments: [{ name: 'task', description: 'What the agent needs to do', required: true }],
  },
  {
    name: 'install-dsh-plugin',
    description: 'Install a DeepSeek Harness plugin into the MCP bridge profile.',
    arguments: [{ name: 'spec', description: 'github:owner/repo or owner/repo', required: true }],
  },
  {
    name: 'use-dsh-plugin',
    description: 'Inspect a plugin, start the DSH runtime with it loaded, and use its tools.',
    arguments: [
      { name: 'spec', description: 'github:owner/repo or owner/repo', required: true },
      { name: 'task', description: 'What to do with the plugin', required: true },
    ],
  },
] as const

export function renderPrompt(name: string, args: Record<string, string>): string {
  if (name === 'search-dsh-plugins') {
    const task = args.task ?? ''
    return [
      'Search DeepSeek Harness plugins for this task.',
      `Task: ${task}`,
      'Call dsh_plugin_search with relevant keywords, then dsh_plugin_inspect on the best matches.',
      'Prefer repositories that declare dsh.bundle.patch (installable Cordis bundles).',
      'Cite github:owner/repo install specs.',
    ].join('\n')
  }
  if (name === 'install-dsh-plugin') {
    const spec = args.spec ?? ''
    return [
      `Install the DeepSeek Harness plugin ${spec}.`,
      '1. dsh_plugin_inspect to confirm it is a DSH bundle.',
      '2. dsh_plugin_status to see whether --allow-install is on.',
      '3. dsh_plugin_install with spec github:owner/repo.',
      '4. dsh_plugin_list_installed to verify the profile layer list.',
    ].join('\n')
  }
  if (name === 'use-dsh-plugin') {
    const spec = args.spec ?? ''
    const task = args.task ?? ''
    return [
      `Use DeepSeek Harness plugin ${spec} to: ${task}`,
      '1. dsh_plugin_inspect the spec.',
      '2. dsh_plugin_status — runtime requires --allow-runtime.',
      '3. dsh_runtime_start with plugins: ["github:owner/repo"].',
      '4. dsh_runtime_list_tools, then call the relevant dsh__* tools.',
      'UI-only plugins cannot execute through MCP; install them into a DSH web/tui profile instead.',
    ].join('\n')
  }
  throw new Error(`unknown prompt ${name}`)
}
