import { useState, useEffect, useRef, useCallback } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// ===== 常量与工具 =====

const ROLES = [
  { key: 'programmer', emoji: '👨‍💻', label: '程序员', color: '#3b82f6', hint: '代码实现方（汇报改动、修复问题）' },
  { key: 'designer', emoji: '🧪', label: '测试设计师', color: '#8b5cf6', hint: '独立设计测试用例（不迎合实现）' },
  { key: 'executor', emoji: '⚙️', label: '执行者', color: '#f59e0b', hint: '真实执行用例、给出判定与证据' },
]
const roleOf = key => ROLES.find(r => r.key === key) || { emoji: '🤖', label: key, color: '#6b7280' }

const STAGES = [
  { key: 'PROGRAMMER_REPORT', label: '改动汇报' },
  { key: 'DESIGNING', label: '设计用例' },
  { key: 'EXECUTING', label: '执行测试' },
  { key: 'FIXING', label: '修复回归' },
  { key: 'DONE', label: '完成' },
]

const CASE_STATUS = {
  pending: { icon: '⏳', label: '待测', color: '#6b7280' },
  running: { icon: '🔄', label: '执行中', color: '#0891b2' },
  pass: { icon: '✅', label: '通过', color: '#16a34a' },
  fail: { icon: '❌', label: '失败', color: '#dc2626' },
  blocked: { icon: '🚫', label: '受阻', color: '#f59e0b' },
}

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

// ===== 主页面 =====

export default function E2ETest() {
  const [instances, setInstances] = useState([])
  const [tasks, setTasks] = useState([])
  const [activeTaskId, setActiveTaskId] = useState(null)
  const [taskInfo, setTaskInfo] = useState(null)     // 含 members/pipeline
  const [turns, setTurns] = useState([])             // 群聊消息（按 turnIndex 稀疏数组）
  const [connected, setConnected] = useState(false)
  const [input, setInput] = useState('')
  const [addressee, setAddressee] = useState('auto')
  const [modal, setModal] = useState(null)
  const [toast, setToast] = useState(null)
  const [showCreate, setShowCreate] = useState(false)

  const wsRef = useRef(null)
  const deltaBuf = useRef({})
  const flushTimer = useRef(null)
  const scrollRef = useRef(null)
  const stickBottom = useRef(true)

  useEffect(() => { loadInstances(); loadTasks() }, [])

  async function loadInstances() {
    try { setInstances(await (await fetch('/api/instances')).json()) }
    catch (e) { showToast('加载实例失败: ' + e.message, 'error') }
  }
  async function loadTasks() {
    try { setTasks(await (await fetch('/api/e2e/tasks')).json()) } catch (e) { /* 静默 */ }
  }
  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  // ===== WebSocket =====
  useEffect(() => {
    if (!activeTaskId) { setTurns([]); setTaskInfo(null); setConnected(false); return }
    const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${window.location.host}/ws/e2e?taskId=${activeTaskId}`)
    wsRef.current = ws
    ws.onopen = () => setConnected(true)
    ws.onclose = () => setConnected(false)
    ws.onerror = () => setConnected(false)
    ws.onmessage = ev => {
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
    }, 150)
  }, [])

  function setTurnAt(i, updater) {
    setTurns(prev => {
      const arr = [...prev]
      if (!arr[i]) return prev
      arr[i] = updater(arr[i])
      return arr
    })
  }

  function handleEvent(e) {
    switch (e.type) {
      case 'snapshot': {
        setTaskInfo(e.task)
        const arr = []
        ;(e.turns || []).forEach((t, i) => {
          arr[i] = {
            kind: t.kind || 'agent', author: t.author || 'user', authorName: t.authorName || '',
            addressedTo: t.addressedTo || '', userText: t.userText || '', thinking: t.thinking || '', answer: t.answer || '',
            tools: t.tools || [], result: t.result || null,
            error: t.errorMessage || null, aborted: !!t.aborted,
            a2a: t.a2a || null,
          }
        })
        setTurns(arr)
        deltaBuf.current = {}
        break
      }
      case 'user_message':
        setTurns(prev => {
          const arr = [...prev]
          arr[e.turnIndex] = { kind: 'user', author: 'user', authorName: e.authorName || '用户', addressedTo: e.to || 'auto', userText: e.text, thinking: '', answer: '', tools: [], result: null }
          return arr
        })
        break
      case 'ask':
        setTaskInfo(prev => prev ? {
          ...prev,
          pendingQuestion: { role: e.role, roleLabel: e.roleLabel, roleEmoji: e.roleEmoji, memberName: e.memberName, question: e.question, why: e.why },
        } : prev)
        break
      case 'ask_resolved':
        setTaskInfo(prev => prev ? { ...prev, pendingQuestion: null } : prev)
        break
      case 'system':
        setTurns(prev => {
          const arr = [...prev]
          arr[e.turnIndex] = { kind: 'system', author: 'system', authorName: '', userText: e.text, thinking: '', answer: '', tools: [], result: null }
          return arr
        })
        break
      case 'turn_start':
        setTaskInfo(prev => prev ? { ...prev, busy: true } : prev)
        setTurns(prev => {
          const arr = [...prev]
          const prevTurn = arr[e.turnIndex]
          arr[e.turnIndex] = {
            // 保留先到的 a2a 信封（a2a_task Pending 事件先于 turn_start 到达，整对象替换会冲掉它）
            ...(prevTurn || {}),
            kind: 'agent', author: e.author || 'user', authorName: e.authorName || '',
            userText: e.userText || '', thinking: '', answer: '', tools: [], result: null,
          }
          return arr
        })
        break
      case 'a2a_task': {
        // A2A 任务信封状态流转（Pending→Running→终态）：骑在该成员轮次的气泡上
        const i = e.turnIndex
        setTurns(prev => {
          const arr = [...prev]
          const base = arr[i] || {
            kind: 'agent', author: e.a2a?.receiverRole || 'agent', authorName: e.a2a?.receiverName || '',
            userText: '', thinking: '', answer: '', tools: [], result: null,
          }
          arr[i] = { ...base, a2a: e.a2a || null }
          return arr
        })
        break
      }
      case 'queued':
        showToast('⏳ 该实例正在处理其他消息，本条已排队', 'info')
        break
      case 'thinking':
      case 'delta': {
        const i = e.turnIndex
        deltaBuf.current[i] = deltaBuf.current[i] || { answer: '', thinking: '' }
        deltaBuf.current[i][e.type === 'delta' ? 'answer' : 'thinking'] += e.text || ''
        scheduleFlush()
        break
      }
      case 'tool':
        setTurnAt(e.turnIndex, t => {
          const tools = [...t.tools]
          tools[e.toolIndex] = { name: e.name, detail: e.detail, status: e.status, elapsedMs: e.elapsedMs }
          return { ...t, tools }
        })
        break
      case 'complete':
        delete deltaBuf.current[e.turnIndex]
        setTaskInfo(prev => prev ? { ...prev, busy: false } : prev)
        setTurnAt(e.turnIndex, t => ({
          ...t,
          answer: (e.result && e.result.text) ? e.result.text : t.answer,
          result: e.result,
        }))
        loadTasks()
        break
      case 'error':
        delete deltaBuf.current[e.turnIndex]
        setTaskInfo(prev => prev ? { ...prev, busy: false } : prev)
        setTurnAt(e.turnIndex, t => ({ ...t, error: e.message, aborted: !!e.aborted }))
        loadTasks()
        break
      case 'stage':
        setTaskInfo(prev => prev ? {
          ...prev,
          pipeline: { ...prev.pipeline, stage: e.stage, stageDetail: e.detail, round: e.round },
        } : prev)
        break
      case 'cases_defined':
        setTaskInfo(prev => prev ? { ...prev, pipeline: { ...prev.pipeline, cases: e.cases } } : prev)
        break
      case 'case_update':
        setTaskInfo(prev => {
          if (!prev) return prev
          const cases = (prev.pipeline.cases || []).map(c => c.id === e.case.id ? e.case : c)
          return { ...prev, pipeline: { ...prev.pipeline, cases } }
        })
        break
      case 'round_report':
        setTaskInfo(prev => {
          if (!prev) return prev
          const rounds = [...(prev.pipeline.rounds || []).filter(r => r.round !== e.round),
            { round: e.round, passed: e.passed, failed: e.failed, blocked: e.blocked, total: e.total, atMs: e.atMs, results: e.results || [] }]
          return { ...prev, pipeline: { ...prev.pipeline, rounds: rounds.sort((a, b) => a.round - b.round) } }
        })
        break
      case 'pipeline_reset':
        // 同群续派新任务：整棵流水线树（含 history 归档）整体替换
        setTaskInfo(prev => prev ? {
          ...prev,
          pipeline: e.pipeline,
          pipelineDone: !!e.pipelineDone,
          interrupted: !!e.interrupted,
          busy: false,
        } : prev)
        loadTasks()
        break
      default: break
    }
  }

  useEffect(() => {
    const el = scrollRef.current
    if (el && stickBottom.current) el.scrollTop = el.scrollHeight
  }, [turns, taskInfo])

  function onScroll() {
    const el = scrollRef.current
    if (!el) return
    stickBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60
  }

  async function handleDelete(id, ev) {
    ev.stopPropagation()
    if (!confirm('删除此测试任务？群聊记录将丢失（各实例自己的飞书会话不受影响）。')) return
    try {
      await fetch(`/api/e2e/tasks/${id}`, { method: 'DELETE' })
      if (activeTaskId === id) setActiveTaskId(null)
      loadTasks()
      showToast('任务已删除', 'success')
    } catch (e) { showToast('删除失败', 'error') }
  }

  function handleSend() {
    const text = input.trim()
    if (!text || !activeTaskId) return
    const ws = wsRef.current
    if (!ws || ws.readyState !== WebSocket.OPEN) { showToast('连接未就绪，请稍后再试', 'error'); return }
    ws.send(JSON.stringify({ type: 'message', text, to: addressee }))
    setInput('')
    stickBottom.current = true
  }
  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend() }
  }

  const pipeline = taskInfo?.pipeline

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 64px)', overflow: 'hidden', gap: 12 }}>
      {/* 顶部：任务标签条 + 新建（紧凑单行） */}
      <div className="card" style={{ padding: '8px 14px', flexShrink: 0, marginBottom: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <h1 className="page-title" style={{ margin: 0, fontSize: 16 }}>🧪 E2E 测试群聊</h1>
          {tasks.length > 0 && (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', flex: 1 }}>
              {tasks.map(t => (
                <span key={t.id} onClick={() => setActiveTaskId(t.id)}
                  className={'task-tab' + (t.id === activeTaskId ? ' active' : '')}
                  title={`需求: ${t.requirement}\n成员: ${t.members.map(m => m.roleEmoji + m.name).join(' / ')}\n${t.commMode === 'a2a' ? '🔗 A2A 任务信封（跨目录可用）' : t.sharedDir ? '共享目录: ' + t.sharedDir : '成员目录不同（群聊全文模式）'}\n创建者: ${t.creatorEmployeeId ? '👔 数字员工「' + (t.creatorEmployeeName || t.creatorEmployeeId) + '」' : '👑 admin'}`}>
                  {t.pendingQuestion
                    ? <span title="流水线暂停，等你回答提问">⏸</span>
                    : (t.busy || t.active) && <span className="task-dot" />}
                  {t.creatorEmployeeId
                    ? <span title={`由数字员工「${t.creatorEmployeeName || t.creatorEmployeeId}」创建`}>👔</span>
                    : <span title="由管理员创建">👑</span>}
                  {t.members.map(m => m.roleEmoji).join('')} {truncate(t.requirement, 14)}
                  {t.pipeline?.stage === 'DONE' && <span style={{ color: '#16a34a' }}>✓</span>}
                  {t.pipeline?.stage === 'FAILED' && <span style={{ color: '#dc2626' }}>✗</span>}
                  {t.interrupted && <span style={{ color: '#d97706' }} title="应用重启中断，可读档续跑">⚠</span>}
                  <button className="task-del" onClick={e => handleDelete(t.id, e)}>×</button>
                </span>
              ))}
            </div>
          )}
          <div style={{ flex: 1 }} />
          <button className="btn btn-primary" style={{ fontSize: 13, padding: '5px 14px' }}
            onClick={() => setShowCreate(true)}>+ 新建群聊任务</button>
        </div>
      </div>

      {/* 主体行：左群聊（占满剩余高度） + 右流水线状态栏 */}
      <div style={{ flex: 1, display: 'flex', gap: 12, minHeight: 0 }}>
        <div className="card chat-area" style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', padding: 0, marginBottom: 0 }}>
          <div className="chat-header" style={{ padding: '7px 14px' }}>
            {taskInfo ? (
              <>
                <span>💬 <code>{taskInfo.id}</code></span>
                {taskInfo.creatorEmployeeId
                  ? <span className="hook-badge" title="由数字员工创建">👔 {taskInfo.creatorEmployeeName || `员工#${taskInfo.creatorEmployeeId}`}</span>
                  : <span className="hook-badge dim" title="由管理员创建">👑 admin</span>}
                <span style={{ color: 'var(--text-dim)', fontSize: 12 }}>
                  {taskInfo.members.map(m => `${m.roleEmoji}${m.name}`).join(' · ')}
                  {taskInfo.commMode === 'a2a' ? ' · 🔗 A2A 任务信封'
                    : taskInfo.sharedDir ? ' · 📁 同目录文件中转' : ' · 📨 群聊全文传递'}
                </span>
                {(taskInfo.pipeline?.runIndex || 1) > 1 && (
                  <span style={{ color: '#2563eb', fontSize: 12, fontWeight: 700 }} title="同群续派的第 N 轮任务">🔁 第 {taskInfo.pipeline.runIndex} 轮</span>
                )}
                {(taskInfo.busy || (taskInfo.pipelineStarted && !taskInfo.pipelineDone)) && <span className="spinner" title="流水线执行中" />}
                <span className={'conn-badge ' + (connected ? 'on' : 'off')}>{connected ? '● 已连接' : '○ 未连接'}</span>
              </>
            ) : <span style={{ color: 'var(--text-dim)', fontSize: 13 }}>选择或新建一个群聊任务开始</span>}
          </div>

          <div className="chat-scroll" ref={scrollRef} onScroll={onScroll}>
            {!activeTaskId && (
              <div className="chat-empty">
                <div style={{ fontSize: 40, marginBottom: 12 }}>🧪</div>
                <div>新建群聊任务：指定 👨‍💻程序员、🧪测试设计师、⚙️执行者 三个实例</div>
                <div style={{ fontSize: 12, marginTop: 8, color: 'var(--text-dim)', lineHeight: 1.8 }}>
                  成员使用自己与飞书对话的同一会话（上下文延续）<br />
                  设计师独立设计用例 → 执行者真实运行 → 失败自动反馈程序员修复 → 重测（最多N轮）<br />
                  结束后结果推送到飞书
                </div>
              </div>
            )}
            {activeTaskId && turns.filter(Boolean).length === 0 && (
              <div className="chat-empty">
                <div style={{ fontSize: 32, marginBottom: 10 }}>💬</div>
                <div>在下方输入测试指令启动流水线，例如：</div>
                <div className="chat-example">开始端到端测试，重点验证并发场景和异常处理</div>
              </div>
            )}
            {turns.map((t, i) => t && <GroupMessage key={i} turn={t}
              live={taskInfo?.busy && i === turns.length - 1 && t.kind === 'agent'} />)}
          </div>

          {/* 应用重启导致的流水线中断：读档横幅 + 断点续跑 */}
          {taskInfo?.interrupted && (
            <div className="ask-banner" style={{ background: 'rgba(220,38,38,0.08)', borderColor: 'rgba(220,38,38,0.4)', color: '#991b1b' }}>
              ⚠️ 应用重启中断了这条流水线 —— 群聊记录与用例状态已从数据库读档恢复。
              <button className="btn btn-primary" style={{ marginLeft: 10, fontSize: 12, padding: '3px 14px' }}
                onClick={async () => {
                  await fetch(`/api/e2e/tasks/${activeTaskId}/resume`, { method: 'POST' })
                  showToast('▶ 已从断点继续执行', 'success')
                  setTaskInfo(prev => prev ? { ...prev, interrupted: false } : prev)
                  loadTasks()
                }}>▶ 继续执行</button>
              <span className="ask-hint">用例已就绪时直接跳到执行阶段复测未通过项；也可先 @成员 单聊确认情况</span>
            </div>
          )}

          {/* 流水线暂停：成员在等玩家回答 */}
          {taskInfo?.pendingQuestion && (
            <div className="ask-banner">
              ⏸ {taskInfo.pendingQuestion.roleEmoji} <b>{taskInfo.pendingQuestion.roleLabel}{taskInfo.pendingQuestion.memberName ? `·${taskInfo.pendingQuestion.memberName}` : ''}</b> 在等你回答：
              <b style={{ margin: '0 4px' }}>{taskInfo.pendingQuestion.question}</b>
              {taskInfo.pendingQuestion.why && <span style={{ opacity: 0.75 }}>（{taskInfo.pendingQuestion.why}）</span>}
              <span className="ask-hint">👇 保持「🎯 自动编排」直接回复答案，流水线将自动继续</span>
            </div>
          )}

          <div className="chat-input-bar" style={{ padding: '10px 14px' }}>
            <select className="form-input" style={{ width: 130, padding: '6px 8px', flexShrink: 0 }}
              value={addressee} onChange={e => setAddressee(e.target.value)} disabled={!activeTaskId}>
              <option value="auto">🎯 自动编排</option>
              {taskInfo?.members?.map((m, i) => (
                <option key={i} value={m.role}>{m.roleEmoji} @{m.roleLabel}·{m.name}</option>
              ))}
            </select>
            <textarea className="chat-input" rows={2}
              placeholder={activeTaskId
                ? (taskInfo?.pendingQuestion
                  ? '⏸ 回答上面的提问，发送后流水线自动继续…（Enter 发送）'
                  : taskInfo?.pipelineDone
                    ? '输入下一个任务需求，发送即续派新一轮编排（历史记录保留、右侧递增）…（Enter 发送）'
                    : (addressee === 'auto' ? '输入测试指令启动流水线…（Enter 发送）' : `直接对 ${roleOf(addressee).label} 说话…（Enter 发送）`))
                : '请先创建/选择任务'}
              value={input} disabled={!activeTaskId || !connected}
              onChange={e => setInput(e.target.value)} onKeyDown={handleKeyDown} />
            <button className="btn btn-primary" onClick={handleSend}
              disabled={!activeTaskId || !connected || !input.trim()}>发送</button>
          </div>
        </div>

        {/* 右侧流水线状态栏（独立滚动，不占聊天高度；历史轮次归档在上、当前轮在下递增） */}
        {taskInfo && (
          <div className="card pipeline-side" style={{ marginBottom: 0 }}>
            {(pipeline?.history || []).map(h => <RunBlock key={h.runIndex} run={h} />)}
            {(pipeline?.history?.length || 0) > 0 && (
              <div className="run-current-head">
                🚀 第 {pipeline?.runIndex || 1} 轮任务
                {pipeline?.stage === 'DONE' ? '（已完成）' : pipeline?.stage === 'FAILED' ? '（失败）' : '（进行中）'}
              </div>
            )}
            <StageStepper pipeline={pipeline} />
            {pipeline?.cases?.length > 0 && <CaseGrid cases={pipeline.cases} rounds={pipeline.rounds} />}
            {pipeline?.rounds?.length > 0 && <RoundHistory rounds={pipeline.rounds} />}
          </div>
        )}
      </div>

      {/* 创建任务弹窗 */}
      {showCreate && (
        <div className="modal-mask" onClick={() => setShowCreate(false)}>
          <div className="modal-box" style={{ maxWidth: 1500, width: 'min(1500px, 96vw)' }} onClick={ev => ev.stopPropagation()}>
            <div className="modal-title">🧪 新建 E2E 测试群聊任务</div>
            <CreatePanel instances={instances} onClose={() => setShowCreate(false)}
              onCreated={async (id) => { setShowCreate(false); await loadTasks(); setActiveTaskId(id) }}
              setModal={setModal} showToast={showToast} />
          </div>
        </div>
      )}

      {modal && (
        <div className="modal-mask" onClick={() => setModal(null)}>
          <div className="modal-box" onClick={ev => ev.stopPropagation()}>
            <div className="modal-title">{modal.title}</div>
            <div className="modal-body">{modal.body}</div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="btn" onClick={() => setModal(null)}>取消</button>
              <button className="btn btn-primary" onClick={modal.onConfirm}>{modal.confirmLabel || '仍要继续创建'}</button>
            </div>
          </div>
        </div>
      )}
      {toast && <div className={`toast toast-${toast.type}`}>{toast.msg}</div>}
    </div>
  )
}

function truncate(s, n) { return !s ? '' : (s.length <= n ? s : s.slice(0, n) + '…') }

// ===== 创建面板 =====

function CreatePanel({ instances, onClose, onCreated, setModal, showToast }) {
  const [pick, setPick] = useState({ programmer: '', designer: '', executor: '' })
  const [requirement, setRequirement] = useState('')
  const [autoFix, setAutoFix] = useState(true)
  const [maxRounds, setMaxRounds] = useState(3)
  const [commMode, setCommMode] = useState('filesystem')
  const [creating, setCreating] = useState(false)
  // 实例独占绑定：被数字员工绑定的实例不能再圈进群聊（服务端也会拦）
  const [occupied, setOccupied] = useState({})
  useEffect(() => {
    fetch('/api/employees').then(r => r.json()).then(emps => {
      const map = {}
      ;(emps || []).forEach(emp => (emp.instances || []).forEach(i => {
        map[String(i.id)] = `${emp.emoji || '👔'} ${emp.name}`
      }))
      setOccupied(map)
    }).catch(() => {})
  }, [])

  const enabled = instances.filter(i => i.enabled)
  const instById = id => instances.find(i => String(i.id) === String(id))
  const dirOf = id => (instById(id)?.claudeWorkingDir || instById(id)?.shellWorkingDir || '').trim()

  function validate() {
    const chosen = Object.values(pick).filter(Boolean)
    if (chosen.length === 0) { showToast('至少选择一个角色的实例', 'error'); return false }
    if (!requirement.trim()) { showToast('请填写要测试的需求描述', 'error'); return false }
    return true
  }

  function handleCreate() {
    if (!validate()) return
    const dirs = [...new Set(Object.values(pick).filter(Boolean).map(dirOf).filter(Boolean))]
    // A2A 模式工件走任务载荷，跨目录零损耗——不需要目录一致性提醒
    if (dirs.length > 1 && commMode !== 'a2a') {
      setModal({
        title: '⚠️ 成员实例的工作目录不同',
        confirmLabel: '仍要继续创建',
        body: (
          <div style={{ lineHeight: 1.9 }}>
            {Object.entries(pick).filter(([, v]) => v).map(([role, id]) => (
              <div key={role}>{roleOf(role).emoji} {roleOf(role).label}「{instById(id)?.name}」: <code>{dirOf(id) || '(未配置)'}</code></div>
            ))}
            <div style={{ marginTop: 10 }}>
              建议把所有成员实例放在<b>相同目录</b>再创建任务：同目录时 agent 之间的大内容
              （改动说明/测试用例/测试报告）直接通过 <code>.e2e/任务ID/</code> 目录下的文件交换，
              群聊保持精简、信息不丢失；目录不同则只能靠群聊消息全文传递，长内容会被截断。
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
    setCreating(true)
    try {
      const members = Object.entries(pick).filter(([, v]) => v)
        .map(([role, id]) => ({ instanceId: Number(id), role }))
      const res = await fetch('/api/e2e/tasks', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ members, requirement: requirement.trim(), autoFix, maxRounds, commMode }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '创建失败')
      showToast('群聊任务已创建，输入测试指令即可启动', 'success')
      onCreated(data.id)
    } catch (e) {
      showToast('创建失败: ' + e.message, 'error')
    } finally {
      setCreating(false)
    }
  }

  return (
    <div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
        {ROLES.map(r => (
          <div key={r.key}>
            <label className="form-label" style={{ fontSize: 13 }}>
              {r.emoji} {r.label} <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 11 }}>{r.hint}</span>
            </label>
            <select className="form-input" style={{ padding: '6px 8px' }}
              value={pick[r.key]} onChange={e => setPick(p => ({ ...p, [r.key]: e.target.value }))}>
              <option value="">（不指定）</option>
              {enabled.map(i => {
                const occ = occupied[String(i.id)]
                return (
                  <option key={i.id} value={i.id} disabled={!!occ}>
                    {occ ? `🔒 ${i.name || `#${i.id}`}（已被员工 ${occ} 独占绑定）` : (i.name || `#${i.id}`)}
                  </option>
                )
              })}
            </select>
          </div>
        ))}
      </div>
      <div className="form-group" style={{ marginTop: 12 }}>
        <label className="form-label" style={{ fontSize: 13 }}>要测试的需求描述 *</label>
        <textarea className="form-input" rows={2} style={{ resize: 'vertical' }}
          placeholder="例如：实现了飞书机器人的多实例状态功能，管理面板显示每个实例的实时状态徽章"
          value={requirement} onChange={e => setRequirement(e.target.value)} />
      </div>
      <div className="form-group" style={{ marginTop: 12 }}>
        <label className="form-label" style={{ fontSize: 13 }}>成员通信方式</label>
        <div style={{ display: 'flex', gap: 18, fontSize: 13, flexWrap: 'wrap' }}>
          <label style={{ display: 'flex', alignItems: 'flex-start', gap: 6, cursor: 'pointer', maxWidth: 340 }}>
            <input type="radio" style={{ marginTop: 3 }} checked={commMode === 'filesystem'}
              onChange={() => setCommMode('filesystem')} />
            <span>
              📁 <b>共享文件系统</b>（黑板模式）
              <span style={{ display: 'block', fontSize: 11, color: 'var(--text-dim)', lineHeight: 1.6 }}>
                成员在同一工作目录经 .e2e/任务ID/ 文件交换大内容（改动说明/用例/报告），群聊只留精简摘要。需成员目录相同才发挥全部效果。
              </span>
            </span>
          </label>
          <label style={{ display: 'flex', alignItems: 'flex-start', gap: 6, cursor: 'pointer', maxWidth: 340 }}>
            <input type="radio" style={{ marginTop: 3 }} checked={commMode === 'a2a'}
              onChange={() => setCommMode('a2a')} />
            <span>
              🔗 <b>A2A</b>（Agent2Agent 协议）
              <span style={{ display: 'block', fontSize: 11, color: 'var(--text-dim)', lineHeight: 1.6 }}>
                工件作为任务信封载荷在成员间直传（编排器派发，状态全程追踪：待执行→执行中→完成/失败），
                不依赖共享目录，<b>跨目录成员可用</b>。移植自开源项目 axocoatl 的 A2A 方案。
              </span>
            </span>
          </label>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap' }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
          <input type="checkbox" checked={autoFix} onChange={e => setAutoFix(e.target.checked)} />
          失败自动反馈程序员修复并重测
        </label>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
          最多轮数
          <select className="form-input" style={{ width: 64, padding: '4px 6px' }}
            value={maxRounds} onChange={e => setMaxRounds(Number(e.target.value))}>
            {[1, 2, 3, 4, 5].map(n => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <div style={{ flex: 1 }} />
        <button className="btn" onClick={onClose}>收起</button>
        <button className="btn btn-primary" onClick={handleCreate} disabled={creating}>
          {creating ? '创建中…' : '创建群聊任务'}
        </button>
      </div>
    </div>
  )
}

// ===== 流水线状态 =====

/** 已归档的历史轮次（同群续派任务时右侧向下递增保留；点击展开该轮完整执行情况） */
function RunBlock({ run }) {
  const [open, setOpen] = useState(false)
  const done = run.stage === 'DONE'
  const failed = run.stage === 'FAILED'
  const passed = (run.cases || []).filter(c => c.status === 'pass').length
  return (
    <div className="run-block">
      <div className="run-block-head" onClick={() => setOpen(v => !v)}>
        <span>{done ? '✅' : failed ? '❌' : '⏸'} 第 {run.runIndex} 轮任务</span>
        <span className="run-req" title={run.requirement}>{truncate(run.requirement, 16)}</span>
        <span style={{ marginLeft: 'auto', fontSize: 11, color: done ? 'var(--success, #16a34a)' : failed ? 'var(--error)' : 'var(--text-dim)' }}>
          {passed}/{(run.cases || []).length}
        </span>
        <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>{open ? '▲' : '▼'}</span>
      </div>
      {open && (
        <div className="run-block-body">
          <StageStepper pipeline={run} />
          {(run.cases || []).length > 0 && <CaseGrid cases={run.cases} rounds={run.rounds} />}
          {(run.rounds || []).length > 0 && <RoundHistory rounds={run.rounds} />}
        </div>
      )}
    </div>
  )
}

function StageStepper({ pipeline }) {
  if (!pipeline || pipeline.stage === 'IDLE') {
    return <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>⏸ 流水线未启动 — 在群聊里输入测试指令开始</div>
  }
  const failed = pipeline.stage === 'FAILED'
  const curIdx = failed
    ? STAGES.length   // 失败时不点亮任何"当前"步骤
    : STAGES.findIndex(s => s.key === pipeline.stage)
  return (
    <div>
      <div className="stepper-v">
        {STAGES.map((s, i) => {
          let cls = 'step-v'
          if (pipeline.stage === 'DONE') cls += i <= curIdx ? ' done' : ''
          else if (failed) cls += ''
          else if (i < curIdx) cls += ' done'
          else if (i === curIdx) cls += ' active'
          const isLast = i === STAGES.length - 1
          return (
            <div key={s.key} className="step-v-row">
              <span className={cls}>
                {(i < curIdx || (pipeline.stage === 'DONE' && i <= curIdx)) ? '✓' : (i === curIdx && !isLast) ? '●' : '○'} {s.label}
                {i === curIdx && pipeline.stage !== 'DONE' && !failed && <span className="spinner" style={{ marginLeft: 6, width: 10, height: 10 }} />}
              </span>
              {!isLast && <span className="step-v-line" />}
            </div>
          )
        })}
      </div>
      <div style={{ fontSize: 12, marginTop: 8, lineHeight: 1.6, color: failed ? 'var(--error)' : 'var(--text-dim)' }}>
        {failed ? '❌ ' + (pipeline.failReason || '流水线失败') : pipeline.stageDetail}
        {pipeline.round > 0 && !failed && <b> · 第 {pipeline.round} 轮</b>}
      </div>
      {pipeline.rounds?.length > 0 && (
        <div className="round-timeline">
          {pipeline.rounds.map(r => (
            <span key={r.round} className={'round-chip' + (r.failed === 0 ? ' allpass' : '')} title={r.fixSummary || ''}>
              第{r.round}轮 {r.passed}/{r.total} 通过{r.failed > 0 && ` · ❌${r.failed}`}{r.blocked > 0 && ` · 🚫${r.blocked}`}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

function CaseGrid({ cases, rounds }) {
  const passed = cases.filter(c => c.status === 'pass').length
  return (
    <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px dashed var(--border, #e5e7eb)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <span style={{ fontSize: 12, fontWeight: 600 }}>用例 {passed}/{cases.length}</span>
        <div className="pass-bar"><div className="pass-fill" style={{ width: `${cases.length ? passed / cases.length * 100 : 0}%` }} /></div>
      </div>
      <div className="case-list">
        {cases.map(c => <CaseCard key={c.id} c={c} />)}
      </div>
    </div>
  )
}

/** 各轮执行痕迹：第 N 轮跑了哪些用例、各自结果如何，按时间往下排 */
function RoundHistory({ rounds }) {
  const fmtClock = ms => {
    if (!ms) return ''
    const d = new Date(ms)
    return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  return (
    <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px dashed var(--border, #e5e7eb)' }}>
      <div style={{ fontSize: 12, fontWeight: 600, marginBottom: 8 }}>📜 各轮执行记录</div>
      {rounds.map(r => (
        <div key={r.round} className="round-block">
          <div className="round-block-head">
            <b>第 {r.round} 轮</b>
            <span style={{ color: 'var(--text-dim)', fontWeight: 400 }}>{fmtClock(r.atMs)}</span>
            <span style={{ marginLeft: 'auto', color: r.failed + r.blocked === 0 ? 'var(--success)' : 'var(--error)' }}>
              {r.passed}/{r.total} 通过
            </span>
          </div>
          {(r.results || []).map(c => {
            const st = CASE_STATUS[c.status] || CASE_STATUS.pending
            const note = c.status !== 'pass' ? (c.failReason || c.evidence || '') : ''
            return (
              <div key={c.id} className="round-case-row" title={note ? `${c.id}: ${note}` : c.id}>
                <span>{st.icon}</span>
                <b style={{ fontSize: 11 }}>{c.id}</b>
                <span className="rc-title">{c.title}</span>
              </div>
            )
          })}
          {(r.results || []).length === 0 && <div className="round-case-row" style={{ color: 'var(--text-dim)' }}>（本轮无逐用例明细）</div>}
          {r.fixSummary && <div className="round-fix" title={r.fixSummary}>🔧 修复: {truncate(r.fixSummary, 60)}</div>}
        </div>
      ))}
    </div>
  )
}

function CaseCard({ c }) {
  const [open, setOpen] = useState(false)
  const st = CASE_STATUS[c.status] || CASE_STATUS.pending
  return (
    <div className="case-card" style={{ borderLeftColor: st.color }}>
      <div className="case-head" onClick={() => setOpen(v => !v)}>
        <span>{st.icon}</span>
        <b style={{ fontSize: 12 }}>{c.id}</b>
        <span style={{ fontSize: 12, flex: 1 }}>{c.title}</span>
        <span style={{ fontSize: 11, color: st.color }}>{st.label}{c.status === 'running' && '…'}</span>
        <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>{open ? '▲' : '▼'}</span>
      </div>
      {open && (
        <div className="case-body">
          {c.steps && <div><span className="case-k">步骤</span>{c.steps}</div>}
          {c.expected && <div><span className="case-k">预期</span>{c.expected}</div>}
          {c.evidence && <div><span className="case-k">证据</span><code>{c.evidence}</code></div>}
          {c.failReason && <div style={{ color: 'var(--error)' }}><span className="case-k">原因</span>{c.failReason}</div>}
        </div>
      )}
    </div>
  )
}

// ===== 群聊消息 =====

function GroupMessage({ turn, live }) {
  if (turn.kind === 'system') {
    return <div className="group-system"><span>{turn.userText}</span></div>
  }
  if (turn.kind === 'user') {
    const addr = turn.addressedTo
    const role = addr && addr !== 'auto' ? roleOf(addr) : null
    const isEmployee = turn.authorName && turn.authorName !== '用户'
    if (isEmployee) {
      // 数字员工发言 — 飞书群聊视角："对方"的消息显示在左边（👔 蓝色身份）
      const first = String(turn.authorName).split(' ')[0] || '👔'
      return (
        <div className="msg-bot">
          <div className="msg-avatar" style={{ background: 'rgba(37,99,235,0.12)', color: '#2563eb' }} title={turn.authorName}>
            {first.length <= 8 ? first : '👔'}
          </div>
          <div className="bubble-bot" style={{ borderLeft: '3px solid #2563eb' }}>
            <div className="msg-author" style={{ color: '#2563eb' }}>{turn.authorName}</div>
            <div style={{ whiteSpace: 'pre-wrap' }}>
              <span className={'addr-chip emp' + (role ? '' : ' auto')}>
                {role ? `@${role.emoji} ${role.label}` : '🎯 自动编排'}
              </span>
              {turn.userText}
            </div>
          </div>
        </div>
      )
    }
    return (
      <div className="msg-user">
        <div className="bubble-user">
          <span className={'addr-chip' + (role ? '' : ' auto')}>
            {role ? `@${role.emoji} ${role.label}` : '🎯 自动编排'}
          </span>
          {turn.userText}
        </div>
      </div>
    )
  }
  const role = roleOf(turn.author)
  return (
    <div className="msg-bot">
      <div className="msg-avatar" style={{ background: role.color + '18', color: role.color }} title={turn.authorName}>
        {role.emoji}
      </div>
      <div className="bubble-bot" style={{ borderLeft: `3px solid ${role.color}` }}>
        {turn.a2a && <A2AStrip a2a={turn.a2a} />}
        <div className="msg-author" style={{ color: role.color }}>
          {role.label}{turn.authorName && <span style={{ color: 'var(--text-dim)', fontWeight: 400 }}> · {turn.authorName}</span>}
          {turn.userText && <span className="msg-action">（{turn.userText}）</span>}
        </div>
        <AgentTurnBody turn={turn} live={live} />
      </div>
    </div>
  )
}

// ===== A2A 任务信封 =====

const A2A_STATUS = {
  Pending: { label: '待执行', color: '#6b7280', bg: 'rgba(107,114,128,0.10)' },
  Running: { label: '执行中', color: '#2563eb', bg: 'rgba(37,99,235,0.10)' },
  Completed: { label: '已完成', color: '#16a34a', bg: 'rgba(22,163,74,0.10)' },
  Failed: { label: '失败', color: '#dc2626', bg: 'rgba(220,38,38,0.10)' },
  Cancelled: { label: '已取消', color: '#d97706', bg: 'rgba(217,119,6,0.10)' },
}

/** 信封条：骑在 agent 气泡顶部的 A2A 任务元数据（状态流转/耗时/输出摘录），读档后原样恢复 */
function A2AStrip({ a2a }) {
  const st = A2A_STATUS[a2a.status] || A2A_STATUS.Pending
  return (
    <div className="a2a-strip">
      <span title="A2A 任务信封">📨</span>
      <b>A2A</b>
      <code className="a2a-id">{a2a.taskId}</code>
      <span className="a2a-route">{a2a.senderName || '编排器'} → {roleOf(a2a.receiverRole).emoji} {a2a.receiverName || ''}</span>
      <span className="a2a-status" style={{ color: st.color, background: st.bg }}>
        {a2a.status === 'Running' && <span className="spinner" style={{ width: 9, height: 9 }} />}
        {st.label}
      </span>
      {a2a.durationMs > 0 && <span className="a2a-dur">耗时 {fmtElapsed(a2a.durationMs)}</span>}
      {a2a.error && <span className="a2a-err" title={a2a.error}>⚠ {truncate(a2a.error, 40)}</span>}
      {a2a.outputExcerpt && (
        <details className="a2a-details">
          <summary>输出摘录</summary>
          <div className="a2a-excerpt">{a2a.outputExcerpt}</div>
        </details>
      )}
    </div>
  )
}

function AgentTurnBody({ turn, live }) {
  const [thinkOpen, setThinkOpen] = useState(false)
  const [toolsOpen, setToolsOpen] = useState(true)
  const thinkRef = useRef(null)
  const r = turn.result

  useEffect(() => {
    if (thinkOpen && thinkRef.current) thinkRef.current.scrollTop = thinkRef.current.scrollHeight
  }, [thinkOpen, turn.thinking])

  useEffect(() => { if (r) setToolsOpen(false) }, [r])

  const validTools = turn.tools.filter(Boolean)
  const runningCount = validTools.filter(t => t.status === 'running').length

  return (
    <>
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
          <span className="spinner" /><span className="live-text">发言中…</span><span className="live-cursor">▊</span>
        </div>
      )}
      {r && (
        <div className={'turn-footer' + (r.isError ? ' error' : '')}>
          {r.isError ? '❌ 出错' : r.aborted ? '⏹ 已停止' : '✅ 发言完成'}
          {r.durationMs > 0 && <span> · 耗时 {fmtElapsed(r.durationMs)}</span>}
          {r.model && <span> · {r.model}</span>}
          {fmtTokens(r.inputTokens) && <span> · ↑{fmtTokens(r.inputTokens)} ↓{fmtTokens(r.outputTokens)}</span>}
          {r.costUsd >= 0 && <span> · ${r.costUsd.toFixed(4)}</span>}
        </div>
      )}
      {turn.error && !r && (
        <div className="turn-footer error">{turn.aborted ? '⏹ 已停止' : '❌'} {turn.error}</div>
      )}
    </>
  )
}
