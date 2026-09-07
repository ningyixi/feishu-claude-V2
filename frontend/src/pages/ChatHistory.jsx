import { useState, useEffect, useRef } from 'react'

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

function renderContent(content) {
  if (!content) return null

  // 尝试解析 JSON
  if (content.trim().startsWith('{') || content.trim().startsWith('[')) {
    try {
      const parsed = JSON.parse(content.trim())
      return (
        <pre style={{
          background: '#f8fafc',
          border: '1px solid #e2e8f0',
          borderRadius: '6px',
          padding: '10px 12px',
          fontSize: '12px',
          fontFamily: 'var(--font-mono)',
          overflow: 'auto',
          maxHeight: '400px',
          margin: '6px 0 0',
          lineHeight: '1.5',
          color: '#334155',
        }}>
          {JSON.stringify(parsed, null, 2)}
        </pre>
      )
    } catch {}
  }

  // 检查是否包含命令行输出（多行等宽文本）
  const lines = content.split('\n')
  const hasCodeLike = lines.some(l => l.startsWith('  ') || l.startsWith('$') || l.startsWith('>'))

  if (hasCodeLike && lines.length > 2) {
    return (
      <pre style={{
        background: '#1e293b',
        color: '#e2e8f0',
        borderRadius: '6px',
        padding: '10px 12px',
        fontSize: '12px',
        fontFamily: 'var(--font-mono)',
        overflow: 'auto',
        maxHeight: '400px',
        margin: '6px 0 0',
        lineHeight: '1.6',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
      }}>
        {content}
      </pre>
    )
  }

  // 普通文本
  return (
    <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', lineHeight: '1.6' }}>
      {content}
    </div>
  )
}

export default function ChatHistory({ instanceId, instanceName, onBack }) {
  const [messages, setMessages] = useState([])
  const [loading, setLoading] = useState(true)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const scrollRef = useRef(null)

  async function load() {
    try {
      const res = await fetch(`/api/instances/${instanceId}/messages`)
      const data = await res.json()
      setMessages(data)
    } catch (e) {
      console.error('加载对话历史失败:', e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    const interval = autoRefresh ? setInterval(load, 3000) : null
    return () => { if (interval) clearInterval(interval) }
  }, [instanceId, autoRefresh])

  // 自动滚动到底部
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages])

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '12px' }}>
        <button className="btn" onClick={onBack} style={{ fontSize: '13px', padding: '6px 14px' }}>
          ← 返回
        </button>
        <h1 className="page-title" style={{ margin: 0, fontSize: '16px', flex: 1 }}>
          💬 {instanceName || '对话历史'}
        </h1>
        <label style={{ fontSize: '12px', color: 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: '4px', cursor: 'pointer' }}>
          <input type="checkbox" checked={autoRefresh} onChange={e => setAutoRefresh(e.target.checked)} />
          自动刷新
        </label>
        <button className="btn" onClick={load} style={{ fontSize: '12px', padding: '4px 10px' }}>刷新</button>
      </div>

      <div
        ref={scrollRef}
        style={{
          height: 'calc(100vh - 150px)',
          overflowY: 'auto',
          background: 'var(--bg-card)',
          border: '1px solid var(--border)',
          borderRadius: '8px',
          padding: '16px',
        }}
      >
        {loading && <div style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '2rem' }}>加载中...</div>}

        {!loading && messages.length === 0 && (
          <div style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '2rem' }}>
            暂无对话记录
          </div>
        )}

        {messages.map((msg, i) => {
          const isUser = msg.role === 'user'
          return (
            <div
              key={msg.id || i}
              style={{
                display: 'flex',
                justifyContent: isUser ? 'flex-end' : 'flex-start',
                marginBottom: '12px',
              }}
            >
              <div style={{
                maxWidth: '80%',
                minWidth: '120px',
              }}>
                {/* 时间标签 */}
                <div style={{
                  fontSize: '11px',
                  color: 'var(--text-dim)',
                  marginBottom: '3px',
                  textAlign: isUser ? 'right' : 'left',
                }}>
                  {isUser ? '👤 用户' : '🤖 机器人'} · {formatTime(msg.createdAt)}
                  {msg.chatType === 'group' && ' · 群聊'}
                </div>
                {/* 消息气泡 */}
                <div style={{
                  background: isUser ? '#eff6ff' : '#f0fdf4',
                  border: `1px solid ${isUser ? '#bfdbfe' : '#bbf7d0'}`,
                  borderRadius: '10px',
                  padding: '10px 14px',
                  fontSize: '13px',
                  color: '#1e293b',
                }}>
                  {renderContent(msg.content)}
                </div>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
