import { useState, useEffect, useRef, useCallback } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const TOOL_STATUS = {
  running: { label: '执行中', color: '#0891b2' },
  success: { label: '成功', color: '#16a34a' },
  error: { label: '失败', color: '#dc2626' },
}

function fmtElapsed(ms) {
  if (!ms || ms <= 0) return ''
  const s = ms / 1000
  if (s < 60) return s.toFixed(1) + 's'
  return Math.floor(s / 60) + 'm ' + Math.round(s % 60) + 's'
}

function fmtTokens(n) {
  if (n == null || n < 0) return null
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'm'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

export default function E2ETest() {
  const [instances, setInstances] = useState([])
  const [testerId, setTesterId] = useState('')
  const [targetId, setTargetId] = useState('')
  const [tasks, setTasks] = useState([])
  const [activeTaskId, setActiveTaskId] = useState(null)
  const [taskInfo, setTaskInfo] = useState(null)
  const [turns, setTurns] = useState([])
  const [connected, setConnected] = useState(false)
  const [input, setInput] = useState('')
  const [brief, setBrief] = useState(null)
  const [showBrief, setShowBrief] = useState(false)
  const [modal, setModal] = useState(null)
  const [toast, setToast] = useState(null)

  const wsRef = useRef(null)
  const deltaBuf = useRef({})
  const flushTimer = useRef(null)
  const scrollRef = useRef(null)
  const stickBottom = useRef(true)

  // ===== 数据加载 =====
  useEffect(() => { loadInstances(); loadTasks() }, [])

  async function loadInstances() {
    try {
      const res = await fetch('/api/instances')
      setInstances(await res.json())
    } catch (e) { showToast('加载实例失败: ' + e.message, 'error') }
  }

  async function loadTasks() {
    try {
      const res = await fetch('/api/e2e/tasks')
      setTasks(await res.json())
    } catch (e) { /* 静默 */ }
  }

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  // 选中被测实例后拉背景简报
  useEffect(() => {
    const id = activeTaskId
      ? (tasks.find(t => t.id === activeTaskId)?.targetInstanceId)
      : targetId
    if (!id) { setBrief(null); return }
    let alive = true
    fetch(`/api/e2e/brief/${id}`)
      .then(r => r.ok ? r.json() : null)
      .then(d => { if (alive && d) setBrief(d) })
      .catch(() => {})
    return () => { alive = false }
  }, [targetId, activeTaskId, tasks])

  // ===== WebSocket =====
  useEffect(() => {
    if (!activeTaskId) { setTurns([]); setTaskInfo(null); setConnected(false); return }
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${window.location.host}/ws/e2e?taskId=${activeTaskId}`)
    wsRef.current = ws
    ws.onopen = () => setConnected(true)
    ws.onclose = () => setConnected(false)
    ws.onerror = () => setConnected(false)
    ws.onmessage = (ev) => {
      try { handleEvent(JSON.parse(ev.data)) } catch (e) { console.error('WS 事件解析失败', e) }
    }
    return () => { ws.onclose = null; ws.close(); wsRef.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeTaskId])

  const scheduleFlush = useCallback(() => {
    if (flushTimer.current) return
    flushTimer.current = setTimeout(() => {
      flushTimer.current = null
      const buf = deltaBuf.current
      const keys = Object.keys(buf)
      if (keys.length === 0) return
      deltaBuf.current = {}
      setTurns(prev => {
        const arr = [...prev]
        for (const idx of keys) {
          const i = Number(idx)
          if (!arr[i]) continue
          arr[i] = {
            ...arr[i],
            answer: arr[i].answer + (buf[idx].answer || ''),
            thinking: arr[i].thinking + (buf[idx].thinking || ''),
          }
        }
        return arr
      })
    }, 150)   // markdown 重渲染较贵，150ms 批量 flush
  }, [])

  function handleEvent(e) {
    switch (e.type) {
      case 'snapshot': {
        setTaskInfo(e.task)
        setTurns((e.turns || []).map(t => ({
          userText: t.userText || '',
          thinking: t.thinking || '',
          answer: t.answer || '',
          tools: t.tools || [],
          result: t.result || null,
          error: t.errorMessage || null,
          aborted: !!t.aborted,
        })))
        deltaBuf.current = {}
        break
      }
      case 'turn_start': {
        setTaskInfo(prev => prev ? { ...prev, busy: true } : prev)
        setTurns(prev => {
          const arr = [...prev]
          arr[e.turnIndex] = {
            userText: e.userText || '', thinking: '', answer: '',
            tools: [], result: null, error: null, aborted: false,
          }
          return arr
        })
        break
      }
      case 'queued':
        showToast('⏳ 上一轮还在执行，本条已排队', 'info')
        break
      case 'thinking':
      case 'delta': {
        const i = e.turnIndex
        deltaBuf.current[i] = deltaBuf.current[i] || { answer: '', thinking: '' }
        deltaBuf.current[i][e.type === 'delta' ? 'answer' : 'thinking'] += e.text || ''
        scheduleFlush()
        break
      }
      case 'tool': {
        setTurns(prev => {
          const arr = [...prev]
          const t = arr[e.turnIndex]
          if (!t) return prev
          const tools = [...t.tools]
          tools[e.toolIndex] = { name: e.name, detail: e.detail, status: e.status, elapsedMs: e.elapsedMs }
          arr[e.turnIndex] = { ...t, tools }
          return arr
        })
        break
      }
      case 'complete': {
        deltaBuf.current[e.turnIndex] = null
        delete deltaBuf.current[e.turnIndex]
        setTaskInfo(prev => prev ? { ...prev, busy: false } : prev)
        loadTasks()
        setTurns(prev => {
          const arr = [...prev]
          const t = arr[e.turnIndex]
          if (!t) return prev
          arr[e.turnIndex] = {
            ...t,
            answer: (e.result && e.result.text) ? e.result.text : t.answer,
            result: e.result,
          }
          return arr
        })
        break
      }
      case 'error': {
        delete deltaBuf.current[e.turnIndex]
        setTaskInfo(prev => prev ? { ...prev, busy: false } : prev)
        loadTasks()
        setTurns(prev => {
          const arr = [...prev]
          const t = arr[e.turnIndex]
          if (!t) return prev
          arr[e.turnIndex] = { ...t, error: e.message, aborted: !!e.aborted }
          return arr
        })
        break
      }
      default: break
    }
  }

  // 自动滚动（贴底时才跟随）
  useEffect(() => {
    const el = scrollRef.current
    if (el && stickBottom.current) el.scrollTop = el.scrollHeight
  }, [turns])

  function onScroll() {
    const el = scrollRef.current
    if (!el) return
    stickBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
  }

  // ===== 创建任务 =====
  function instDir(inst) {
    return (inst?.claudeWorkingDir || inst?.shellWorkingDir || '').trim()
  }

  function handleCreate() {
    if (!testerId || !targetId) { showToast('请先选择测试实例(A)和被测实例(B)', 'error'); return }
    if (testerId === targetId) { showToast('A 和 B 是同一个实例，自己测自己没有意义 😅', 'error'); return }
    const a = instances.find(i => String(i.id) === String(testerId))
    const b = instances.find(i => String(i.id) === String(targetId))
    const dirA = instDir(a), dirB = instDir(b)
    if (dirA && dirB && dirA !== dirB) {
      setModal({
        title: '⚠️ 工作目录不同',
        body: (
          <div style={{ lineHeight: 1.8 }}>
            <div>实例 A「{a?.name}」目录：<code>{dirA}</code></div>
            <div>实例 B「{b?.name}」目录：<code>{dirB}</code></div>
            <div style={{ marginTop: 10 }}>
              测试任务将在 <b>A 的目录</b>里运行。目录不同意味着 A 看不到 B 改的代码，
              建议先到「实例管理」把 A 的 Claude 项目目录改成 B 的目录，再创建任务。
            </div>
          </div>
        ),
        onConfirm: () => { setModal(null); doCreate() },
      })
    } else {
      doCreate()
    }
  }

  async function doCreate() {
    try {
      const res = await fetch('/api/e2e/tasks', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ testerInstanceId: Number(testerId), targetInstanceId: Number(targetId) }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '创建失败')
      showToast('任务已创建，专属测试会话已启动', 'success')
      await loadTasks()
      setActiveTaskId(data.id)
    } catch (e) {
      showToast('创建任务失败: ' + e.message, 'error')
    }
  }

  async function handleDelete(id, ev) {
    ev.stopPropagation()
    if (!confirm('删除此测试任务？专属 Claude 进程将被终止，对话记录丢失。')) return
    try {
      await fetch(`/api/e2e/tasks/${id}`, { method: 'DELETE' })
      if (activeTaskId === id) setActiveTaskId(null)
      loadTasks()
      showToast('任务已删除', 'success')
    } catch (e) { showToast('删除失败', 'error') }
  }

  // ===== 发送消息 =====
  function handleSend() {
    const text = input.trim()
    if (!text || !activeTaskId) return
    const ws = wsRef.current
    if (!ws || ws.readyState !== WebSocket.OPEN) { showToast('连接未就绪，请稍后再试', 'error'); return }
    ws.send(JSON.stringify({ type: 'message', text }))
    setInput('')
    stickBottom.current = true
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend() }
  }

  const activeTask = tasks.find(t => t.id === activeTaskId)

  // ===== 渲染 =====
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 64px)', overflow: 'hidden', gap: 12 }}>
      {/* 顶部：A → B 创建栏 */}
      <div className="card" style={{ padding: '14px 16px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <h1 className="page-title" style={{ margin: 0, fontSize: 18 }}>🧪 E2E 测试工作台</h1>
          <div style={{ flex: 1 }} />
          <InstanceSelect label="测试方 A" value={testerId} onChange={setTesterId} instances={instances} disabled={!!activeTaskId} />
          <span style={{ fontSize: 22, color: 'var(--accent)', fontWeight: 700 }}>➜</span>
          <InstanceSelect label="被测方 B" value={targetId} onChange={setTargetId} instances={instances} disabled={!!activeTaskId} />
          <button className="btn btn-primary" onClick={handleCreate} disabled={!!activeTaskId}>+ 新建测试任务</button>
        </div>

        {/* 任务标签条 */}
        {tasks.length > 0 && (
          <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
            {tasks.map(t => (
              <span
                key={t.id}
                onClick={() => setActiveTaskId(t.id)}
                className={'task-tab' + (t.id === activeTaskId ? ' active' : '')}
                title={`${t.testerName} ➜ ${t.targetName}\n目录: ${t.workingDir}\n模型: ${t.model || '默认'}\n创建于 ${new Date(t.createdAt).toLocaleString()}`}
              >
                {t.busy && <span className="task-dot" />}
                {t.testerName} ➜ {t.targetName}
                <span style={{ opacity: 0.6, marginLeft: 4 }}>({t.turnCount}轮)</span>
                <button className="task-del" onClick={e => handleDelete(t.id, e)}>×</button>
              </span>
            ))}
          </div>
        )}
      </div>

      {/* B 的最近变更（折叠面板） */}
      {brief && (
        <div className="card" style={{ padding: 0 }}>
          <div className="brief-header" onClick={() => setShowBrief(v => !v)}>
            <span>📋 「{brief.instanceName}」的最近变更 <span style={{ color: 'var(--text-dim)', fontWeight: 400 }}>（git + 最近对话，创建任务时自动注入给测试方）</span></span>
            <span>{showBrief ? '▲' : '▼'}</span>
          </div>
          {showBrief && (
            <div className="brief-body">
              <BriefSection title="git log（最近10条提交）" content={brief.gitLog} />
              <BriefSection title="git status（未提交变更）" content={brief.gitStatus} />
              <BriefSection title="git diff --stat（工作区改动统计）" content={brief.gitDiffStat} />
              {brief.recentChats?.length > 0 && (
                <div className="brief-section">
                  <div className="brief-title">最近飞书对话（B 自述做了什么）</div>
                  {brief.recentChats.map((c, i) => (
                    <div key={i} className="brief-chat">
                      <span className={'brief-role ' + (c.role === 'user' ? 'user' : 'bot')}>{c.role === 'user' ? '用户' : 'B'}</span>
                      <span>{c.content}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* 对话区 */}
      <div className="card chat-area" style={{ flex: 1, minHeight: 200, display: 'flex', flexDirection: 'column', padding: 0 }}>
        <div className="chat-header">
          {taskInfo ? (
            <>
              <span>🎯 任务 <code>{taskInfo.id}</code>：{taskInfo.testerName} ➜ {taskInfo.targetName}</span>
              <span style={{ color: 'var(--text-dim)', fontSize: 12 }}>
                目录 {taskInfo.workingDir || '(默认)'} · 模型 {taskInfo.model || '默认'}
              </span>
              {taskInfo.busy && <span className="spinner" title="执行中" />}
              <span className={'conn-badge ' + (connected ? 'on' : 'off')}>
                {connected ? '● 已连接' : '○ 未连接'}
              </span>
            </>
          ) : (
            <span style={{ color: 'var(--text-dim)' }}>选择或新建一个测试任务开始</span>
          )}
        </div>

        <div className="chat-scroll" ref={scrollRef} onScroll={onScroll}>
          {!activeTaskId && (
            <div className="chat-empty">
              <div style={{ fontSize: 40, marginBottom: 12 }}>🧪</div>
              <div>上方选择「测试方 A ➜ 被测方 B」并新建任务</div>
              <div style={{ fontSize: 12, marginTop: 6, color: 'var(--text-dim)' }}>
                任务会启动一个专属 Claude 会话（用 A 的配置），首条消息自动带上 B 的 git 变更和最近对话作为背景
              </div>
            </div>
          )}
          {activeTaskId && turns.length === 0 && (
            <div className="chat-empty">
              <div style={{ fontSize: 32, marginBottom: 10 }}>💬</div>
              <div>输入测试指令开始，例如：</div>
              <div className="chat-example">端到端测试刚才实现的多实例状态功能，先看看改了什么，再设计测试并执行</div>
            </div>
          )}
          {turns.map((t, i) => <TurnView key={i} turn={t} live={taskInfo?.busy && i === turns.length - 1} />)}
        </div>

        {/* 输入区 */}
        <div className="chat-input-bar">
          <textarea
            className="chat-input"
            placeholder={activeTaskId ? '输入测试指令…（Enter 发送，Shift+Enter 换行）' : '请先创建/选择任务'}
            value={input}
            disabled={!activeTaskId || !connected}
            onChange={e => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            rows={2}
          />
          <button className="btn btn-primary" onClick={handleSend} disabled={!activeTaskId || !connected || !input.trim()}>
            发送
          </button>
        </div>
      </div>

      {/* 目录不同提醒弹窗 */}
      {modal && (
        <div className="modal-mask" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={e => e.stopPropagation()}>
            <div className="modal-title">{modal.title}</div>
            <div className="modal-body">{modal.body}</div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="btn" onClick={() => setModal(null)}>取消</button>
              <button className="btn btn-primary" onClick={modal.onConfirm}>仍要继续创建</button>
            </div>
          </div>
        </div>
      )}

      {toast && <div className={`toast toast-${toast.type}`}>{toast.msg}</div>}
    </div>
  )
}

// ===== 子组件 =====

function InstanceSelect({ label, value, onChange, instances, disabled }) {
  return (
    <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
      <span style={{ color: 'var(--text-dim)', whiteSpace: 'nowrap' }}>{label}</span>
      <select
        className="form-input"
        style={{ width: 180, padding: '6px 8px' }}
        value={value}
        disabled={disabled}
        onChange={e => onChange(e.target.value)}
      >
        <option value="">选择实例…</option>
        {instances.filter(i => i.enabled).map(i => (
          <option key={i.id} value={i.id}>{i.name || `#${i.id}`}</option>
        ))}
      </select>
    </label>
  )
}

function BriefSection({ title, content }) {
  if (!content) return null
  return (
    <div className="brief-section">
      <div className="brief-title">{title}</div>
      <pre className="brief-pre">{content}</pre>
    </div>
  )
}

function TurnView({ turn, live }) {
  const [thinkOpen, setThinkOpen] = useState(false)
  const [toolsOpen, setToolsOpen] = useState(true)
  const thinkRef = useRef(null)
  const r = turn.result

  // 思考过程展开时，内容流式增长自动滚到底部（不用手动拖）
  useEffect(() => {
    if (thinkOpen && thinkRef.current) {
      thinkRef.current.scrollTop = thinkRef.current.scrollHeight
    }
  }, [thinkOpen, turn.thinking])

  // 轮次结束后工具面板自动折叠（执行期间保持展开，对照 openclaw-lark 行为）
  useEffect(() => {
    if (r) setToolsOpen(false)
  }, [r])

  const validTools = turn.tools.filter(Boolean)
  const runningCount = validTools.filter(t => t.status === 'running').length

  return (
    <div className="turn">
      {/* 用户消息 */}
      <div className="msg-user">
        <div className="bubble-user">{turn.userText}</div>
      </div>
      {/* 测试输出 */}
      <div className="msg-bot">
        <div className="bubble-bot">
          {turn.thinking && (
            <div className="think-block">
              <span className="think-toggle" onClick={() => setThinkOpen(v => !v)}>
                💭 思考过程 {r?.thinkingMs ? `(${fmtElapsed(r.thinkingMs)})` : (live ? '(进行中)' : '')} {thinkOpen ? '▲' : '▼'}
              </span>
              {thinkOpen && <div className="think-content" ref={thinkRef}>{turn.thinking}</div>}
            </div>
          )}
          {validTools.length > 0 && (
            <div className="tools-panel">
              <div className="panel-header" onClick={() => setToolsOpen(v => !v)}>
                <span>
                  🛠️ 工具调用 · {validTools.length} 步
                  {live && runningCount > 0 && <span style={{ color: '#0891b2' }}> · {runningCount} 个执行中</span>}
                  {r?.toolElapsedMs ? ` · (${fmtElapsed(r.toolElapsedMs)})` : ''}
                </span>
                <span>{toolsOpen ? '▲' : '▼'}</span>
              </div>
              {toolsOpen && (
                <div className="panel-content">
                  {validTools.map((t, i) => (
                    <span key={i} className="tool-badge" title={t.detail}>
                      {t.status === 'running' && <span className="spinner" style={{ width: 10, height: 10, marginRight: 0 }} />}
                      🛠️ {t.name}
                      {t.detail && <span className="tool-detail">{t.detail.length > 40 ? t.detail.slice(0, 40) + '…' : t.detail}</span>}
                      <span style={{ color: TOOL_STATUS[t.status]?.color || 'var(--text-dim)' }}>
                        {TOOL_STATUS[t.status]?.label || t.status}{t.status !== 'running' && t.elapsedMs ? ` · ${fmtElapsed(t.elapsedMs)}` : ''}
                      </span>
                    </span>
                  ))}
                </div>
              )}
            </div>
          )}
          {turn.answer ? (
            <div className="md-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{turn.answer}</ReactMarkdown></div>
          ) : (
            (live && validTools.length === 0 && !turn.thinking) && (
              <span className="typing-hint"><span className="spinner" />正在思考…</span>
            )
          )}
          {live && (
            <div className="live-row">
              <span className="spinner" />
              <span className="live-text">执行中…</span>
              <span className="live-cursor">▊</span>
            </div>
          )}
          {r && (
            <div className={'turn-footer' + (r.isError ? ' error' : '')}>
              {r.isError ? '❌ 出错' : r.aborted ? '⏹ 已停止' : '✅ 已完成'}
              {r.durationMs > 0 && <span> · 耗时 {fmtElapsed(r.durationMs)}</span>}
              {r.model && <span> · {r.model}</span>}
              {fmtTokens(r.inputTokens) && <span> · ↑{fmtTokens(r.inputTokens)} ↓{fmtTokens(r.outputTokens)}</span>}
              {r.costUsd >= 0 && <span> · ${r.costUsd.toFixed(4)}</span>}
            </div>
          )}
          {turn.error && !r && (
            <div className="turn-footer error">{turn.aborted ? '⏹ 已停止' : '❌'} {turn.error}</div>
          )}
        </div>
      </div>
    </div>
  )
}
