import { useState, useEffect, useRef, useCallback } from 'react'

const LEVELS = ['ALL', 'INFO', 'WARN', 'ERROR', 'DEBUG']

export default function Logs() {
  const [logs, setLogs] = useState([])
  const [filter, setFilter] = useState('ALL')
  const [paused, setPaused] = useState(false)
  const viewerRef = useRef(null)
  const pausedRef = useRef(false)

  useEffect(() => { pausedRef.current = paused }, [paused])

  // 加载历史日志
  useEffect(() => {
    fetch('/api/logs/history')
      .then(r => r.json())
      .then(data => setLogs(data))
      .catch(() => {})
  }, [])

  // SSE 实时日志
  useEffect(() => {
    const es = new EventSource('/api/logs')
    es.onmessage = (e) => {
      if (pausedRef.current) return
      try {
        const entry = JSON.parse(e.data)
        setLogs(prev => {
          const next = [...prev, entry]
          return next.length > 500 ? next.slice(-500) : next
        })
      } catch {}
    }
    return () => es.close()
  }, [])

  // 自动滚动
  useEffect(() => {
    if (viewerRef.current && !paused) {
      viewerRef.current.scrollTop = viewerRef.current.scrollHeight
    }
  }, [logs, paused])

  const filtered = filter === 'ALL' ? logs : logs.filter(l => l.level === filter)

  const formatTime = useCallback((ts) => {
    if (!ts) return ''
    const d = new Date(ts)
    return d.toLocaleTimeString('zh-CN', { hour12: false })
  }, [])

  return (
    <div>
      <h1 className="page-title">运行日志</h1>

      <div className="log-toolbar">
        {LEVELS.map(l => (
          <button key={l}
            className={`log-filter${filter === l ? ' active' : ''}`}
            onClick={() => setFilter(l)}>
            {l}
          </button>
        ))}
        <div style={{ flex: 1 }} />
        <button className="btn" style={{ padding: '4px 12px', fontSize: 12 }}
          onClick={() => setPaused(p => !p)}>
          {paused ? '▶ 恢复' : '⏸ 暂停'}
        </button>
        <button className="btn" style={{ padding: '4px 12px', fontSize: 12 }}
          onClick={() => setLogs([])}>
          清空
        </button>
        <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>
          {filtered.length} 条
        </span>
      </div>

      <div className="log-viewer" ref={viewerRef}>
        {filtered.length === 0 ? (
          <div style={{ color: 'var(--text-dim)', textAlign: 'center', padding: 40 }}>
            暂无日志
          </div>
        ) : (
          filtered.map((log, i) => (
            <div className="log-entry" key={i}>
              <span className="log-time">{formatTime(log.timestamp)}</span>
              <span className={`log-level ${log.level}`}>{log.level}</span>
              <span className="log-logger">{log.logger}</span>
              <span className="log-msg">{log.message}</span>
            </div>
          ))
        )}
      </div>
    </div>
  )
}
