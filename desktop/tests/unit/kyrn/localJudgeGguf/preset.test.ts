import { describe, expect, it } from 'vitest';
import {
  LAYA_GGUF_DEFAULT_ID,
  LAYA_GGUF_PRESETS,
  layaGgufFileUrl,
  layaGgufPage,
  layaGgufPreset,
} from '@/common/kyrn/layaGguf.ts';

/**
 * The GGUF builds are the ones ggml-org publishes of the English checkpoint, plus a `custom` slot for a file the
 * person already has. These tests pin the preset table and the download-URL builder that the manager and the panel
 * share, so a rename in one place breaks loudly in the other.
 */
describe('layaGguf presets', () => {
  it('offers the published builds and a custom slot, defaulting to Q8_0', () => {
    expect(LAYA_GGUF_PRESETS.map((preset) => preset.id)).toEqual(['gguf-q8', 'gguf-bf16', 'gguf-custom']);
    expect(LAYA_GGUF_DEFAULT_ID).toBe('gguf-q8');
  });

  it('falls back to the first build for an unknown id', () => {
    expect(layaGgufPreset('nope').id).toBe('gguf-q8');
    expect(layaGgufPreset().id).toBe('gguf-q8');
  });

  it('names no repository for the custom build', () => {
    expect(layaGgufPreset('gguf-custom').repo).toBe('');
    expect(layaGgufPreset('gguf-custom').files).toHaveLength(0);
  });

  it('pins a hash only for the build that was hashed here', () => {
    expect(layaGgufPreset('gguf-q8').files[0]?.sha256).toBeTypeOf('string');
    // The BF16 build was never hashed here; it is then checked by size alone.
    expect(layaGgufPreset('gguf-bf16').files[0]?.sha256).toBeUndefined();
  });
});

describe('layaGguf download URLs', () => {
  const name = 'Laya-Q8_0.gguf';
  const repoPath = 'ggml-org/Laya-GGUF/resolve/22265007700297ba9e128297e82540cf28c5d7d4/Laya-Q8_0.gguf';

  it('builds the Hugging Face link, the mirror link and the ModelScope link', () => {
    expect(layaGgufFileUrl(name, 'gguf-q8', 'huggingface')).toBe(`https://huggingface.co/${repoPath}?download=true`);
    expect(layaGgufFileUrl(name, 'gguf-q8', 'hf-mirror')).toBe(`https://hf-mirror.com/${repoPath}?download=true`);
    expect(layaGgufFileUrl(name, 'gguf-q8', 'modelscope')).toBe(`https://modelscope.cn/models/${repoPath}`);
  });

  it('builds a custom source from the base the person types', () => {
    expect(layaGgufFileUrl(name, 'gguf-q8', 'custom', 'https://mirror.example/')).toBe(
      `https://mirror.example/${repoPath}?download=true`
    );
  });

  it('falls back to Hugging Face for an unknown source', () => {
    expect(layaGgufFileUrl(name, 'gguf-q8', 'nope')).toBe(layaGgufFileUrl(name, 'gguf-q8', 'huggingface'));
  });

  it('points the page at the repository tree for a published build, and nowhere for custom', () => {
    expect(layaGgufPage('gguf-q8')).toBe(
      'https://huggingface.co/ggml-org/Laya-GGUF/tree/22265007700297ba9e128297e82540cf28c5d7d4'
    );
    expect(layaGgufPage('gguf-custom')).toBe('');
  });
});
