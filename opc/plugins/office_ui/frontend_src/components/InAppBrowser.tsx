import { useEffect, useState } from 'react'

interface InAppBrowserProps {
  url: string
  title?: string
  onClose: () => void
}

/**
 * Built-in (in-app) browser overlay.
 *
 * Renders an external URL inside the SafeOPC window via an iframe so skills
 * like CreatorHub can "open the page" without spawning the OS default browser.
 * The URL is opened here when the backend broadcasts a `ui_open_browser` event
 * (triggered by e.g. `opc-creatorhub open`).
 */
export function InAppBrowser({ url, title = '内置浏览器', onClose }: InAppBrowserProps) {
  const [currentUrl, setCurrentUrl] = useState(url)
  const [address, setAddress] = useState(url)

  useEffect(() => {
    setCurrentUrl(url)
    setAddress(url)
  }, [url])

  const handleNavigate = () => {
    const next = address.trim()
    if (next) setCurrentUrl(next)
  }

  const openExternal = () => {
    try {
      window.open(currentUrl, '_blank', 'noopener,noreferrer')
    } catch {
      /* ignore */
    }
  }

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 1000,
        background: '#0f1115',
        display: 'flex',
        flexDirection: 'column',
        color: '#e6edf3',
        fontFamily: '-apple-system, "Segoe UI", Roboto, "Microsoft YaHei", sans-serif',
      }}
      role="dialog"
      aria-label="内置浏览器"
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '8px 12px',
          background: '#161b22',
          borderBottom: '1px solid #30363d',
        }}
      >
        <span style={{ fontWeight: 700, fontSize: 13, whiteSpace: 'nowrap' }}>{title}</span>
        <input
          value={address}
          onChange={(e) => setAddress(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleNavigate()
          }}
          spellCheck={false}
          style={{
            flex: 1,
            background: '#0d1117',
            border: '1px solid #30363d',
            borderRadius: 6,
            color: '#e6edf3',
            padding: '6px 10px',
            fontSize: 13,
          }}
          aria-label="地址栏"
        />
        <button
          onClick={handleNavigate}
          title="跳转"
          style={btnStyle}
        >
          前往
        </button>
        <button
          onClick={openExternal}
          title="在外部浏览器打开"
          style={btnStyle}
        >
          外部打开
        </button>
        <button
          onClick={onClose}
          title="关闭"
          style={{ ...btnStyle, color: '#ff7b72' }}
        >
          ✕
        </button>
      </div>
      <iframe
        key={currentUrl}
        src={currentUrl}
        title={title}
        referrerPolicy="no-referrer"
        style={{ flex: 1, border: 'none', width: '100%', background: '#ffffff' }}
      />
    </div>
  )
}

const btnStyle: React.CSSProperties = {
  background: '#21262d',
  border: '1px solid #30363d',
  borderRadius: 6,
  color: '#e6edf3',
  padding: '6px 12px',
  fontSize: 13,
  cursor: 'pointer',
}
