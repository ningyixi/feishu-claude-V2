import Toast from '../components/ui/Toast'
import { useConfirm, DraftPage } from '../components/ui/FloatingUI'
import { useState, useEffect, useRef, memo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useSearchParams } from 'react-router-dom'
import { useOccupied, KbPanel } from './Supplier'
import { COMPOSER_HINT, handleComposerKey, autoGrow } from '../utils/composerKeys'

// ===== 常量 =====

const STEPS = [
  { key: 'INGEST', label: '入库解析', icon: '📥' },
  { key: 'WIKI', label: '编译 Wiki', icon: '🕸️' },
]
const STEP_STYLE = {
  DONE: { color: '#16a34a', label: '已完成' },
  RUNNING: { color: '#3b82f6', label: '进行中' },
  FAILED: { color: '#dc2626', label: '失败' },
  PENDING: { color: '#9ca3af', label: '待开始' },
}
const WIKI_LABEL = { EMPTY: '尚未编译', BUILDING: '正在编译', READY: '已就绪', FAILED: '编译失败' }
const TASK_STATUS_LABEL = { INGEST: '入库解析中', WIKI: '编译 Wiki 中', READY: '对话待命' }
const KB_STATUS = {
  RAW: { label: '待解析', color: '#6b7280' },
  PENDING: { label: '排队解析', color: '#6b7280' },
  PARSING: { label: '解析中', color: '#3b82f6' },
  DONE: { label: '已解析', color: '#16a34a' },
  FAILED: { label: '解析失败', color: '#dc2626' },
}
const stepOf = (detail, key) => (detail?.steps || []).find(s => s.key === key) || { key, status: 'PENDING' }

// PENDING 行的"已运行"时长（本地时钟算，每次轮询重渲染自然刷新）
function elapsedLabel(createdAt) {
  if (!createdAt) return ''
  const t = new Date(String(createdAt)).getTime()
  if (Number.isNaN(t)) return ''
  const s = Math.max(0, Math.floor((Date.now() - t) / 1000))
  if (s < 60) return `已运行 ${s} 秒`
  return `已运行 ${Math.floor(s / 60)} 分 ${String(s % 60).padStart(2, '0')} 秒`
}

// ===== 主页面（左侧任务列表 + 右侧表单/详情） =====

export default function AdHoc() {
  const [route, setRoute] = useSearchParams()
  const taskId = /^\d+$/.test(route.get('task') || '') ? Number(route.get('task')) : null
  const creating = route.get('view') === 'create'
  const [toast, setToast] = useState(null)

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: '1rem', flexWrap: 'wrap' }}>
        <div style={{ flex: 1, minWidth: 240 }}>
          <h1 className="page-title" style={{ margin: 0 }}>临时需求</h1>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 4 }}>
            建任务传关联文档，自动 入库解析 → 编译 LLM Wiki；对话框全程可用（Wiki 就绪后每轮自动带上 Wiki 入口）。与飞书私聊绑定实例是同一个会话。
          </div>
        </div>
        {!creating && <button className="btn btn-primary" onClick={() => setRoute({ view: 'create' })}>+ 新建任务</button>}
      </div>

      <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <div style={{ flex: '0 0 250px', minWidth: 220 }}>
          <TaskListPane selectedId={creating ? null : taskId}
            onSelect={id => setRoute({ task: String(id) })}
            onCreate={() => setRoute({ view: 'create' })}
            showToast={showToast} />
        </div>
        <div style={{ flex: '1 1 480px', minWidth: 0 }}>
          {creating && <CreateTask onDone={id => setRoute(id ? { task: String(id) } : {})} showToast={showToast} />}
          {!creating && taskId && <TaskDetail key={taskId} taskId={taskId} onDeleted={() => setRoute({})} showToast={showToast} />}
          {!creating && !taskId && (
            <div className="card" style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
              从左侧选择一个任务，或点「+ 新建任务」：贴上关联文档，流水线自动推进；任何时候都能在对话框说话，Wiki 就绪后每轮自动带上 Wiki 入口。
            </div>
          )}
        </div>
      </div>
      <Toast toast={toast} onDismiss={() => setToast(null)} />
    </div>
  )
}

// ===== 左侧任务列表 =====

function TaskListPane({ selectedId, onSelect, onCreate, showToast }) {
  const confirmAction = useConfirm()
  const [tasks, setTasks] = useState([])

  const load = () => fetch('/api/ad-hoc-tasks').then(r => r.ok ? r.json() : []).then(rows => { if (Array.isArray(rows)) setTasks(rows) }).catch(() => {})
  useEffect(() => {
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [])

  async function remove(task, e) {
    e.stopPropagation()
    if (!await confirmAction({ kind: 'popconfirm', title: '删除临时需求任务？', body: `删除任务「${task.name}」？任务记录、对话流水与专属知识库条目将清除，删除后无法恢复。`, confirmLabel: '删除任务' })) return
    const res = await fetch(`/api/ad-hoc-tasks/${task.id}`, { method: 'DELETE' })
    if (!res.ok) { const d = await res.json().catch(() => ({})); return showToast(d.error || '删除失败', 'error') }
    showToast('已删除', 'success')
    load()
  }

  return (
    <div className="card" style={{ padding: 10 }}>
      <div style={{ fontSize: 12.5, fontWeight: 700, padding: '4px 6px 8px' }}>任务（{tasks.length}）</div>
      {tasks.length === 0 && <div style={{ fontSize: 12, color: 'var(--text-dim)', padding: '8px 6px' }}>还没有任务，点右上「+ 新建任务」。</div>}
      {tasks.map(t => {
        const ready = t.status === 'READY'
        const failed = !!t.error
        return (
          <div key={t.id} onClick={() => onSelect(t.id)}
            style={{ cursor: 'pointer', padding: '8px 10px', borderRadius: 8, marginBottom: 4, background: selectedId === t.id ? 'rgba(37,99,235,.08)' : undefined, border: selectedId === t.id ? '1px solid rgba(37,99,235,.3)' : '1px solid transparent' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ fontSize: 13, fontWeight: 600, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {ready ? '✅' : failed ? '⚠️' : '🛰️'} {t.name}
              </span>
              <button className="btn" style={{ fontSize: 11, padding: '1px 8px', color: 'var(--error)', flexShrink: 0 }} onClick={e => remove(t, e)}>删除</button>
            </div>
            <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 3 }}>
              {t.blocked && <span style={{ color: '#b45309', fontWeight: 700, marginRight: 6 }}>⏸ 待你确认</span>}
              {failed ? <span style={{ color: 'var(--error)' }}>失败，可重试本段</span> : (TASK_STATUS_LABEL[t.status] || t.status)}
              {!ready && !failed && t.running && <span className="spinner" style={{ width: 8, height: 8, marginLeft: 6 }} />}
              <span style={{ marginLeft: 6 }}>{String(t.updatedAt || '').replace('T', ' ').substring(5, 16)}</span>
            </div>
          </div>
        )
      })}
      <button className="btn" style={{ width: '100%', marginTop: 6, fontSize: 12 }} onClick={onCreate}>+ 新建任务</button>
    </div>
  )
}

// ===== 新建任务表单（任务名 / 关联文档多行 / 绑定实例） =====

function CreateTask({ onDone, showToast }) {
  const pageFormRef = useRef(null)
  const occupied = useOccupied()
  const [instances, setInstances] = useState([])
  const [form, setForm] = useState({ name: '', instanceId: '', docSources: '' })
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {})
  }, [])

  async function submit() {
    if (!form.instanceId) return showToast('请绑定执行实例', 'error')
    const sources = form.docSources.split('\n').map(s => s.trim()).filter(Boolean)
    if (sources.length === 0) return showToast('请至少填写 1 条关联文档', 'error')
    setSaving(true)
    try {
      const res = await fetch('/api/ad-hoc-tasks', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: form.name.trim() || null, instanceId: Number(form.instanceId), docSources: form.docSources }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || '创建失败')
      showToast('任务已创建，流水线开始推进', 'success')
      pageFormRef.current?.commit(); onDone(data.id)
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <DraftPage ref={pageFormRef}><div className="card">
      <h2 style={{ fontSize: 15, fontWeight: 700, margin: '0 0 12px' }}>🛰️ 新建临时需求任务</h2>
      <div className="form-group">
        <label className="form-label">任务名（选填，留空自动生成）</label>
        <input className="form-input" placeholder="如：某供应商历史接口字段变更" value={form.name}
          onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
      </div>
      <div className="form-group">
        <label className="form-label">关联文档 *（每行一条：http(s) 链接 / .pdf / .html / .md/.txt / .docx/.pptx/.xlsx/.doc 本地路径）</label>
        <textarea className="form-input" rows={6} style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, resize: 'vertical' }}
          placeholder={'https://docs.supplier.com/api\nD:\\docs\\supplier-api.pdf'}
          value={form.docSources} onChange={e => setForm(f => ({ ...f, docSources: e.target.value }))} />
      </div>
      <div className="form-group">
        <label className="form-label">绑定实例 *（Wiki 编译与后续对话都在这台实例的主会话上，与飞书私聊同会话）</label>
        <select className="form-input" value={form.instanceId} onChange={e => setForm(f => ({ ...f, instanceId: e.target.value }))}>
          <option value="">（请选择）</option>
          {instances.filter(i => i.enabled).map(i => {
            const occ = occupied[String(i.id)]
            return (
              <option key={i.id} value={i.id} disabled={!!occ}>
                {occ
                  ? `🔒 ${i.name || `#${i.id}`}（已被员工 ${occ.label} 独占绑定）`
                  : `${i.name || `#${i.id}`}${i.claudeModel ? ` (${i.claudeModel})` : ''}`}
              </option>
            )
          })}
        </select>
      </div>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
        <button className="btn" onClick={() => onDone(null)}>取消</button>
        <button className="btn btn-primary" onClick={submit} disabled={saving}>{saving ? '创建中…' : '🚀 创建并启动'}</button>
      </div>
    </div></DraftPage>
  )
}

// ===== 任务详情（两段步骤条 + Wiki 状态卡 + 对话框 + 只读知识库） =====

function TaskDetail({ taskId, onDeleted, showToast }) {
  const confirmAction = useConfirm()
  const [detail, setDetail] = useState(null)
  const [notFound, setNotFound] = useState(false)
  const [chatNonce, setChatNonce] = useState(0)   // 待确认卡答复后让 ChatPanel 立刻重拉，不等轮询
  const [appendNonce, setAppendNonce] = useState(0)   // 追加材料成功后立刻重拉详情（重编译状态即时可见）
  const pollRef = useRef(null)

  // 轮询只在数据真变化时 setDetail：详情卡/待确认卡的 DOM 因此保持稳定，
  // 用户拖选问题原文时选区不会被一次轮询冲掉（2026-11-17 用户诉求：对话文字要能拖拽复制）。
  const applyDetail = data => setDetail(prev => (prev && JSON.stringify(prev) === JSON.stringify(data) ? prev : data))

  const ready = !!detail && detail.status === 'READY'
  useEffect(() => {
    // 任务已删除/不存在：停表，不再对着 404 无限轮询（父级按 taskId remount，切换任务会重新起表）
    if (notFound) return
    let stopped = false
    const load = () => fetch(`/api/ad-hoc-tasks/${taskId}`)
      .then(async r => ({ ok: r.ok, data: await r.json().catch(() => ({})) }))
      .then(({ ok, data }) => {
        if (stopped) return          // 卸载/换任务后不再写 state：删任务后继续轮询的泄漏防线
        if (ok) applyDetail(data); else setNotFound(true)
      })
      .catch(() => {})
    load()
    // 推进中 3s 轮询进度；就绪后放缓（对话进度由 ChatPanel 自己轮询）；追加材料成功（appendNonce）立刻重拉一次
    pollRef.current = setInterval(load, ready ? 10000 : 3000)
    return () => { stopped = true; clearInterval(pollRef.current) }
    // 依赖只放 ready 这个布尔（决定轮询档位）。**不要把 detail 对象放进依赖、也不要在 effect 里 setDetail(null)**：
    // 旧写法两者都做了 → 每次取回详情就翻转依赖 → effect 重跑 → 再取详情，形成每秒上千次请求的自触发循环，
    // 页面长期停在「加载中…」、对话面板反复卸载重挂，用户拖到一半选区就被冲掉
    // （2026-11-17 Playwright 实测：8 秒内 /api/ad-hoc-tasks/{id} 被请求 10992 次，.md-body 采样 16/16 次为 0）。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskId, notFound, ready, appendNonce])

  async function retryStage() {
    const res = await fetch(`/api/ad-hoc-tasks/${taskId}/retry-stage`, { method: 'POST' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '重试失败', 'error')
    showToast('已从本段续跑', 'success')
    applyDetail(d)
  }
  async function remove() {
    if (!await confirmAction({ kind: 'popconfirm', title: '删除临时需求任务？', body: `删除任务「${detail.name}」？任务记录、对话流水与专属知识库条目将清除，删除后无法恢复。`, confirmLabel: '删除任务' })) return
    const res = await fetch(`/api/ad-hoc-tasks/${taskId}`, { method: 'DELETE' })
    if (!res.ok) { const d = await res.json().catch(() => ({})); return showToast(d.error || '删除失败', 'error') }
    showToast('已删除', 'success')
    onDeleted()
  }

  /** 待确认卡里直接答复：与对话框同一接口（后端 ask 会清 pendingQuestion，并在同一主会话续跑） */
  async function answerPending(question) {
    const res = await fetch(`/api/ad-hoc-tasks/${taskId}/chat`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ question }),
    })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '发送失败', 'error')
    showToast('已答复，agent 在同一会话继续', 'success')
    setChatNonce(n => n + 1)                                              // 对话流水立刻刷新
    setDetail(prev => (prev ? { ...prev, pendingQuestion: '', notifyError: '' } : prev))   // 卡片立刻收起
  }

  if (notFound) return <div className="card">任务不存在或已删除</div>
  if (!detail) return <div className="card">加载中…</div>

  const failedStep = detail.error ? (detail.steps || []).find(s => s.status === 'FAILED') : null
  const wiki = detail.wiki || {}

  return (
    <div>
      {/* 头部 */}
      <div className="card" style={{ padding: '12px 18px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 16, fontWeight: 700 }}>{ready ? '✅' : '🛰️'} {detail.name}</span>
          <span className="hook-badge">{detail.kbCode}</span>
          {detail.instanceName && <span className="hook-badge dim">实例：{detail.instanceName}</span>}
          {detail.running && !ready && <span className="hook-badge" style={{ color: '#3b82f6' }}><span className="spinner" style={{ width: 9, height: 9, marginRight: 5 }} />流水线推进中</span>}
          {ready && <span className="hook-badge" style={{ color: '#16a34a', background: 'rgba(22,163,74,.1)', borderColor: 'rgba(22,163,74,.3)' }}>💬 对话待命</span>}
          <div style={{ flex: 1 }} />
          <button className="btn" style={{ fontSize: 11, padding: '2px 10px', color: 'var(--error)' }} onClick={remove}>删除任务</button>
        </div>
      </div>

      {/* 常驻「待你确认」卡：agent 命中卡点时置顶显示问题原文，答复框就在下面（不是消息流里的一行） */}
      <PendingQuestionCard detail={detail} onAnswer={answerPending} />

      {/* 两段步骤条 */}
      <div className="card" style={{ marginTop: 14, padding: '16px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start' }}>
          {STEPS.map((s, i) => {
            const st = stepOf(detail, s.key)
            const style = STEP_STYLE[st.status] || STEP_STYLE.PENDING
            return (
              <div key={s.key} style={{ display: 'flex', alignItems: 'flex-start', flex: i < STEPS.length - 1 ? 1 : 'none' }}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: 92 }}>
                  <span className="wf-dot" style={{ background: st.status === 'DONE' ? '#16a34a' : st.status === 'FAILED' ? '#dc2626' : st.status === 'RUNNING' ? '#3b82f6' : '#d1d5db', color: '#fff', boxShadow: st.status === 'RUNNING' ? '0 0 0 3px rgba(59,130,246,.22)' : undefined }}>
                    {st.status === 'DONE' ? '✓' : st.status === 'FAILED' ? '✕' : st.status === 'RUNNING' ? '' : s.icon}
                    {st.status === 'RUNNING' && <span className="spinner" style={{ width: 9, height: 9, borderWidth: 1.5 }} />}
                  </span>
                  <div style={{ fontSize: 12, fontWeight: st.status === 'RUNNING' || st.status === 'FAILED' ? 700 : 500, marginTop: 6, color: st.status === 'PENDING' ? 'var(--text-dim)' : 'var(--text)' }}>{s.label}</div>
                  <div style={{ fontSize: 11, color: style.color }}>{style.label}</div>
                </div>
                {i < STEPS.length - 1 && <span style={{ flex: 1, height: 2, background: 'var(--border, #e5e7eb)', marginTop: 11, minWidth: 24 }} />}
              </div>
            )
          })}
        </div>

        {/* 失败原因 + 重试本段（A5） */}
        {failedStep && (
          <div style={{ marginTop: 14, padding: '10px 12px', border: '1px solid rgba(220,38,38,.4)', background: 'rgba(220,38,38,.06)', borderRadius: 8 }}>
            <div style={{ fontSize: 12.5, fontWeight: 700, color: '#dc2626' }}>
              ⚠️ 「{STEPS.find(s => s.key === failedStep.key)?.label}」段失败
            </div>
            <div style={{ fontSize: 12.5, color: 'var(--text)', marginTop: 6, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{detail.error}</div>
            <button className="btn btn-primary" style={{ fontSize: 12, padding: '4px 14px', marginTop: 8 }} onClick={retryStage}>
              🔄 重试本段
            </button>
          </div>
        )}

        {/* 当前段实时进度 */}
        {!ready && !failedStep && <RunningPanel detail={detail} />}
      </div>

      {/* Wiki 状态卡（版本/来源数/页数） */}
      <div className="card" style={{ marginTop: 14, padding: '14px 20px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 14, fontWeight: 700 }}>🕸️ LLM Wiki</span>
          <span className="hook-badge" style={wiki.status === 'READY' ? { color: '#16a34a', background: 'rgba(22,163,74,.1)', borderColor: 'rgba(22,163,74,.3)' } : undefined}>
            {WIKI_LABEL[wiki.status] || wiki.status || '—'}
          </span>
          {wiki.revision != null && <span className="hook-badge dim">版本 #{wiki.revision}</span>}
          {wiki.sourceCount != null && <span className="hook-badge dim">来源 {wiki.sourceCount} 个</span>}
          {wiki.pageCount != null && wiki.status === 'READY' && <span className="hook-badge dim">页面 {wiki.pageCount} 页</span>}
          {wiki.status === 'BUILDING' && wiki.phase && <span style={{ fontSize: 12, color: '#3b82f6' }}>{wiki.phase}</span>}
          {wiki.status === 'FAILED' && wiki.error && <span style={{ fontSize: 12, color: 'var(--error)', wordBreak: 'break-all' }}>{wiki.error}</span>}
        </div>
        {/* S3 诚实提示：追加材料后重编译期间常驻——别让用户以为模型已经读过他刚贴的原文 */}
        {(detail.pendingMaterialCount || 0) > 0 && (
          <div data-wiki-rebuilding style={{ marginTop: 10, padding: '8px 12px', border: '1px solid rgba(245,158,11,.4)', background: 'rgba(245,158,11,.08)', borderRadius: 8, fontSize: 12.5, color: '#b45309' }}>
            <span className="spinner" style={{ width: 9, height: 9, marginRight: 6 }} />
            Wiki 正在基于新增材料重编译 —— 有 {detail.pendingMaterialCount} 条新增材料尚未进入当前 Wiki 版本
            {Array.isArray(detail.pendingMaterialTitles) && detail.pendingMaterialTitles.length > 0
              ? `（涉及：${detail.pendingMaterialTitles.slice(0, 5).join('、')}${detail.pendingMaterialTitles.length > 5 ? ' 等' : ''}）` : ''}。
            对话照常可用；涉及这些材料时 agent 会按注入提示直接读来源原文，不会假装 Wiki 已覆盖。
          </div>
        )}
      </div>

      {/* 对话框：任何阶段都可说话——Wiki 未就绪时本轮不带 Wiki 入口，只带一行阶段说明 */}
      <ChatPanel taskId={taskId} showToast={showToast} wikiReady={ready} taskStatus={detail.status} chatNonce={chatNonce} />

      {/* 继续补充材料（S1/S2）：追加文档来源 / 聊天记录 / 笔记双框；追加成功后自动重编译 Wiki（S3） */}
      <AppendSourcesPanel taskId={taskId} disabled={!!detail.running} showToast={showToast}
        onAppended={() => setAppendNonce(n => n + 1)} />

      {/* 知识库条目（只读） */}
      <KbPanel kbCode={detail.kbCode} showToast={showToast} readOnly />
    </div>
  )
}

// ===== 继续补充材料（11-23-adhoc-append-sources S1/S2） =====

/**
 * 任务详情页底部的追加入口：文档来源（多行，类型由后端 DocParser 唯一权威判定）/ 聊天记录 / 笔记双框。
 * 笔记双框（用户诉求原话）：框一「主要内容」= 自己的理解/结论/待办；框二「原文」= 粘贴的原始文本，
 * 后端逐字保存（不 trim、不改空白）。原文框**不做 trim 直传**——粘贴内容长什么样就存什么样。
 * 追加成功后端自动重排队流水线并重编译 Wiki（S3）；disabled=流水线推进中（后端同一把串行闸兜底）。
 */
function AppendSourcesPanel({ taskId, disabled, showToast, onAppended }) {
  const [docSources, setDocSources] = useState('')
  const [chat, setChat] = useState('')
  const [chatTitle, setChatTitle] = useState('')
  const [noteTitle, setNoteTitle] = useState('')
  const [noteSummary, setNoteSummary] = useState('')
  const [noteRaw, setNoteRaw] = useState('')
  const [saving, setSaving] = useState(false)

  const hasInput = !!(docSources.trim() || chat.trim() || noteSummary.trim() || noteRaw.trim())

  async function submit() {
    if (saving || disabled) return
    if (!hasInput) return showToast('请至少填写一项：文档来源 / 聊天记录 / 笔记（主要内容或原文）', 'error')
    setSaving(true)
    try {
      const body = {}
      if (docSources.trim()) body.docSources = docSources
      if (chat.trim()) {
        body.chat = chat
        if (chatTitle.trim()) body.chatTitle = chatTitle.trim()
      }
      if (noteSummary.trim() || noteRaw.trim()) {
        if (noteTitle.trim()) body.noteTitle = noteTitle.trim()
        body.noteSummary = noteSummary   // 原样直传：原文/主要内容的空白由后端按契约保存
        body.noteRaw = noteRaw
      }
      const res = await fetch(`/api/ad-hoc-tasks/${taskId}/append-sources`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      })
      const d = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(d.error || '追加失败')
      const added = (d.added || []).length
      const dup = (d.duplicated || []).length
      if (added > 0 && d.wikiRebuildQueued) {
        showToast(`已追加 ${added} 项材料${dup ? `（另去重跳过 ${dup} 项）` : ''}——Wiki 正在基于新增材料重编译，对话照常可用`, 'success')
      } else {
        showToast(`材料都已在知识库中（去重跳过 ${dup} 项），未触发重编译`, 'success')
      }
      setDocSources(''); setChat(''); setChatTitle(''); setNoteTitle(''); setNoteSummary(''); setNoteRaw('')
      onAppended?.()
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <div className="card" data-append-sources style={{ marginTop: 14, padding: '14px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14, fontWeight: 700 }}>📎 继续补充材料</span>
        <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>
          建完任务后随时追加：文档来源 / 聊天记录 / 笔记（主要内容 + 原文两个框）。追加成功后 Wiki 自动重编译，期间对话照常可用。
        </span>
        {disabled && (
          <span className="hook-badge" style={{ color: '#b45309', background: 'rgba(245,158,11,.12)', borderColor: 'rgba(245,158,11,.4)' }}>
            流水线推进中，暂不能追加（等当前段结束）
          </span>
        )}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 12, marginTop: 12 }}>
        {/* 文档来源（多行） */}
        <div className="form-group" style={{ margin: 0 }}>
          <label className="form-label">文档来源（每行一条：http(s) 链接 / .pdf / .html / .md/.txt / .docx/.pptx/.xlsx/.doc 本地路径，类型自动判定）</label>
          <textarea className="form-input" data-append-docs rows={4}
            style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, resize: 'vertical' }}
            placeholder={'https://docs.supplier.com/api-v2\nD:\\docs\\补充说明.docx'}
            value={docSources} disabled={disabled} onChange={e => setDocSources(e.target.value)} />
        </div>

        {/* 聊天记录 */}
        <div className="form-group" style={{ margin: 0 }}>
          <label className="form-label">聊天记录（粘贴与对接人的沟通内容）</label>
          <input className="form-input" style={{ fontSize: 12, marginBottom: 6 }} placeholder="标题（可选）"
            value={chatTitle} disabled={disabled} onChange={e => setChatTitle(e.target.value)} />
          <textarea className="form-input" data-append-chat rows={4} style={{ fontSize: 12.5, resize: 'vertical' }}
            placeholder="对接群里确认：字段改用 v2……"
            value={chat} disabled={disabled} onChange={e => setChat(e.target.value)} />
        </div>
      </div>

      {/* 笔记双框（S2 用户诉求：一个框主要内容、一个框原文） */}
      <div style={{ marginTop: 12, border: '1px solid var(--border,#e5e7eb)', borderRadius: 10, padding: '10px 14px', background: 'rgba(59,130,246,.03)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 12.5, fontWeight: 700 }}>📝 笔记</span>
          <input className="form-input" style={{ width: 200, fontSize: 12 }} placeholder="标题（可选）"
            value={noteTitle} disabled={disabled} onChange={e => setNoteTitle(e.target.value)} />
          <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>两个框至少填一个；「原文」逐字保存（不改写、不裁剪、不重排空白）</span>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 12, marginTop: 8 }}>
          <div>
            <label className="form-label">① 主要内容（你的理解 / 结论 / 待办）</label>
            <textarea className="form-input" data-append-note-summary rows={5} style={{ fontSize: 12.5, resize: 'vertical' }}
              placeholder="例：取消接口必须带订单号；押金口径待确认，下周一前给结论。"
              value={noteSummary} disabled={disabled} onChange={e => setNoteSummary(e.target.value)} />
          </div>
          <div>
            <label className="form-label">② 原文（直接粘贴：邮件 / 群消息 / 口头约定原话 / 合同条款…）</label>
            <textarea className="form-input" data-append-note-raw rows={5}
              style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, resize: 'vertical' }}
              placeholder={'粘贴原始文本，保存时原样入库（含换行与空白）……'}
              value={noteRaw} disabled={disabled} onChange={e => setNoteRaw(e.target.value)} />
          </div>
        </div>
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 12 }}>
        <button className="btn btn-primary" data-append-submit style={{ fontSize: 12.5, padding: '6px 18px' }}
          disabled={disabled || saving || !hasInput} onClick={submit}>
          {saving ? '追加中…' : '＋ 追加材料并重编译 Wiki'}
        </button>
        <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>
          重复追加同一条来源/同一段笔记不会产生重复条目；重编译期间对话框照常可用。
        </span>
      </div>
    </div>
  )
}

// ===== 当前段实时进度 =====

function RunningPanel({ detail }) {
  const running = (detail.steps || []).find(s => s.status === 'RUNNING')
  if (!running) return null

  if (running.key === 'INGEST') {
    const entries = detail.kbEntries || []
    const parsing = entries.filter(e => ['RAW', 'PENDING', 'PARSING'].includes(e.status))
    return (
      <div style={{ marginTop: 14 }}>
        <div style={{ fontSize: 12.5, color: '#3b82f6' }}>
          <span className="spinner" style={{ width: 9, height: 9, marginRight: 6 }} />
          {parsing.length > 0
            ? `正在入库解析关联文档（${entries.length - parsing.length}/${entries.length} 就绪）…`
            : '材料已就绪，即将开始编译 Wiki…'}
        </div>
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
          {entries.map(e => {
            const st = KB_STATUS[e.status] || { label: e.status, color: '#6b7280' }
            return (
              <span key={e.id} className="hook-badge" style={{ color: st.color, background: st.color + '14', borderColor: st.color + '40' }}
                data-tooltip={e.source || e.title} tabIndex={0}>
                {(['PARSING', 'PENDING'].includes(e.status)) && <span className="spinner" style={{ width: 8, height: 8, marginRight: 4, borderWidth: 1.5 }} />}
                {(e.title || `条目#${e.id}`).slice(0, 24)} · {st.label}
              </span>
            )
          })}
        </div>
      </div>
    )
  }

  // WIKI 段
  const wiki = detail.wiki || {}
  return (
    <div style={{ marginTop: 14, fontSize: 12.5, color: '#3b82f6' }}>
      <span className="spinner" style={{ width: 9, height: 9, marginRight: 6 }} />
      正在编译 LLM Wiki…
      {wiki.phase ? `（${wiki.phase}）` : wiki.status ? `（${WIKI_LABEL[wiki.status] || wiki.status}）` : ''}
    </div>
  )
}

// ===== 常驻「待你确认」卡（PRD 11-17-adhoc-blocker-notify 决策 3/4） =====

/**
 * agent 命中卡点（对话轮回复里出现【卡点】，或 Wiki 编译产物里检出【卡点】）时，后端把问题原文
 * 落到 ad_hoc_task.pending_question，本卡据此常驻页面顶部：琥珀色、问题原文、答复框就在下面。
 * 页面这张卡是**权威事实源**，飞书私聊只是提醒——所以通知失败也要在这里明说（notifyError）。
 * 用户在下面回一句（或在对话框回一句）即视为答复：后端清空 pending_question，卡片收起，同一主会话续跑。
 * 问题原文必须可拖选复制（用户最常干的事就是把它拷走），故 detail 轮询只在数据真变化时才 setState。
 */
function PendingQuestionCard({ detail, onAnswer }) {
  const [answer, setAnswer] = useState('')
  const [sending, setSending] = useState(false)
  const inputRef = useRef(null)
  // X1：多行 composer 自适应高度（1 行起，约 6 行封顶内部滚动；发送清空后自动收回单行）
  useEffect(() => { autoGrow(inputRef.current) }, [answer])
  const question = (detail?.pendingQuestion || '').trim()
  if (!question) return null

  async function submit() {
    const text = answer.trim()
    if (!text || sending) return
    setSending(true)
    try { await onAnswer(text); setAnswer('') } finally { setSending(false) }
  }

  return (
    <div className="card" data-pending-question
      style={{ marginTop: 14, padding: '14px 20px', border: '1px solid rgba(245,158,11,.45)', background: 'rgba(245,158,11,.08)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14, fontWeight: 700, color: '#b45309' }}>⏸ 待你确认</span>
        <span style={{ fontSize: 11.5, color: '#b45309' }}>
          agent 已停下本轮等你拍板（提问纪律要求：输出问题之前不改代码、不写文件）
        </span>
        <div style={{ flex: 1 }} />
        {detail.notifyError
          ? <span className="hook-badge" style={{ color: '#dc2626', background: 'rgba(220,38,38,.1)', borderColor: 'rgba(220,38,38,.35)' }}
              data-tooltip="飞书私聊没发出去，但本卡就是权威事实源，在这里答复即可" tabIndex={0}>
              飞书通知失败：{detail.notifyError}
            </span>
          : <span className="hook-badge" style={{ color: '#16a34a', background: 'rgba(22,163,74,.1)', borderColor: 'rgba(22,163,74,.3)' }}>
              飞书已通知{detail.notifiedAt ? ` · ${String(detail.notifiedAt).replace('T', ' ').substring(5, 16)}` : ''}
            </span>}
      </div>
      <div data-pending-question-text style={{ marginTop: 10, padding: '10px 12px', borderRadius: 8, background: 'rgba(255,255,255,.55)', border: '1px solid rgba(245,158,11,.25)', fontSize: 12.5, lineHeight: 1.75, color: 'var(--text)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', userSelect: 'text', cursor: 'text' }}>
        {question}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 10, alignItems: 'flex-end' }}>
        <textarea ref={inputRef} rows={1} className="form-input" data-pending-answer
          style={{ flex: 1, resize: 'none', overflowY: 'auto', fontFamily: 'inherit' }}
          placeholder={`直接在这里答复（例：上限按 20 家）——agent 在同一会话继续（${COMPOSER_HINT}）`}
          value={answer} onChange={e => setAnswer(e.target.value)}
          onKeyDown={e => handleComposerKey(e, submit, { multiline: true })} />
        <button className="btn btn-primary" style={{ flexShrink: 0 }} disabled={!answer.trim() || sending} onClick={submit}>
          {sending ? '发送中…' : '答复并继续'}
        </button>
      </div>
    </div>
  )
}

// ===== 对话框（与绑定实例主会话随问随答：实例空闲立即回答；真有一轮在跑才排队，忙完即答） =====

/** 单条对话行是否等价（用于轮询合并：等价则复用旧对象引用，React 直接跳过重渲染） */
const sameMsg = (a, b) => !!a && !!b && a.id === b.id && a.role === b.role && a.status === b.status
  && a.text === b.text && String(a.createdAt) === String(b.createdAt)

/**
 * 轮询回来的行与现有行合并：内容全等 → 返回原数组引用（setState 直接 bail out，一次重渲染都不发生）；
 * 部分变化 → 未变的行保留原对象引用（配合 React.memo 的气泡，PENDING 行的进度文字每 2.5s 变化时，
 * 已完成回复的 markdown 不会被重新解析、DOM 子树不被替换，用户拖到一半的选区不会被冲掉）。
 */
function mergeMsgs(prev, next) {
  if (!Array.isArray(next)) return prev
  if (prev.length === next.length && prev.every((row, i) => sameMsg(row, next[i]))) return prev
  return next.map(row => {
    const old = prev.find(p => p.id === row.id)
    return sameMsg(old, row) ? old : row
  })
}

/** 单条气泡：memo 化，只有本行数据（或 PENDING 行的 tick）变化才重渲染 markdown */
const MessageBubble = memo(function MessageBubble({ m, tick }) {
  if (m.role === 'user') {
    return (
      <div data-msg-id={m.id} data-role="user" style={{ alignSelf: 'flex-end', maxWidth: '78%', background: 'var(--accent,#2563eb)', color: '#fff', borderRadius: '10px 10px 2px 10px', padding: '7px 11px', fontSize: 12.5, whiteSpace: 'pre-wrap', userSelect: 'text' }}>
        {m.text}
        <div style={{ fontSize: 10, opacity: .65, marginTop: 3 }}>{String(m.createdAt).substring(11, 16)}</div>
      </div>
    )
  }
  const blocked = m.status === 'BLOCKED'
  return (
    <div data-msg-id={m.id} data-role="agent" style={{
      alignSelf: 'flex-start', maxWidth: '86%', fontSize: 12.5,
      background: blocked ? 'rgba(245,158,11,.08)' : 'rgba(107,114,128,.1)',
      border: blocked ? '1px solid rgba(245,158,11,.45)' : '1px solid var(--border,#e5e7eb)',
      borderRadius: '10px 10px 10px 2px', padding: '7px 11px',
    }}>
      {blocked && (
        <div style={{ fontSize: 11, fontWeight: 700, color: '#b45309', marginBottom: 4 }}>
          ⏸ 待你确认 · 本轮已停手（问题原文见页面顶部「待你确认」卡）
        </div>
      )}
      {m.status === 'PENDING' ? (
        m.text ? (
          // 后端回写的实时进度摘要 — 灰色小字 + spinner，不走 ReactMarkdown（防止误认成最终答案）
          <span style={{ color: 'var(--text-dim)', fontSize: 12 }}>
            <span className="spinner" style={{ width: 9, height: 9, display: 'inline-block', marginRight: 6 }} />
            {m.text}
          </span>
        ) : (
          <span style={{ color: 'var(--text-dim)' }}><span className="spinner" style={{ width: 9, height: 9, display: 'inline-block', marginRight: 6 }} />处理中…</span>
        )
      ) : (
        <div className="md-body" style={{ color: m.status === 'FAILED' ? 'var(--error)' : 'var(--text)', userSelect: 'text' }}>
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.text}</ReactMarkdown>
        </div>
      )}
      <div style={{ fontSize: 10, color: 'var(--text-dim)', marginTop: 3 }}>
        {String(m.createdAt).substring(11, 16)}
        {m.status === 'PENDING' && elapsedLabel(m.createdAt) ? ` · ${elapsedLabel(m.createdAt)}` : ''}
      </div>
    </div>
  )
})

function ChatPanel({ taskId, showToast, wikiReady = true, taskStatus = '', chatNonce = 0 }) {
  const [msgs, setMsgs] = useState([])
  const [q, setQ] = useState('')
  const [sending, setSending] = useState(false)
  const [tick, setTick] = useState(0)   // 每次轮询 +1：只喂给 PENDING 行刷新"已运行"时长，终态气泡不受影响
  const bodyRef = useRef(null)
  const inputRef = useRef(null)
  // X1：多行 composer 自适应高度（1 行起，约 6 行封顶内部滚动；发送清空后自动收回单行）
  useEffect(() => { autoGrow(inputRef.current) }, [q])

  const load = () => fetch(`/api/ad-hoc-tasks/${taskId}/chat`)
    .then(r => r.ok ? r.json() : [])
    .then(rows => { setMsgs(prev => mergeMsgs(prev, rows)); setTick(t => t + 1) })
    .catch(() => {})
  const hasPending = msgs.some(m => m.status === 'PENDING')
  useEffect(() => {
    let stopped = false
    const poll = () => { if (!stopped) load() }
    poll()
    const t = setInterval(poll, hasPending ? 2500 : 8000)
    return () => { stopped = true; clearInterval(t) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskId, hasPending, chatNonce])
  useEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight
  }, [msgs.length, msgs[msgs.length - 1]?.text?.length])

  async function send() {
    if (!q.trim() || sending) return
    setSending(true)
    const res = await fetch(`/api/ad-hoc-tasks/${taskId}/chat`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: q }),
    })
    const d = await res.json().catch(() => ({}))
    setSending(false)
    if (!res.ok) return showToast(d.error || '发送失败', 'error')
    setQ('')
    load()
  }

  const placeholder = (msgs.some(m => m.status === 'BLOCKED') || !wikiReady)
    ? `回一句就是答复（例：上限按 20 家）——agent 在同一会话继续…（${COMPOSER_HINT}）`
    : `吩咐临时小改，例如：某供应商把历史接口字段改了，帮我改一下…（${COMPOSER_HINT}）`

  return (
    <div className="card" style={{ marginTop: 14, padding: '14px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14, fontWeight: 700 }}>💬 对话框</span>
        {!wikiReady && <span className="hook-badge" style={{ color: '#b45309', background: 'rgba(245,158,11,.12)', borderColor: 'rgba(245,158,11,.4)' }}>Wiki 未就绪 · 仍可对话</span>}
        <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>
          {wikiReady
            ? '直接吩咐临时小改——每轮自动带上本任务 Wiki 入口；与飞书私聊同一会话上下文，实例空闲立即回答，正忙时排队、忙完即答（是否排队以下方实时进度为准）'
            : `现在就能说，不用等流水线——流水线仍在「${TASK_STATUS_LABEL[taskStatus] || taskStatus || '推进中'}」，本轮不带 Wiki 入口，需要引用文档请直接给路径。与飞书私聊同一会话上下文，实例空闲立即回答，正忙时排队、忙完即答。`}
        </span>
      </div>
      <div ref={bodyRef} data-chat-body style={{ maxHeight: 360, overflowY: 'auto', marginTop: 10, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {msgs.length === 0 && <div style={{ fontSize: 12, color: 'var(--text-dim)', padding: '12px 0', textAlign: 'center' }}>（还没有对话 — 例如："某供应商把历史某个接口的字段改了，帮我改一下"）</div>}
        {msgs.map(m => <MessageBubble key={m.id} m={m} tick={m.status === 'PENDING' ? tick : 0} />)}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 10, alignItems: 'flex-end' }}>
        <textarea ref={inputRef} rows={1} className="form-input" data-chat-input
          style={{ flex: 1, resize: 'none', overflowY: 'auto', fontFamily: 'inherit' }}
          placeholder={placeholder}
          value={q} onChange={e => setQ(e.target.value)} onKeyDown={e => handleComposerKey(e, send, { multiline: true })} />
        <button className="btn btn-primary" style={{ flexShrink: 0 }} disabled={!q.trim() || sending} onClick={send}>
          {sending ? '发送中…' : '📤 发送'}
        </button>
      </div>
    </div>
  )
}
