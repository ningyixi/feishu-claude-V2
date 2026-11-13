import Toast from '../components/ui/Toast'
import { useConfirm, DraftPage } from '../components/ui/FloatingUI'
import { useState, useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useSearchParams, Link } from 'react-router-dom'
import { useOccupied, KbPanel } from './Supplier'

// ===== 常量 =====

const STEPS = [
  { key: 'KB', label: '入库解析', icon: '📥' },
  { key: 'ANALYSIS', label: '接口分析', icon: '🤖' },
  { key: 'PROBE', label: '接口实测', icon: '🔌' },
  { key: 'WIKI', label: '建档 Wiki', icon: '🕸️' },
]
const STEP_STYLE = {
  DONE: { color: '#16a34a', label: '已完成' },
  RUNNING: { color: '#3b82f6', label: '进行中' },
  FAILED: { color: '#dc2626', label: '失败' },
  PENDING: { color: '#9ca3af', label: '待开始' },
}
const WIKI_LABEL = { EMPTY: '尚未编译', BUILDING: '正在编译', READY: '已就绪', STALE: '来源已变化', FAILED: '编译失败', ERROR: '状态未知' }
const KB_STATUS = {
  RAW: { label: '待解析', color: '#6b7280' },
  PENDING: { label: '排队解析', color: '#6b7280' },
  PARSING: { label: '解析中', color: '#3b82f6' },
  DONE: { label: '已解析', color: '#16a34a' },
  FAILED: { label: '解析失败', color: '#dc2626' },
}
const stepOf = (detail, key) => (detail?.steps || []).find(s => s.key === key) || { key, status: 'PENDING' }
const nodeOf = (detail, nodeId) => (detail?.nodes || []).find(n => n.nodeId === nodeId)

// ===== 主页面（视图路由） =====

export default function QuickProbe() {
  const [route, setRoute] = useSearchParams()
  const taskId = /^\d+$/.test(route.get('task') || '') ? Number(route.get('task')) : null
  const view = route.get('view') || (taskId ? 'detail' : 'list')
  const [toast, setToast] = useState(null)

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  return (
    <div>
      {view === 'list' && <TaskList onOpen={id => setRoute({ task: String(id) })}
        onCreate={() => setRoute({ view: 'create' })} showToast={showToast} />}
      {view === 'create' && <CreateTask onBack={() => setRoute({})}
        onCreated={id => setRoute({ task: String(id) })} showToast={showToast} />}
      {view === 'detail' && taskId && <TaskDetail taskId={taskId} onBack={() => setRoute({})} showToast={showToast} />}
      <Toast toast={toast} onDismiss={() => setToast(null)} />
    </div>
  )
}

// ===== 任务列表 =====

function TaskList({ onOpen, onCreate, showToast }) {
  const confirmAction = useConfirm()
  const [tasks, setTasks] = useState([])

  const load = () => fetch('/api/quick-probes').then(r => r.ok ? r.json() : []).then(rows => { if (Array.isArray(rows)) setTasks(rows) }).catch(() => {})
  useEffect(() => {
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [])

  async function remove(task, e) {
    e.stopPropagation()
    if (!await confirmAction({ kind: 'popconfirm', title: '删除建档任务？', body: `删除任务「${task.name}」？任务与节点记录将清除，删除后无法恢复。`, confirmLabel: '删除任务' })) return
    const res = await fetch(`/api/quick-probes/${task.id}`, { method: 'DELETE' })
    if (!res.ok) { const d = await res.json().catch(() => ({})); return showToast(d.error || '删除失败', 'error') }
    showToast('已删除', 'success')
    load()
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.2rem' }}>
        <div>
          <h1 className="page-title" style={{ margin: 0 }}>接口建档</h1>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 4 }}>
            录入供应商接口文档，自动完成 入库解析 → 接口分析 → Postman 实测 → 建档 LLM Wiki。独立工具，五分钟开箱即用，无需创建工作台工作。
          </div>
        </div>
        <button className="btn btn-primary" onClick={onCreate}>+ 新建建档任务</button>
      </div>

      {tasks.length === 0 && (
        <div className="card" style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
          还没有建档任务。点「+ 新建建档任务」，贴上文档链接即可开始。
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {tasks.map(t => {
          const done = t.phase === 'DONE'
          const failed = !!t.error
          const step = STEPS.find(s => s.key === t.phase)
          return (
            <div key={t.id} className="card job-card" onClick={() => onOpen(t.id)} style={{ cursor: 'pointer', padding: '16px 18px', marginBottom: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 15, fontWeight: 700, flex: 1 }}>{done ? '✅' : failed ? '⚠️' : '🛰️'} {t.name}</span>
                <span className="hook-badge">{t.supplierCode}</span>
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 8, lineHeight: 1.9 }}>
                <div>当前段: {done ? '建档完成' : (step ? `${step.icon} ${step.label}` : t.phase || '—')}
                  {t.running && !done && <span className="spinner" style={{ width: 9, height: 9, marginLeft: 6 }} />}
                  {failed && <span style={{ color: 'var(--error)', marginLeft: 6 }}>失败，可从该段重试</span>}
                </div>
                <div>更新: {String(t.updatedAt || '').replace('T', ' ').substring(5, 16)}</div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
                <button className="btn" style={{ fontSize: 12, padding: '2px 10px', color: 'var(--error)' }} onClick={e => remove(t, e)}>删除</button>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

// ===== 新建任务（极简四字段） =====

function CreateTask({ onBack, onCreated, showToast }) {
  const pageFormRef = useRef(null)
  const occupied = useOccupied()
  const [instances, setInstances] = useState([])
  const [form, setForm] = useState({ identifier: '', name: '', instanceId: '', sourcesText: '', siteCrawl: false })
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {})
  }, [])

  async function submit() {
    if (!form.identifier.trim()) return showToast('请填写供应商标识', 'error')
    if (!form.instanceId) return showToast('请绑定执行实例', 'error')
    const sources = form.sourcesText.split('\n').map(s => s.trim()).filter(Boolean)
    if (sources.length === 0) return showToast('请至少填写 1 条文档来源', 'error')
    setSaving(true)
    try {
      const res = await fetch('/api/quick-probes', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          identifier: form.identifier.trim(),
          name: form.name.trim() || null,
          instanceId: Number(form.instanceId),
          sources,
          siteCrawl: form.siteCrawl,
        }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || '创建失败')
      showToast('任务已创建，流水线开始推进', 'success')
      pageFormRef.current?.commit(); onCreated(data.id)
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <DraftPage ref={pageFormRef}><div style={{ maxWidth: 720, margin: '0 auto' }}>
      <h1 className="page-title">🛰️ 新建建档任务</h1>
      <div className="card">
        <div className="form-group">
          <label className="form-label">供应商标识 *（作为知识库命名空间，任意文本，如：游由海外/shyytrip）</label>
          <input className="form-input" placeholder="如：shyytrip" value={form.identifier}
            onChange={e => setForm(f => ({ ...f, identifier: e.target.value }))} />
        </div>
        <div className="form-group">
          <label className="form-label">文档来源 *（每行一条：http(s) 链接 / .pdf / .md/.txt 本地路径）</label>
          <textarea className="form-input" rows={5} style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, resize: 'vertical' }}
            placeholder={'https://docs.supplier.com/api\nD:\\docs\\supplier-api.pdf'}
            value={form.sourcesText} onChange={e => setForm(f => ({ ...f, sourcesText: e.target.value }))} />
          <label style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 11.5, color: 'var(--text-dim)', marginTop: 6, cursor: 'pointer' }}
            data-tooltip="对 http(s) 来源启用同站点目录递归抓取：严格同源 + 入口第一级路径，最多 200 页" tabIndex={0}>
            <input type="checkbox" checked={form.siteCrawl} onChange={e => setForm(f => ({ ...f, siteCrawl: e.target.checked }))} />
            🕸 URL 来源按同站点目录抓取（最多 200 页）
          </label>
        </div>
        <div className="form-group">
          <label className="form-label">执行实例 *（跑分析/实测/Wiki 编译）</label>
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
        <div className="form-group">
          <label className="form-label">任务名（选填，默认 = 供应商标识）</label>
          <input className="form-input" placeholder="如：shyytrip 接口建档" value={form.name}
            onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
          <button className="btn" onClick={onBack}>取消</button>
          <button className="btn btn-primary" onClick={submit} disabled={saving}>{saving ? '创建中…' : '🚀 一键启动建档'}</button>
        </div>
      </div>
    </div></DraftPage>
  )
}

// ===== 任务详情（步骤条 + 实时进度 + 结果卡） =====

function TaskDetail({ taskId, onBack, showToast }) {
  const confirmAction = useConfirm()
  const [detail, setDetail] = useState(null)
  const [notFound, setNotFound] = useState(false)
  const pollRef = useRef(null)

  const load = () => fetch(`/api/quick-probes/${taskId}`)
    .then(async r => ({ ok: r.ok, data: await r.json().catch(() => ({})) }))
    .then(({ ok, data }) => { if (ok) setDetail(data); else setNotFound(true) })
    .catch(() => {})

  useEffect(() => {
    load()
    // 推进中 3s 轮询进度；建档完成后放缓（详情含 Wiki 指纹重算，无需高频）
    pollRef.current = setInterval(load, detail && detail.phase === 'DONE' ? 15000 : 3000)
    return () => clearInterval(pollRef.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskId, detail && detail.phase === 'DONE'])

  async function retry(stepKey) {
    const res = await fetch(`/api/quick-probes/${taskId}/retry/${stepKey.toLowerCase()}`, { method: 'POST' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '重试失败', 'error')
    showToast('已从该段续跑', 'success')
    setDetail(d)
  }
  async function remove() {
    if (!await confirmAction({ kind: 'popconfirm', title: '删除建档任务？', body: `删除任务「${detail.name}」？任务与节点记录将清除，删除后无法恢复。`, confirmLabel: '删除任务' })) return
    const res = await fetch(`/api/quick-probes/${taskId}`, { method: 'DELETE' })
    if (!res.ok) { const d = await res.json().catch(() => ({})); return showToast(d.error || '删除失败', 'error') }
    showToast('已删除', 'success')
    onBack()
  }

  if (notFound) return <div className="card">任务不存在或已删除 <button className="btn" onClick={onBack}>返回列表</button></div>
  if (!detail) return <div className="card">加载中… <button className="btn" onClick={onBack}>返回</button></div>

  const done = detail.phase === 'DONE'
  const failedStep = detail.error ? (detail.steps || []).find(s => s.status === 'FAILED') : null

  return (
    <div>
      {/* 头部 */}
      <div className="card" style={{ padding: '12px 18px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <button className="btn" style={{ fontSize: 12, padding: '4px 12px' }} onClick={onBack}>← 接口建档</button>
          <span style={{ fontSize: 16, fontWeight: 700 }}>{done ? '✅' : '🛰️'} {detail.name}</span>
          <span className="hook-badge">{detail.supplierCode}</span>
          {detail.running && !done && <span className="hook-badge" style={{ color: '#3b82f6' }}><span className="spinner" style={{ width: 9, height: 9, marginRight: 5 }} />流水线推进中</span>}
          <div style={{ flex: 1 }} />
          <button className="btn" style={{ fontSize: 11, padding: '2px 10px', color: 'var(--error)' }} onClick={remove}>删除任务</button>
          <Link to={`/supplier?job=${detail.id}`} className="btn" style={{ fontSize: 11, padding: '2px 10px' }}
            data-tooltip="在供应商工作台打开本任务详情（知识库与 Wiki 可在那里查看管理）" tabIndex={0}>在工作台打开</Link>
        </div>
      </div>

      {/* 四段步骤条 */}
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

        {/* 失败原因 + 从本段重试 */}
        {failedStep && (
          <div style={{ marginTop: 14, padding: '10px 12px', border: '1px solid rgba(220,38,38,.4)', background: 'rgba(220,38,38,.06)', borderRadius: 8 }}>
            <div style={{ fontSize: 12.5, fontWeight: 700, color: '#dc2626' }}>
              ⚠️ 「{STEPS.find(s => s.key === failedStep.key)?.label}」段失败
            </div>
            <div style={{ fontSize: 12.5, color: 'var(--text)', marginTop: 6, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{detail.error}</div>
            <button className="btn btn-primary" style={{ fontSize: 12, padding: '4px 14px', marginTop: 8 }} onClick={() => retry(failedStep.key)}>
              🔄 从本段重试
            </button>
          </div>
        )}

        {/* 当前段实时进度 */}
        {!done && !failedStep && <RunningPanel detail={detail} />}
      </div>

      {/* 完成结果卡：以查看本次分析产物为主 */}
      {done && <ResultCard detail={detail} />}

      {/* 知识库条目（只读查看） */}
      <KbPanel kbCode={detail.kbCode} showToast={showToast} readOnly />
    </div>
  )
}

// ===== 当前段实时进度 =====

function RunningPanel({ detail }) {
  const running = (detail.steps || []).find(s => s.status === 'RUNNING')
  if (!running) return null
  const wiki = detail.wiki || {}

  if (running.key === 'KB') {
    const entries = detail.kbEntries || []
    const parsing = entries.filter(e => ['RAW', 'PENDING', 'PARSING'].includes(e.status))
    return (
      <div style={{ marginTop: 14 }}>
        <div style={{ fontSize: 12.5, color: '#3b82f6' }}>
          <span className="spinner" style={{ width: 9, height: 9, marginRight: 6 }} />
          {parsing.length > 0
            ? `正在入库解析文档材料（${entries.length - parsing.length}/${entries.length} 就绪）…`
            : '材料已就绪，正在编译初版 Wiki（分析/实测段的上下文需要）…'}
          {wiki.status === 'BUILDING' && wiki.phase ? `（${wiki.phase}）` : ''}
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

  if (running.key === 'ANALYSIS' || running.key === 'PROBE') {
    const node = nodeOf(detail, running.key === 'ANALYSIS' ? 'n1' : 'n2')
    return (
      <div style={{ marginTop: 14 }}>
        <div style={{ fontSize: 12.5, color: '#3b82f6' }}>
          <span className="spinner" style={{ width: 9, height: 9, marginRight: 6 }} />
          {running.key === 'ANALYSIS' ? '实例正在阅读文档、产出接口映射分析…' : '实例正在按 Postman 实测协议真实调用接口…'}
        </div>
        {node?.pendingQuestion ? (
          <div style={{ marginTop: 8, padding: '10px 12px', border: '1px solid #f59e0b', background: 'rgba(245,158,11,.08)', borderRadius: 8 }}>
            <div style={{ fontSize: 12, fontWeight: 700, color: '#b45309', marginBottom: 6 }}>❓ 实例提出卡点，等待答复（答复后流水线自动继续）</div>
            <div className="md-body" style={{ fontSize: 12.5, maxHeight: 200, overflowY: 'auto' }}>
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{node.pendingQuestion}</ReactMarkdown>
            </div>
            <div style={{ fontSize: 11.5, color: 'var(--text-dim)', marginTop: 6 }}>
              请<Link to={`/supplier?job=${detail.id}`} style={{ color: 'var(--accent)', margin: '0 3px' }}>在工作台打开本任务</Link>在节点卡中答复卡点。
            </div>
          </div>
        ) : (
          <div style={{ marginTop: 8, fontSize: 12, color: '#3b82f6', background: 'rgba(59,130,246,.07)', border: '1px solid rgba(59,130,246,.25)', borderRadius: 6, padding: '5px 10px', wordBreak: 'break-all' }}>
            正在：{node?.progress || '思考中（首个工具动作到达前略等）…'}
            {node?.thinking && (
              <div style={{ marginTop: 4, fontSize: 11.5, color: '#7c3aed', maxHeight: 72, overflowY: 'auto', wordBreak: 'break-all' }}>💭 {node.thinking}</div>
            )}
          </div>
        )}
      </div>
    )
  }

  // WIKI 段
  return (
    <div style={{ marginTop: 14, fontSize: 12.5, color: '#3b82f6' }}>
      <span className="spinner" style={{ width: 9, height: 9, marginRight: 6 }} />
      正在归档分析/实测产物并编译 LLM Wiki…
      {wiki.phase ? `（${wiki.phase}）` : wiki.status ? `（${WIKI_LABEL[wiki.status] || wiki.status}）` : ''}
    </div>
  )
}

// ===== 完成结果卡：查看本次分析产物 =====

function ResultCard({ detail }) {
  const wiki = detail.wiki || {}
  const n1 = nodeOf(detail, 'n1')
  const n2 = nodeOf(detail, 'n2')
  const pageCount = Array.isArray(wiki.pages) ? wiki.pages.length : null

  return (
    <div className="card" style={{ marginTop: 14, padding: '16px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14, fontWeight: 700 }}>📦 建档完成 · 查看本次分析产物</span>
        <div style={{ flex: 1 }} />
        <Link to={`/supplier?job=${detail.id}`} style={{ fontSize: 12, color: 'var(--text-dim)' }}>在工作台打开 →</Link>
      </div>

      {/* Wiki 状态卡 */}
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 12 }}>
        <span className="hook-badge" style={{ color: '#16a34a', background: 'rgba(22,163,74,.1)', borderColor: 'rgba(22,163,74,.3)' }}>
          🕸️ Wiki {WIKI_LABEL[wiki.status] || wiki.status || '—'}
        </span>
        {wiki.revision != null && <span className="hook-badge dim">版本 #{wiki.revision}</span>}
        {wiki.sourceCount != null && <span className="hook-badge dim">来源 {wiki.sourceCount} 个</span>}
        {pageCount != null && <span className="hook-badge dim">页面 {pageCount} 页</span>}
      </div>

      {/* 分析产物 */}
      <details open style={{ marginTop: 14 }}>
        <summary style={{ fontSize: 13, fontWeight: 700, cursor: 'pointer' }}>🤖 接口分析（文档 → 接口映射）</summary>
        {n1?.artifact
          ? <div className="artifact-box md-body" style={{ maxHeight: 420, overflowY: 'auto', marginTop: 8 }}><ReactMarkdown remarkPlugins={[remarkGfm]}>{n1.artifact}</ReactMarkdown></div>
          : <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6 }}>（无分析产物）</div>}
      </details>

      {/* 实测档案 */}
      <details style={{ marginTop: 14 }}>
        <summary style={{ fontSize: 13, fontWeight: 700, cursor: 'pointer' }}>
          🔌 实测档案（真实调用与响应）
          {n2?.hasEvidence
            ? <span className="hook-badge" style={{ marginLeft: 8, color: '#16a34a', background: 'rgba(22,163,74,.1)', borderColor: 'rgba(22,163,74,.3)' }}>含真实调用证据</span>
            : <span className="hook-badge dim" style={{ marginLeft: 8 }}>未采集到真实调用证据（接口可能未接通）</span>}
        </summary>
        {n2?.artifact
          ? <div className="artifact-box md-body" style={{ maxHeight: 420, overflowY: 'auto', marginTop: 8 }}><ReactMarkdown remarkPlugins={[remarkGfm]}>{n2.artifact}</ReactMarkdown></div>
          : <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6 }}>（无实测产物）</div>}
      </details>
    </div>
  )
}
