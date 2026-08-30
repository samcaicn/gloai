import { existsSync, mkdirSync, readFileSync, writeFileSync, readdirSync, unlinkSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { type RunnableConfig } from '@langchain/core/runnables'
import {
  BaseCheckpointSaver,
  type Checkpoint,
  type CheckpointMetadata,
  type CheckpointTuple,
  type CheckpointListOptions,
  emptyCheckpoint,
  copyCheckpoint,
} from '@langchain/langgraph-checkpoint'

/**
 * File-system based checkpointer for LangGraph.
 * Stores checkpoints as JSON files in a directory hierarchy.
 * Implements the BaseCheckpointSaver interface.
 */
export class FileSystemCheckpointer extends BaseCheckpointSaver {
  private readonly checkpointDir: string

  constructor(checkpointDir: string) {
    super()
    this.checkpointDir = checkpointDir
    if (!existsSync(checkpointDir)) {
      mkdirSync(checkpointDir, { recursive: true })
    }
  }

  async getTuple(config: RunnableConfig): Promise<CheckpointTuple | undefined> {
    const threadId = config.configurable?.thread_id
    const checkpointId = config.configurable?.checkpoint_id
    if (!threadId) return undefined

    const threadDir = join(this.checkpointDir, threadId)
    if (!existsSync(threadDir)) return undefined

    let targetId = checkpointId
    if (!targetId) {
      // Get the latest checkpoint
      const files = readdirSync(threadDir)
        .filter(f => f.endsWith('.json') && !f.endsWith('.meta.json'))
        .sort().reverse()
      if (files.length === 0) return undefined
      targetId = files[0]!.replace('.json', '')
    }

    const checkpointPath = join(threadDir, `${targetId}.json`)
    if (!existsSync(checkpointPath)) return undefined

    const checkpoint: Checkpoint = JSON.parse(readFileSync(checkpointPath, 'utf8'))
    const metadataPath = join(threadDir, `${targetId}.meta.json`)
    const metadata: CheckpointMetadata = existsSync(metadataPath)
      ? JSON.parse(readFileSync(metadataPath, 'utf8'))
      : { source: 'update', step: 0, writes: null, parents: {} }

    return {
      config: { configurable: { thread_id: threadId, checkpoint_id: targetId } },
      checkpoint,
      metadata,
    }
  }

  async *list(config: RunnableConfig, options?: CheckpointListOptions): AsyncGenerator<CheckpointTuple> {
    const threadId = config.configurable?.thread_id
    if (!threadId) return

    const threadDir = join(this.checkpointDir, threadId)
    if (!existsSync(threadDir)) return

    let files = readdirSync(threadDir)
      .filter(f => f.endsWith('.json') && !f.endsWith('.meta.json'))
      .sort().reverse()

    if (options?.before?.configurable?.checkpoint_id) {
      const beforeId = options.before.configurable.checkpoint_id as string
      const beforeIdx = files.findIndex(f => f.startsWith(beforeId))
      if (beforeIdx >= 0) files = files.slice(beforeIdx + 1)
    }

    if (options?.limit) {
      files = files.slice(0, options.limit)
    }

    for (const file of files) {
      const id = file.replace('.json', '')
      const tupleConfig: RunnableConfig = { configurable: { thread_id: threadId, checkpoint_id: id } }
      const tuple = await this.getTuple(tupleConfig)
      if (tuple) yield tuple
    }
  }

  async put(
    config: RunnableConfig,
    checkpoint: Checkpoint,
    metadata: CheckpointMetadata,
  ): Promise<RunnableConfig> {
    const threadId = config.configurable?.thread_id
    const checkpointId = config.configurable?.checkpoint_id
    if (!threadId || !checkpointId) throw new Error('thread_id and checkpoint_id are required')

    const threadDir = join(this.checkpointDir, threadId)
    if (!existsSync(threadDir)) {
      mkdirSync(threadDir, { recursive: true })
    }

    writeFileSync(join(threadDir, `${checkpointId}.json`), JSON.stringify(checkpoint))
    writeFileSync(join(threadDir, `${checkpointId}.meta.json`), JSON.stringify(metadata))

    return { configurable: { thread_id: threadId, checkpoint_id: checkpointId } }
  }

  async putWrites(config: RunnableConfig, writes: [string, unknown][], taskId: string): Promise<void> {
    const threadId = config.configurable?.thread_id
    const checkpointId = config.configurable?.checkpoint_id
    if (!threadId || !checkpointId) return

    const threadDir = join(this.checkpointDir, threadId)
    if (!existsSync(threadDir)) {
      mkdirSync(threadDir, { recursive: true })
    }

    writeFileSync(join(threadDir, `${checkpointId}.writes.json`), JSON.stringify({ taskId, writes }))
  }

  async deleteThread(threadId: string): Promise<void> {
    const threadDir = join(this.checkpointDir, threadId)
    if (!existsSync(threadDir)) return
    rmSync(threadDir, { recursive: true, force: true })
  }
}
