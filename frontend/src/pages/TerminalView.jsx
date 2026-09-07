import { useEffect, useRef, useState } from 'react'
import { Terminal as XTerm } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'

export default function TerminalView({ instanceId, workDir, onBack }) {
  const hostRef = useRef(null)
  const termRef = useRef(null)
  const wsRef = useRef(null)

  useEffect(() => {
    if (!hostRef.current) return

    const term = new XTerm({
      fontFamily: 'ui-monospace, "SF Mono", "JetBrains Mono", Menlo, Consolas, monospace',
      fontSize: 13,
      lineHeight: 1.2,
      cursorBlink: true,
      cursorStyle: 'block',
      scrollback: 5000,
      convertEol: false,
      theme: {
        background: '#0f172a',
        foreground: '#cbd5e1',
        cursor: '#cbd5e1',
        selectionBackground: '#475569',
      },
    })

    const fit = new FitAddon()
    term.loadAddon(fit)
    term.open(hostRef.current)
    try { fit.fit() } catch {}

    termRef.current = term

    // WebSocket 连接
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${location.host}/ws/terminal`)
    wsRef.current = ws

    ws.onopen = () => {
      // 请求打开 PTY
      ws.send(JSON.stringify({
        type: 'open',
        instanceId: instanceId,
        workDir: workDir || '',
      }))
    }

    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data)
        switch (msg.type) {
          case 'connected':
            term.writeln('\x1b[32m[终端已连接]\x1b[0m\r\n')
            term.focus()
            break
          case 'output':
            term.write(msg.data)
            break
          case 'exit':
            term.writeln('\r\n\x1b[33m[进程已退出]\x1b[0m')
            break
          case 'error':
            term.writeln('\x1b[31m[错误] ' + (msg.message || '') + '\x1b[0m')
            break
        }
      } catch {}
    }

    ws.onclose = () => {
      term.writeln('\r\n\x1b[33m[连接已断开]\x1b[0m')
    }

    // 键盘输入 → 发送到后端 PTY
    term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'input', data }))
      }
    })

    // 自适应大小
    const ro = new ResizeObserver(() => {
      try {
        fit.fit()
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }))
        }
      } catch {}
    })
    ro.observe(hostRef.current)

    return () => {
      ro.disconnect()
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'close' }))
        ws.close()
      }
      term.dispose()
    }
  }, [instanceId])

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
        <button className="btn" onClick={onBack} style={{ fontSize: '13px', padding: '6px 14px' }}>
          ← 返回实例
        </button>
        <h1 className="page-title" style={{ margin: 0, fontSize: '16px' }}>
          🖥️ 终端
        </h1>
      </div>

      <div
        ref={hostRef}
        tabIndex={0}
        style={{
          background: '#0f172a',
          height: 'calc(100vh - 150px)',
          minHeight: '420px',
          padding: '6px 8px',
          borderRadius: '10px',
          overflow: 'hidden',
          outline: 'none',
          boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
        }}
      />
    </div>
  )
}
