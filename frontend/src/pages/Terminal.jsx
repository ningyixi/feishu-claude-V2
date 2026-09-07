import { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'

export default function TerminalPage() {
  const hostRef = useRef(null)
  const termRef = useRef(null)
  const fitRef = useRef(null)
  const wsRef = useRef(null)
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    if (!hostRef.current) return

    // 创建 xterm 实例（与 claude-bridge 完全一致的配置）
    const term = new XTerm({
      fontFamily: 'ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace',
      fontSize: 12,
      lineHeight: 1.2,
      cursorBlink: true,
      convertEol: false,
      scrollback: 2000,
      theme: {
        background: '#0f172a',
        foreground: '#cbd5e1',
        cursor: '#cbd5e1',
      },
    })
    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(hostRef.current)

    try { fit.fit() } catch {}

    termRef.current = term
    fitRef.current = fit

    // ResizeObserver 自适应容器
    const ro = new ResizeObserver(() => {
      try { fit.fit() } catch {}
    })
    ro.observe(hostRef.current)

    // WebSocket 连接
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const wsUrl = `${proto}://${location.host}/ws/terminal`

    function connect() {
      const ws = new WebSocket(wsUrl)
      wsRef.current = ws

      ws.onopen = () => {
        setConnected(true)
        term.write('\x1b[32m[已连接]\x1b[0m\r\n')
      }

      ws.onmessage = (ev) => {
        // 直接写入 xterm（xterm 自己解释所有 ANSI 控制序列）
        term.write(ev.data)
      }

      ws.onclose = () => {
        setConnected(false)
        term.write('\r\n\x1b[33m[连接断开，2秒后重连...]\x1b[0m\r\n')
        setTimeout(connect, 2000)
      }

      ws.onerror = () => ws.close()
    }

    connect()

    return () => {
      ro.disconnect()
      if (wsRef.current) wsRef.current.close()
      term.dispose()
    }
  }, [])

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '1rem' }}>
        <h1 className="page-title" style={{ margin: 0, display: 'flex', alignItems: 'center', gap: '12px' }}>
          🖥️ 终端
          <span style={{
            fontSize: '0.75rem',
            padding: '2px 8px',
            borderRadius: '4px',
            background: connected ? '#10b98115' : '#ef444415',
            color: connected ? '#10b981' : '#ef4444',
            fontWeight: 500,
          }}>
            {connected ? '● 已连接' : '● 未连接'}
          </span>
        </h1>
      </div>

      {/* PTY 容器：与 claude-bridge 一致的样式 */}
      <div
        ref={hostRef}
        style={{
          background: '#0f172a',
          height: 'calc(100vh - 160px)',
          minHeight: '420px',
          padding: '6px 8px',
          borderRadius: '10px',
          overflow: 'hidden',
          outline: 'none',
          boxShadow: '0 4px 12px rgba(0,0,0,0.1)',
        }}
      />
    </div>
  )
}
