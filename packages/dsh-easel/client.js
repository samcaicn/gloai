window.__ModuleLoader__.load({
  id: 'dsh-easel',
  factory: (require) => {
    const module = { exports: {} }
    const exports = module.exports
    Object.defineProperty(exports, Symbol.toStringTag, { value: 'Module' })
    const React = require('react')

    const NS = 'tools.easel'
    const EASEL_URL = 'http://127.0.0.1:7860'

    const en = {
      nav: 'Easel',
      title: 'Easel',
      intro:
        'Easel is an open-source AI social-media agent for discovering trends, producing content, and publishing to Xiaohongshu, Douyin, Zhihu, Bilibili and more. AiMarketing launches it as a companion process and opens its workspace in a dedicated window.',
      open: 'Open Easel',
      opening: 'Opening Easel…',
      prerequisiteTitle: 'First-time setup',
      prerequisite:
        'Create the Easel virtual environment once: run packages/easel/setup.sh (macOS/Linux) or setup.ps1 (Windows), then set API keys in packages/easel/.env. The chat path also needs "easel gateway start".',
      repository: 'View Easel on GitHub',
      failed: 'Easel could not start',
      preview: 'Inline preview',
      previewHint: 'If the preview is blocked by the browser sandbox, use "Open Easel" to load it in its own window.'
    }

    const zh = {
      nav: 'Easel',
      title: 'Easel',
      intro:
        'Easel 是一个开源的 AI 社交媒体智能体，用于发现热点、创作内容并一键发布到小红书、抖音、知乎、哔哩哔哩等平台。AiMarketing 将其作为伴随进程拉起，并在独立窗口中打开其工作台。',
      open: '打开 Easel',
      opening: '正在打开 Easel…',
      prerequisiteTitle: '首次使用准备',
      prerequisite:
        '首次需创建 Easel 虚拟环境：运行 packages/easel/setup.sh（macOS/Linux）或 setup.ps1（Windows），并在 packages/easel/.env 中配置 API key。对话能力还需先执行 "easel gateway start"。',
      repository: '在 GitHub 查看 Easel',
      failed: 'Easel 启动失败',
      preview: '内嵌预览',
      previewHint: '若预览被浏览器沙箱拦截，请点击"打开 Easel"在其独立窗口中加载。'
    }

    const css = `
      .dshEaselSection{box-sizing:border-box;max-width:880px;color:var(--dsw-alias-label-primary);display:flex;flex-direction:column;gap:16px}
      .dshEaselTitle{margin:0;font-size:20px;font-weight:600;line-height:30px}
      .dshEaselIntro{margin:0;color:var(--dsw-alias-label-secondary);font-size:14px;line-height:22px}
      .dshEaselCard{box-sizing:border-box;border:1px solid var(--dsw-alias-border-l2);background:var(--dsw-alias-bg-module-platform);border-radius:14px;padding:22px;display:flex;flex-direction:column;gap:16px}
      .dshEaselActions{display:flex;flex-wrap:wrap;align-items:center;gap:10px}
      .dshEaselButton{box-sizing:border-box;height:36px;padding:0 16px;border:1px solid transparent;border-radius:18px;font:inherit;font-size:14px;font-weight:500;cursor:pointer;color:var(--dsw-alias-label-primary-foreground);background:var(--dsw-alias-button-primary-fill)}
      .dshEaselButton:hover:not(:disabled){background:var(--dsw-alias-button-primary-hover)}
      .dshEaselButton:disabled{cursor:default;opacity:.5}
      .dshEaselButton:focus-visible{outline:none;box-shadow:0 0 0 2px var(--dsw-alias-border-l3)}
      .dshEaselLink{color:var(--dsw-alias-label-secondary);border-radius:6px;padding:5px 4px;font-size:13px;line-height:20px;text-decoration:none}
      .dshEaselLink:hover{color:var(--dsw-alias-label-primary);text-decoration:underline}
      .dshEaselNotice{margin:0;padding-top:14px;border-top:1px solid var(--dsw-alias-border-l2);color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:19px}
      .dshEaselError{color:var(--dsw-alias-state-error-primary);font-size:13px;line-height:20px}
      .dshEaselPreview{box-sizing:border-box;border:1px solid var(--dsw-alias-border-l2);border-radius:12px;overflow:hidden;background:var(--dsw-alias-bg-layer-1)}
      .dshEaselPreviewFrame{display:block;width:100%;height:560px;border:0}
      .dshEaselPreviewHint{margin:8px 0 0;color:var(--dsw-alias-label-tertiary);font-size:12px;line-height:19px}
      .dshEaselSpinner{box-sizing:border-box;width:16px;height:16px;border:2px solid var(--dsw-alias-border-l2);border-top-color:var(--dsw-alias-label-primary);border-radius:50%;animation:dshEaselSpin .75s linear infinite}
      .dshEaselBusy{display:flex;align-items:center;gap:9px}
      @keyframes dshEaselSpin{to{transform:rotate(360deg)}}
      @media (prefers-reduced-motion:reduce){.dshEaselSpinner{animation:none}}
    `

    function installStyles() {
      if (document.querySelector('style[data-plugin-css="dsh-easel"]')) return
      const style = document.createElement('style')
      style.dataset.plugin = 'dsh-easel'
      style.dataset.pluginCss = 'dsh-easel'
      style.textContent = css
      document.head.appendChild(style)
    }

    async function openEasel(setError) {
      const bridge = globalThis.dshDesktop
      if (bridge && typeof bridge.openEasel === 'function') {
        const result = await bridge.openEasel()
        if (!result.ok && result.detail) setError(result.detail)
        return result.ok
      }
      // Fallback: open the loopback workspace in the system browser.
      window.open(EASEL_URL, '_blank', 'noopener,noreferrer')
      return true
    }

    function EaselSection({ t }) {
      const [busy, setBusy] = React.useState(false)
      const [error, setError] = React.useState()

      const onOpen = async () => {
        setError(undefined)
        setBusy(true)
        try {
          await openEasel(setError)
        } catch (failure) {
          setError(failure instanceof Error ? failure.message : String(failure))
        } finally {
          setBusy(false)
        }
      }

      return React.createElement(
        'section',
        { className: 'dshEaselSection' },
        React.createElement('h2', { className: 'dshEaselTitle' }, t('title')),
        React.createElement('p', { className: 'dshEaselIntro' }, t('intro')),
        React.createElement(
          'div',
          { className: 'dshEaselCard' },
          React.createElement(
            'div',
            { className: 'dshEaselActions' },
            React.createElement(
              'button',
              {
                type: 'button',
                className: 'dshEaselButton',
                disabled: busy,
                onClick: () => void onOpen()
              },
              busy
                ? React.createElement(
                  'span',
                  { className: 'dshEaselBusy' },
                  React.createElement('span', { className: 'dshEaselSpinner', 'aria-hidden': 'true' }),
                  React.createElement('span', null, t('opening'))
                )
                : t('open')
            ),
            React.createElement(
              'a',
              {
                className: 'dshEaselLink',
                href: 'https://github.com/ZJU-REAL/Easel',
                target: '_blank',
                rel: 'noopener noreferrer'
              },
              t('repository')
            )
          ),
          error
            ? React.createElement('p', { className: 'dshEaselError' }, `${t('failed')}: ${error}`)
            : null,
          React.createElement(
            'div',
            { className: 'dshEaselPreview' },
            React.createElement('iframe', {
              className: 'dshEaselPreviewFrame',
              src: EASEL_URL,
              title: t('preview'),
              loading: 'lazy',
              allow: 'clipboard-read; clipboard-write'
            })
          ),
          React.createElement('p', { className: 'dshEaselPreviewHint' }, t('previewHint'))
        ),
        React.createElement('p', { className: 'dshEaselNotice' }, `${t('prerequisiteTitle')} — ${t('prerequisite')}`)
      )
    }

    const inject = ['slots', 'locale']
    function apply(ctx) {
      installStyles()
      ctx.effect(
        () => ctx.locale.register(NS, { zh, en }),
        'dsh-easel: copy dictionaries'
      )
      const t = ctx.locale.bind(NS)
      ctx.slots.inject('settings.section', () =>
        ctx.slots.register(
          {
            name: 'settings.section',
            id: 'easel',
            order: 45,
            label: () => t('nav'),
            inject: () => ({ t })
          },
          EaselSection
        )
      )
    }

    exports.apply = apply
    exports.inject = inject
    return module.exports
  }
})
