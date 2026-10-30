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

// 与后端 queuePreview 同规则归一（折叠空白+80字截断），用于排队条目与对话气泡的匹配
function normPreview(c) {
  const one = (c || '').trim().replace(/\s+/g, ' ')
  return one.length > 80 ? one.slice(0, 80) + '…' : one
}

export default function ChatHistory({ instanceId, instanceName, onBack }) {
  const [messages, setMessages] = useState([])
  const [loading, setLoading] = useState(true)
  const [autoRefresh, setAutoRefresh] = useState(true)
  const [queue, setQueue] = useState([])
  const [flashKeys, setFlashKeys] = useState(() => new Set())   // 刚被消费的排队消息 → 进对话流高亮
  const prevQueueRef = useRef([])
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

  // 排队快照 2s 轮询；diff 出"刚消费"的条目做进流动效
  async function loadQueue() {
    try {
      const res = await fetch(`/api/instances/${instanceId}/queue`)
      if (!res.ok) return
      const q = await res.json()
      const prev = prevQueueRef.current
      const nowKeys = new Set(q.map(x => x.preview))
      const consumed = prev.filter(p => !nowKeys.has(p.preview))
      if (consumed.length) {
        setFlashKeys(s => { const n = new Set(s); consumed.forEach(c => n.add(c.preview)); return n })
        setTimeout(() => setFlashKeys(s => {
          const n = new Set(s); consumed.forEach(c => n.delete(c.preview)); return n
        }), 2500)
      }
      prevQueueRef.current = q
      setQueue(q)
    } catch { /* 网络抖动忽略，下轮再来 */ }
  }

  useEffect(() => {
    load(); loadQueue()
    const t1 = autoRefresh ? setInterval(load, 3000) : null
    const t2 = setInterval(loadQueue, 2000)
    return () => { if (t1) clearInterval(t1); clearInterval(t2) }
  }, [instanceId, autoRefresh])

  // 自动滚动到底部
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages, queue])

  const waitingSet = new Set(queue.map(q => q.preview))

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

      <div style={{ display: 'flex', gap: 12, alignItems: 'stretch' }}>
      <div
        ref={scrollRef}
        style={{
          flex: 1,
          minWidth: 0,
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
          const np = normPreview(msg.content)
          if (isUser && waitingSet.has(np)) return null   // 还在排队的消息：只在右列排队面板显示
          const flashed = isUser && flashKeys.has(np)
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
                  fontSize: 12,
                  color: 'var(--text-dim)',
                  marginBottom: '3px',
                  textAlign: isUser ? 'right' : 'left',
                }}>
                  {isUser ? '👤 用户' : '🤖 机器人'} · {formatTime(msg.createdAt)}
                  {msg.chatType === 'group' && ' · 群聊'}
                  {flashed && <span style={{ color: '#d97706', marginLeft: 6 }}>⚡ 刚消费入会话</span>}
                </div>
                {/* 消息气泡 */}
                <div style={{
                  background: flashed ? '#fef3c7' : (isUser ? '#eff6ff' : '#f0fdf4'),
                  border: `1px solid ${flashed ? '#f59e0b' : (isUser ? '#bfdbfe' : '#bbf7d0')}`,
                  borderRadius: '10px',
                  padding: '10px 14px',
                  fontSize: '13px',
                  color: '#1e293b',
                  transition: 'background 1.5s ease, border-color 1.5s ease',
                }}>
                  {renderContent(msg.content)}
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {/* 右列：排队面板（10-30-instance-queue-panel） */}
      <div style={{
        width: 250, flexShrink: 0,
        height: 'calc(100vh - 150px)', overflowY: 'auto',
        background: 'var(--bg-card)', border: '1px solid var(--border)',
        borderRadius: '8px', padding: '12px',
      }}>
        <div style={{ fontSize: 13, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 6 }}>
          {queue.length > 0
            ? <><span className="spinner" style={{ width: 10, height: 10 }} />⏳ 排队中 <span style={{ color: '#d97706' }}>{queue.length}</span> 条</>
            : <>✅ 队列畅通</>}
        </div>
        <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 4, lineHeight: 1.5 }}>
          {queue.length > 0 ? '上一轮跑完会按顺序逐条进入会话' : '没有等待消费的消息'}
        </div>

        {/* 最新队头在顶（ticket 降序显示，消费顺序仍是 FIFO） */}
        {queue.slice().reverse().map(q => (
          <div key={q.ticket} style={{
            marginTop: 8, padding: '8px 10px', borderRadius: 8,
            background: 'rgba(245,158,11,.07)', border: '1px solid rgba(245,158,11,.28)',
          }}>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 11, color: '#b45309', marginBottom: 4 }}>
              <span className="hook-badge" style={{ fontSize: 10, padding: '0 6px' }}>{q.kind}</span>
              <span>已等 {q.waitedSec}s</span>
            </div>
            <div style={{ fontSize: 12, color: 'var(--text)', wordBreak: 'break-all', lineHeight: 1.5 }} title={q.preview}>
              {q.preview.length > 66 ? q.preview.slice(0, 66) + '…' : q.preview}
            </div>
          </div>
        ))}

        {queue.length > 0 && (
          <div style={{ marginTop: 10, fontSize: 10.5, color: 'var(--text-dim)', lineHeight: 1.6 }}>
            💡 排队为内存态：后端重启会清空，未消费的消息请重发
          </div>
        )}
      </div>
      </div>
    </div>
  )
}
