import Toast from '../components/ui/Toast'
import { Surface, useConfirm, DraftPage, useDirtyValue } from '../components/ui/FloatingUI'
import { useState, useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import SupplierWikiPanel from '../components/SupplierWikiPanel'
import SupplierWorkflowPanel from '../components/SupplierWorkflowPanel'
import { useSearchParams } from 'react-router-dom'

// ===== 常量 =====

const NODE_TYPE = {
  agent: { icon: '🤖', label: '实例执行', color: '#3b82f6' },
  agent_check: { icon: '🔍', label: '实例检查', color: '#8b5cf6' },
  manual: { icon: '✋', label: '手动任务', color: '#f59e0b' },
  reminder: { icon: '📌', label: '配置提醒', color: '#6b7280' },
}
const NODE_STATUS = {
  PENDING: { color: '#3b82f6', label: '未完成' },
  RUNNING: { color: '#0891b2', label: '执行中' },
  DONE: { color: '#16a34a', label: '已完成' },
}

const emptyDoc = { type: 'url', value: '' }

// ===== 主页面（视图路由） =====

export default function Supplier() {
  const [route, setRoute] = useSearchParams()
  const jobId = /^\d+$/.test(route.get('job') || '') ? Number(route.get('job')) : null
  const view = route.get('view') || (jobId ? 'game' : 'list')
  const [toast, setToast] = useState(null)

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  return (
    <div>
      {view === 'list' && <JobList onOpen={id => setRoute({ job: String(id) })}
        onCreate={() => setRoute({ view: 'create' })} onWorkflows={() => setRoute({ view: 'workflows' })} showToast={showToast} />}
      {view === 'create' && <CreateJob onBack={() => setRoute({})}
        onCreated={id => setRoute({ job: String(id) })} showToast={showToast} />}
      {view === 'game' && jobId && <GameView jobId={jobId} onBack={() => setRoute({})} showToast={showToast} />}
      {view === 'workflows' && <WorkflowEditor onBack={() => setRoute({})} showToast={showToast} />}
      <Toast toast={toast} onDismiss={() => setToast(null)} />
    </div>
  )
}

// ===== 工作列表 =====

function JobList({ onOpen, onCreate, onWorkflows, showToast }) {
  const confirmAction = useConfirm()
  const [jobs, setJobs] = useState([])
  const [instances, setInstances] = useState([])

  const load = () => fetch('/api/supplier-jobs').then(r => r.json()).then(setJobs).catch(() => {})
  useEffect(() => {
    load()
    fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {})
  }, [])

  const instName = id => instances.find(i => String(i.id) === String(id))?.name || (id ? `#${id}` : '未绑定')

  async function remove(j, e) {
    e.stopPropagation()
    if (!await confirmAction({ kind: 'popconfirm', title: '删除工作？', body: `删除工作「${j.name}」？存档和节点记录将清除，删除后无法恢复。`, confirmLabel: '删除工作' })) return
    const response = await fetch(`/api/supplier-jobs/${j.id}`, { method: 'DELETE' }); if (!response.ok) { const body = await response.json(); return showToast(body.error || '删除失败', 'error') }
    showToast('已删除', 'success')
    load()
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.2rem' }}>
        <div>
          <h1 className="page-title" style={{ margin: 0 }}>供应商接入工作台</h1>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 4 }}>
            每个工作 = 一局接入供应商的"游戏"：沿工作流节点推进，实例帮你干活，点完成变绿，全绿通关
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={onWorkflows}>🛠 工作流模板</button>
          <button className="btn btn-primary" onClick={onCreate}>+ 新建工作</button>
        </div>
      </div>

      {jobs.length === 0 && (
        <div className="card" style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
          还没有进行中的接入工作。点「+ 新建工作」开一局。
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14 }}>
        {jobs.map(j => {
          const done = j.status === 'COMPLETED'
          return (
            <div key={j.id} className="card job-card" onClick={() => onOpen(j.id)} style={{ cursor: 'pointer', padding: '16px 18px', marginBottom: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{ fontSize: 15, fontWeight: 700, flex: 1 }}>{done ? '🏆' : '🎮'} {j.name}</span>
                <span className="hook-badge">{j.mode === 'API' ? 'API 供应商' : 'CM 供应商'}</span>
                <CreatorBadge id={j.createdByEmployeeId} name={j.createdByEmployeeName} dim />
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 8, lineHeight: 1.9 }}>
                <div>供应商: {j.supplierName || '—'}{j.supplierCode ? ` (ID: ${j.supplierCode})` : ''}</div>
                <div>知识表示: {j.knowledgeMode || 'TEXT'} · 执行实例: {instName(j.instanceId)} · 当前节点: {j.currentNodeId || (done ? '已通关' : '—')}</div>
                <div>更新: {String(j.updatedAt || '').replace('T', ' ').substring(5, 16)}</div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
                <button className="btn" style={{ fontSize: 12, padding: '2px 10px', color: 'var(--error)' }} onClick={e => remove(j, e)}>删除</button>
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}

// ===== 新建工作（选模式 → 填"角色卡"） =====

function CreateJob({ onBack, onCreated, showToast }) {
  const pageFormRef = useRef(null)
  const occupied = useOccupied()
  const [step, setStep] = useState(1)
  const [mode, setMode] = useState('API')
  const [templates, setTemplates] = useState([])
  const [instances, setInstances] = useState([])
  const [form, setForm] = useState({
    name: '', supplierCode: '', supplierName: '', libraryPath: '',
    docs: [{ ...emptyDoc }], skills: [], instanceId: '', templateId: '', knowledgeMode: 'TEXT',
  })
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetch('/api/workflows').then(r => r.json()).then(setTemplates).catch(() => {})
    fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {})
  }, [])

  const modeTemplates = templates.filter(t => t.mode === mode)

  async function submit() {
    if (!form.supplierName.trim()) return showToast('请填写供应商名称', 'error')
    if (!form.templateId) return showToast('请选择工作流模板', 'error')
    setSaving(true)
    try {
      const res = await fetch('/api/supplier-jobs', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...form,
          docs: form.docs.filter(d => d.value.trim()),
          skills: form.skills.filter(s => s.trim()),
          instanceId: form.instanceId ? Number(form.instanceId) : null,
          templateId: Number(form.templateId),
        }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '创建失败')
      showToast('存档创建成功，游戏开始 🎮', 'success')
      pageFormRef.current?.commit(); onCreated(data.id)
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <DraftPage ref={pageFormRef}><div style={{ maxWidth: 760, margin: '0 auto' }}>
      <h1 className="page-title">🎮 新建接入工作 {step === 1 ? '· 选择体验模式' : '· 录入基础信息'}</h1>

      {step === 1 && (
        <div className="card">
          <div className="mode-grid">
            <div className={'mode-card' + (mode === 'API' ? ' selected' : '')} onClick={() => setMode('API')}>
              <div style={{ fontSize: 30 }}>🔌</div>
              <div style={{ fontWeight: 700, margin: '8px 0 4px' }}>新接 API 供应商</div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)', lineHeight: 1.7 }}>
                直连供应商 REST/API：文档分析 → 接口实测 → 写代码 → 依赖检查 → 配置 → 联调 → 上线
              </div>
            </div>
            <div className="mode-card disabled" data-tooltip="CM 模式工作流尚未开发" tabIndex={0}>
              <div style={{ fontSize: 30 }}>🏨</div>
              <div style={{ fontWeight: 700, margin: '8px 0 4px' }}>新接 CM 供应商</div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)'}}>即将上线（可先到工作流模板里自行设计）</div>
            </div>
          </div>
          <div className="form-group" style={{ marginTop: 16 }}>
            <label className="form-label">知识组织方式（本次工作固定，用于 A/B 测试）</label>
            <select className="form-input" value={form.knowledgeMode} onChange={e => setForm(f => ({ ...f, knowledgeMode: e.target.value }))}>
              <option value="TEXT">A · 纯文本：按阶段交付原文正文</option>
              <option value="WIKI">B · LLM Wiki：编译知识页、关键约束与来源链接</option>
            </select>
            <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6 }}>两种方式均保留原始材料。Wiki 模式需要绑定实例完成编译，不会自动退回纯文本。</div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 16 }}>
            <button className="btn" onClick={onBack}>取消</button>
            <button className="btn btn-primary" onClick={() => {
              const t = modeTemplates.find(x => x.builtin) || modeTemplates[0]
              setForm(f => ({ ...f, templateId: t ? String(t.id) : '' }))
              setStep(2)
            }} disabled={mode !== 'API'}>下一步 →</button>
          </div>
        </div>
      )}

      {step === 2 && (
        <div className="card">
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div className="form-group">
              <label className="form-label">供应商名称 *</label>
              <input className="form-input" placeholder="如：Dorak" value={form.supplierName}
                onChange={e => setForm(f => ({ ...f, supplierName: e.target.value }))} />
            </div>
            <div className="form-group">
              <label className="form-label">供应商 ID（Suppliers 枚举值，可后补）</label>
              <input className="form-input" placeholder="如：2166" value={form.supplierCode}
                onChange={e => setForm(f => ({ ...f, supplierCode: e.target.value }))} />
            </div>
          </div>
          <div className="form-group">
            <label className="form-label">供应商类库绝对路径 *</label>
            <input className="form-input" placeholder="D:\dida\SupplierV2\src\Business\Supplier\Dorak" value={form.libraryPath}
              onChange={e => setForm(f => ({ ...f, libraryPath: e.target.value }))} />
          </div>
          <div className="form-group">
            <label className="form-label">接口文档（可添加多个；实例会抓取链接 / 读取本地文件）</label>
            {form.docs.map((d, i) => (
              <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
                <select className="form-input" style={{ width: 130, flexShrink: 0 }} value={d.type}
                  onChange={e => setForm(f => ({ ...f, docs: f.docs.map((x, xi) => xi === i ? { ...x, type: e.target.value } : x) }))}>
                  <option value="url">🔗 链接</option>
                  <option value="path">📁 本地路径</option>
                </select>
                <input className="form-input" style={{ flex: 1 }} placeholder={d.type === 'url' ? 'https://docs.supplier.com/api' : 'D:\\docs\\supplier-api.pdf 或 .md 本地文档'}
                  value={d.value}
                  onChange={e => setForm(f => ({ ...f, docs: f.docs.map((x, xi) => xi === i ? { ...x, value: e.target.value } : x) }))} />
                <button className="btn" style={{ flexShrink: 0, padding: '4px 10px' }}
                  onClick={() => setForm(f => ({ ...f, docs: f.docs.filter((_, xi) => xi !== i) }))}>✕</button>
              </div>
            ))}
            <button className="btn" style={{ fontSize: 12, padding: '4px 12px' }}
              onClick={() => setForm(f => ({ ...f, docs: [...f.docs, { ...emptyDoc }] }))}>+ 添加文档</button>
          </div>
          <div className="form-group">
            <label className="form-label">关联 Skill（可选，可多个；实例执行节点时会先读这些目录的 SKILL.md 并遵循其指导）</label>
            {form.skills.map((s, i) => (
              <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
                <span style={{ display: 'flex', alignItems: 'center', fontSize: 15, flexShrink: 0 }}>🧠</span>
                <input className="form-input" style={{ flex: 1 }} placeholder="D:\feishu-claude-data\create-supplier"
                  value={s}
                  onChange={e => setForm(f => ({ ...f, skills: f.skills.map((x, xi) => xi === i ? e.target.value : x) }))} />
                <button className="btn" style={{ flexShrink: 0, padding: '4px 10px' }}
                  onClick={() => setForm(f => ({ ...f, skills: f.skills.filter((_, xi) => xi !== i) }))}>✕</button>
              </div>
            ))}
            <button className="btn" style={{ fontSize: 12, padding: '4px 12px' }}
              onClick={() => setForm(f => ({ ...f, skills: [...f.skills, ''] }))}>+ 添加 Skill</button>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div className="form-group">
              <label className="form-label">执行实例（跑分析/检查任务，可中途切换）</label>
              <select className="form-input" value={form.instanceId}
                onChange={e => setForm(f => ({ ...f, instanceId: e.target.value }))}>
                <option value="">（稍后绑定）</option>
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
              <label className="form-label">工作流模板 *</label>
              <select className="form-input" value={form.templateId}
                onChange={e => setForm(f => ({ ...f, templateId: e.target.value }))}>
                {modeTemplates.map(t => (
                  <option key={t.id} value={t.id}>{t.name}（{safeParseNodes(t.nodesJson).length} 节点）</option>
                ))}
              </select>
            </div>
          </div>
          <div className="form-group">
            <label className="form-label">工作名称（可选，默认"接入 供应商名"）</label>
            <input className="form-input" value={form.name} placeholder="如：接入 Dorak（Q3）"
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          </div>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
            <button className="btn" onClick={() => setStep(1)}>← 上一步</button>
            <button className="btn btn-primary" onClick={submit} disabled={saving}>{saving ? '创建中…' : '🎮 创建存档并开始'}</button>
          </div>
        </div>
      )}
    </div></DraftPage>
  )
}

function safeParseNodes(json) {
  try { const a = JSON.parse(json || '[]'); return Array.isArray(a) ? a : [] } catch { return [] }
}

// ===== 游戏视图 =====

function GameView({ jobId, onBack, showToast }) {
  const [managed, setManaged] = useState(false)
  const occupied = useOccupied()
  const [job, setJob] = useState(null)
  const [selected, setSelected] = useState(null)     // nodeId
  const [instances, setInstances] = useState([])
  const [instruction, setInstruction] = useState('')
  const [steerAnswer, setSteerAnswer] = useState('')   // R7 卡点答复输入
  const [showDetail, setShowDetail] = useState(false)  // 📋 任务详情弹窗
  const [detailDraft, setDetailDraft] = useState(null) // {name,supplierName,supplierCode,libraryPath,skillsText}
  const [editingArtifact, setEditingArtifact] = useState(false)
  const [artifactDraft, setArtifactDraft] = useState('')
  const pollRef = useRef(null)

  const load = () => fetch(`/api/supplier-jobs/${jobId}`).then(r => r.ok ? r.json() : null).then(d => {
    if (!d) return
    setJob(d)
    setSelected(prev => prev || d.currentNodeId || (d.nodes[0] && d.nodes[0].nodeId))
  }).catch(() => {})

  useEffect(() => {
    load()
    fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {})
    pollRef.current = setInterval(load, 4000)   // 轮询执行状态
    return () => clearInterval(pollRef.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobId])

  if (!job) return <div className="card">加载中… <button className="btn" onClick={onBack}>返回</button></div>

  const nodes = job.nodes || []
  const doneCount = nodes.filter(n => n.status === 'DONE').length
  const cur = nodes.find(n => n.nodeId === (selected || job.currentNodeId)) || nodes[0]
  const running = nodes.some(n => n.status === 'RUNNING')

  async function runNode(nodeId) {
    if (managed) return showToast('请在持续工作流的待办中处理此节点', 'error')
    const res = await fetch(`/api/supplier-jobs/${jobId}/nodes/${nodeId}/run`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ instruction }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(data.error || '执行失败', 'error')
    setInstruction('')
    showToast('▶ 已交给实例执行，完成后工件会出现在下方（页面自动刷新状态）', 'success')
    load()
  }
  async function answerNode(nodeId) {
    if (managed) return showToast('请在持续工作流的待办中处理此节点', 'error')
    if (!steerAnswer.trim()) return
    const res = await fetch(`/api/supplier-jobs/${jobId}/nodes/${nodeId}/answer`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answer: steerAnswer }),
    })
    const data = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(data.error || '答复发送失败', 'error')
    setSteerAnswer('')
    showToast('已答复，实例将继续执行', 'success')
    load()
  }
  async function completeNode(nodeId) {
    if (managed) return showToast('请在持续工作流的待办中处理此节点', 'error')
    await fetch(`/api/supplier-jobs/${jobId}/nodes/${nodeId}/complete`, { method: 'POST' })
    showToast('✅ 节点完成', 'success')
    setSelected(null)   // 自动跳到下一个推荐节点
    load()
  }
  async function saveArtifact(nodeId) {
    if (managed) return showToast('请在持续工作流的待办中处理此节点', 'error')
    await fetch(`/api/supplier-jobs/${jobId}/nodes/${nodeId}/artifact`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ artifact: artifactDraft }),
    })
    setEditingArtifact(false)
    showToast('工件已保存', 'success')
    load()
  }
  async function switchInstance(id) {
    await fetch(`/api/supplier-jobs/${jobId}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ instanceId: id ? Number(id) : null }),
    })
    load()
  }
  function openDetail() {
    setDetailDraft({
      name: job.name, supplierName: job.supplierName || '', supplierCode: job.supplierCode || '',
      libraryPath: job.libraryPath || '',
      skillsText: safeParseNodes(job.skillsJson).map(s => (typeof s === 'string' ? s : (s?.value || s?.path || ''))).filter(Boolean).join('\n'),
    })
    setShowDetail(true)
  }
  async function saveDetail() {
    const skills = detailDraft.skillsText.split('\n').map(x => x.trim()).filter(Boolean)
    const res = await fetch(`/api/supplier-jobs/${jobId}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: detailDraft.name, supplierName: detailDraft.supplierName,
        supplierCode: detailDraft.supplierCode, libraryPath: detailDraft.libraryPath,
        skills,
      }),
    })
    const d = await res.json().catch(() => ({}))
    if (!res.ok || d.error) return showToast(d.error || '保存失败', 'error')
    setShowDetail(false); showToast('任务信息已更新', 'success'); load()
  }

  return (
    <div>
      {/* 头部 */}
      <div className="card" style={{ padding: '12px 18px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <button className="btn" style={{ fontSize: 12, padding: '4px 12px' }} onClick={onBack}>← 工作台</button>
          <span style={{ fontSize: 16, fontWeight: 700 }}>{job.status === 'COMPLETED' ? '🏆' : '🎮'} {job.name}</span>
          {job.createdByEmployeeId && <CreatorBadge id={job.createdByEmployeeId} name={job.createdByEmployeeName} />}
          <span className="hook-badge">{job.mode === 'API' ? 'API 供应商' : 'CM 供应商'}</span>
          {job.status === 'COMPLETED' && <span className="hook-badge" style={{ background: 'rgba(22,163,74,.1)', color: '#16a34a', borderColor: 'rgba(22,163,74,.3)' }}>已通关 🎉</span>}
          <button className="btn" style={{ fontSize: 11, padding: '2px 10px' }} onClick={openDetail} data-tooltip="查看/修改任务详细信息" aria-label="查看/修改任务详细信息">📋 详情</button>
          <div style={{ flex: 1 }} />
          <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>进度 {doneCount}/{nodes.length}</span>
          <div className="pass-bar" style={{ maxWidth: 140 }}><div className="pass-fill" style={{ width: `${nodes.length ? doneCount / nodes.length * 100 : 0}%` }} /></div>
          <label style={{ fontSize: 12, color: 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: 6 }}>
            🤖 执行实例
            <select className="form-input" style={{ width: 170, padding: '4px 8px', fontSize: 12 }}
              disabled={managed} value={job.instanceId || ''} onChange={e => switchInstance(e.target.value)}>
              <option value="">（未绑定）</option>
              {instances.filter(i => i.enabled).map(i => {
                const occ = occupied[String(i.id)]
                const isCurrent = String(job.instanceId || '') === String(i.id)
                const byEmp = job.createdByEmployeeId != null
                // 员工创建的工作只能用该员工绑定的实例；admin 的不能用被员工独占的实例
                const allowed = byEmp ? !!(occ && occ.empId === job.createdByEmployeeId) : !occ
                const forbidden = !allowed && !isCurrent
                return (
                  <option key={i.id} value={i.id} disabled={forbidden}>
                    {forbidden
                      ? `🔒 ${i.name || `#${i.id}`}${byEmp ? '（非该员工绑定实例）' : `（已被员工 ${occ.label} 独占绑定）`}`
                      : (i.name || `#${i.id}`)}
                  </option>
                )
              })}
            </select>
          </label>
        </div>
        {/* 存档信息行 */}
        <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 8, fontFamily: 'var(--font-mono)' }}>
          供应商: {job.supplierName}{job.supplierCode ? ` (${job.supplierCode})` : ''} · 类库: {job.libraryPath || '未填'} · 文档 {safeParseNodes(job.docsJson).length} 份 · 🧠 Skill {safeParseNodes(job.skillsJson).length} 个
        </div>
        {/* 游戏提醒 */}
        {job.currentNodeId && (
          <div className="game-hint">
            💡 <b>当前目标</b>：{nodes.find(n => n.nodeId === job.currentNodeId)?.title} —— {nodes.find(n => n.nodeId === job.currentNodeId)?.description?.split('。')[0]}。
            {job.currentNodeId !== selected && <a style={{ cursor: 'pointer', color: 'var(--accent)', marginLeft: 6 }} onClick={() => setSelected(job.currentNodeId)}>带我去 →</a>}
          </div>
        )}
      </div>

      <SupplierWorkflowPanel job={job} showToast={showToast} onManaged={setManaged} />
      <div className="supplier-workspace-grid" style={{ display: 'flex', gap: 14, alignItems: 'flex-start' }}>
        {/* 左：节点操作卡 */}
        <div className="card" style={{ flex: 1, minWidth: 0, padding: '16px 20px' }}>
          {cur ? (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <span style={{ fontSize: 15, fontWeight: 700 }}>
                  {NODE_TYPE[cur.type]?.icon} {cur.title}
                </span>
                <span className="hook-badge" style={{ color: NODE_TYPE[cur.type]?.color, background: NODE_TYPE[cur.type]?.color + '14', borderColor: NODE_TYPE[cur.type]?.color + '40' }}>
                  {NODE_TYPE[cur.type]?.label}
                </span>
                <span className="hook-badge" style={{ color: NODE_STATUS[cur.status]?.color, background: NODE_STATUS[cur.status]?.color + '14', borderColor: NODE_STATUS[cur.status]?.color + '40' }}>
                  {cur.status === 'RUNNING' && <span className="spinner" style={{ width: 9, height: 9, marginRight: 4 }} />}
                  {cur.status === 'RUNNING' && cur.pendingQuestion ? '卡点待答' : NODE_STATUS[cur.status]?.label}
                </span>
                <div style={{ flex: 1 }} />
                {cur.status !== 'DONE' && (
                  <button className="btn btn-primary" style={{ fontSize: 12, padding: '5px 14px' }}
                    onClick={() => completeNode(cur.nodeId)}>✓ 我已完成此节点</button>
                )}
              </div>

              {/* 执行进度：转圈不再是黑盒，实时显示当前工具动作 */}
              {cur.status === 'RUNNING' && (
                <div style={{ marginTop: 10, fontSize: 12, color: cur.pendingQuestion ? '#b45309' : '#3b82f6', background: cur.pendingQuestion ? 'rgba(245,158,11,.08)' : 'rgba(59,130,246,.07)', border: '1px solid ' + (cur.pendingQuestion ? 'rgba(245,158,11,.3)' : 'rgba(59,130,246,.25)'), borderRadius: 6, padding: '5px 10px', wordBreak: 'break-all' }}>
                  {cur.pendingQuestion
                    ? <>⏸ 已挂起等你答复（停在最后一步：{cur.progress || '思考中'}）</>
                    : <><span className="spinner" style={{ width: 9, height: 9, marginRight: 6 }} />正在：{cur.progress || '思考中（首个工具动作到达前略等）…'}</>}
                  {cur.thinking && (
                    <div style={{ marginTop: 4, fontSize: 11.5, color: '#7c3aed', maxHeight: 72, overflowY: 'auto', wordBreak: 'break-all' }} data-tooltip="实例当前思考内容（估算还要在忙什么）" tabIndex={0}>
                      💭 {cur.thinking}
                    </div>
                  )}
                </div>
              )}

              <div style={{ fontSize: 13, color: 'var(--text-dim)', marginTop: 10, lineHeight: 1.8, whiteSpace: 'pre-wrap' }}>
                {cur.description}
              </div>

              {/* agent 节点：执行控制（devChat 节点改为无限轮对话重写提示词） */}
              {(cur.type === 'agent' || cur.type === 'agent_check') && cur.devChat && (
                <DevChatPanel jobId={job.id} nodeId={cur.nodeId} disabled={!job.instanceId} showToast={showToast} />
              )}
              {(cur.type === 'agent' || cur.type === 'agent_check') && !cur.devChat && (
                <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px dashed var(--border,#e5e7eb)' }}>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <textarea className="form-input" rows={3} style={{ flex: 1, padding: '7px 10px', fontSize: 12.5, resize: 'vertical', fontFamily: 'inherit' }}
                      placeholder={cur.artifact ? '纠正/补充指示（如：验价接口映射错了，应该是 xxx…），Enter 执行，Shift+Enter 换行' : '补充指示（可选），Shift+Enter 换行'}
                      value={instruction} onChange={e => setInstruction(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); runNode(cur.nodeId) } }} />
                    <button className="btn btn-primary" style={{ fontSize: 12.5, padding: '6px 16px', flexShrink: 0 }}
                      disabled={cur.status === 'RUNNING' || !job.instanceId}
                      onClick={() => runNode(cur.nodeId)}>
                      {cur.status === 'RUNNING' ? '执行中…' : (cur.artifact ? '🔄 带指示重跑' : '▶ 让实例执行')}
                    </button>
                  </div>
                  {cur.pendingQuestion && (
                    <div style={{ marginTop: 10, padding: '10px 12px', border: '1px solid #f59e0b', background: 'rgba(245,158,11,.08)', borderRadius: 8 }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: '#b45309', marginBottom: 6 }}>❓ 实例遇到卡点，答复后将注入原会话继续执行</div>
                      <div className="md-body" style={{ fontSize: 12.5, maxHeight: 220, overflowY: 'auto' }}>
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{cur.pendingQuestion}</ReactMarkdown>
                      </div>
                      <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
                        <textarea className="form-input" rows={2} style={{ flex: 1, padding: '7px 10px', fontSize: 12.5, resize: 'vertical', fontFamily: 'inherit' }}
                          placeholder="回答卡点（凭证 / 决定 / 材料说明…），Enter 发送，Shift+Enter 换行"
                          value={steerAnswer} onChange={e => setSteerAnswer(e.target.value)}
                          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); answerNode(cur.nodeId) } }} />
                        <button className="btn btn-primary" style={{ fontSize: 12.5, padding: '6px 16px', flexShrink: 0 }}
                          disabled={!steerAnswer.trim()}
                          onClick={() => answerNode(cur.nodeId)}>📤 发送答复</button>
                      </div>
                    </div>
                  )}
                  {!job.instanceId && <div style={{ fontSize: 12, color: 'var(--error)', marginTop: 6 }}>⚠️ 还没绑定执行实例，先在右上角选择</div>}
                  {cur.note && <div style={{ fontSize: 12, color: 'var(--error)', marginTop: 8 }}>⚠️ {cur.note}</div>}
                </div>
              )}

              {/* 写代码准备门禁（10-29-codegen-prep）：会议文档 → KV 配置 → redis 提醒 → 生成提示词 */}
              {(cur.codegenPrep || cur.contextStage === 'codegen') && <CodegenPrepCard job={job} reload={load} showToast={showToast} />}

              {/* 工件区 */}
              {cur.deliveryManifest && <details style={{ marginTop: 12 }}><summary>本次上下文交付记录</summary><pre style={{ whiteSpace: 'pre-wrap', fontSize: 11 }}>{cur.deliveryManifest}</pre></details>}
              {cur.toolEvidence && <details style={{ marginTop: 12 }}><summary>Postman 请求与返回证据</summary><pre style={{ whiteSpace: 'pre-wrap', fontSize: 11 }}>{cur.toolEvidence}</pre></details>}
              {(cur.artifact || editingArtifact) && (
                <div style={{ marginTop: 14 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                    <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-dim)' }}>📄 工件（{cur.lastRunAt && cur.lastRunAt !== 'null' ? '生成于 ' + String(cur.lastRunAt).replace('T', ' ').substring(5, 16) : '手动编辑'}）</span>
                    <div style={{ flex: 1 }} />
                    {editingArtifact ? (
                      <>
                        <button className="btn" style={{ fontSize: 12, padding: '3px 10px' }} onClick={() => setEditingArtifact(false)}>取消</button>
                        <button className="btn btn-primary" style={{ fontSize: 12, padding: '3px 10px' }} onClick={() => saveArtifact(cur.nodeId)}>保存</button>
                      </>
                    ) : (
                      <button className="btn" style={{ fontSize: 12, padding: '3px 10px' }}
                        onClick={() => { setArtifactDraft(cur.artifact || ''); setEditingArtifact(true) }}>✏️ 编辑纠正</button>
                    )}
                  </div>
                  {editingArtifact ? (
                    <textarea className="form-input" rows={18} style={{ fontFamily: 'var(--font-mono)', fontSize: 12, resize: 'vertical' }}
                      value={artifactDraft} onChange={e => setArtifactDraft(e.target.value)} />
                  ) : (
                    <div className="artifact-box md-body"><ReactMarkdown remarkPlugins={[remarkGfm]}>{cur.artifact}</ReactMarkdown></div>
                  )}
                </div>
              )}
            </>
          ) : <div style={{ color: 'var(--text-dim)' }}>该存档还没有节点（模板为空？）</div>}
        </div>

        {/* 右：工作流竖排节点线 */}
        <div className="card workflow-rail">
          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-dim)', marginBottom: 10 }}>🗺 游戏路径</div>
          {(() => {
            const activeId = nodes.find(n => n.status === 'RUNNING')?.nodeId || job.currentNodeId
            const seqOf = new Map(nodes.map((n, idx) => [n.nodeId, idx]))
            const railNodes = [...nodes.filter(n => n.nodeId === activeId), ...nodes.filter(n => n.nodeId !== activeId)]
            return railNodes.map((n, i) => {
            const st = NODE_STATUS[n.status] || NODE_STATUS.PENDING
            const isCur = n.nodeId === job.currentNodeId
            const isSel = cur && n.nodeId === cur.nodeId
            return (
              <div key={n.nodeId} className="wf-row">
                <div className="wf-line-col">
                  <span className="wf-dot"
                    style={{ background: n.status === 'DONE' ? '#16a34a' : (isCur ? '#3b82f6' : '#93c5fd'),
                             boxShadow: isCur && n.status !== 'DONE' ? '0 0 0 3px rgba(59,130,246,.22)' : undefined }}>
                    {n.status === 'DONE' ? '✓' : n.status === 'RUNNING' ? '' : (seqOf.get(n.nodeId) + 1)}
                    {n.status === 'RUNNING' && <span className="spinner" style={{ width: 9, height: 9, borderWidth: 1.5 }} />}
                  </span>
                  {i < railNodes.length - 1 && <span className="wf-line" />}
                </div>
                <div className={'wf-node' + (isSel ? ' sel' : '')} onClick={() => { setSelected(n.nodeId); setEditingArtifact(false) }}>
                  <div style={{ fontSize: 12.5, fontWeight: isSel ? 700 : 500, color: n.status === 'DONE' ? '#16a34a' : 'var(--text)' }}>
                    {NODE_TYPE[n.type]?.icon} {n.title}
                  </div>
                  <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 2 }}>
                    {NODE_TYPE[n.type]?.label} · <span style={{ color: st.color }}>{st.label}</span>
                    {n.pendingQuestion ? ' · ❓待答' : ''}
                    {n.codegenPrep ? ' · 🧾准备' : ''}
                    {n.artifact ? ' · 📄有工件' : ''}
                  </div>
                  {n.status === 'RUNNING' && n.progress && (
                    <div style={{ fontSize: 11, color: '#3b82f6', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-tooltip={n.progress} tabIndex={0}>
                      ⏳ {n.progress}
                    </div>
                  )}
                </div>
              </div>
            )
          })
        })()}
          {running && <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 10 }}>🔄 实例执行中，状态每 4 秒自动刷新…</div>}
        </div>
      </div>

      {/* 任务详情弹窗：创建后仍可回看/修改全部录入信息 */}
      {showDetail && detailDraft && (
        <Surface kind="dialog" onClose={() => setShowDetail(false)} form>
          <div className="modal-box" style={{ maxWidth: 680, width: 'min(680px,94vw)' }} onClick={ev => ev.stopPropagation()}>
            <div className="modal-title">📋 任务详情 · {job.name}</div>
            <div className="modal-body">
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <div className="form-group"><label className="form-label">任务名称</label>
                  <input className="form-input" value={detailDraft.name} onChange={e => setDetailDraft(d => ({ ...d, name: e.target.value }))} /></div>
                <div className="form-group"><label className="form-label">供应商名称</label>
                  <input className="form-input" value={detailDraft.supplierName} onChange={e => setDetailDraft(d => ({ ...d, supplierName: e.target.value }))} /></div>
                <div className="form-group"><label className="form-label">供应商 ID（supplierCode）</label>
                  <input className="form-input" value={detailDraft.supplierCode} onChange={e => setDetailDraft(d => ({ ...d, supplierCode: e.target.value }))} />
                  <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 4 }}>⚠️ 修改会切换知识库归属（旧库保留在原 code 下）</div></div>
                <div className="form-group"><label className="form-label">类库路径</label>
                  <input className="form-input" style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }} value={detailDraft.libraryPath} onChange={e => setDetailDraft(d => ({ ...d, libraryPath: e.target.value }))} /></div>
              </div>
              <div className="form-group"><label className="form-label">🧠 Skill 目录（每行一个路径；执行时自动注入实例）</label>
                <textarea className="form-input" rows={4} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }} value={detailDraft.skillsText}
                  onChange={e => setDetailDraft(d => ({ ...d, skillsText: e.target.value }))} /></div>
              <div className="form-group"><label className="form-label">接口文档</label>
                <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>已统一由下方「供应商知识库」管理（补充/删除/重解析都在知识库面板操作）</div></div>
              <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 6, fontFamily: 'var(--font-mono)' }}>
                模式 {job.mode} · 知识库 {job.kbCode} · 创建 {String(job.createdAt).replace('T', ' ').substring(0, 16)} · 进度 {doneCount}/{nodes.length}
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
              <button className="btn" onClick={() => setShowDetail(false)}>取消</button>
              <button className="btn btn-primary" onClick={saveDetail}>💾 保存</button>
            </div>
          </div>
        </Surface>
      )}

      {/* R3 统一补充入口：供应商知识库（URL/PDF/聊天记录/笔记 随时补充） */}
      <SupplierWikiPanel job={job} showToast={showToast} />
      <KbPanel kbCode={job.kbCode} showToast={showToast} />

      {/* 对话区：执行过程中随时问实例"现在什么情况" */}
      <ChatPanel jobId={job.id} running={running} showToast={showToast} />
    </div>
  )
}

// ===== 写代码准备卡片（10-29-codegen-prep）：会议文档门禁 → KV 配置 → redis 提醒 → 生成提示词一键复制 =====

function CodegenPrepCard({ job, reload, showToast }) {
  const parseConfigs = () => { try { const a = JSON.parse(job.configJson || '[]'); return Array.isArray(a) ? a : [] } catch { return [] } }
  const [meeting, setMeeting] = useState(job.meetingDocUrl || '')
  const [spec, setSpec] = useState(job.specDocPath || '')
  const [promptSkill, setPromptSkill] = useState(job.promptSkillPath || '')
  const [configs, setConfigs] = useState(parseConfigs())
  const [prompt, setPrompt] = useState(null)   // 生成结果文本
  const [checks, setChecks] = useState(null)   // R3 自检结果
  const [busy, setBusy] = useState(false)
  const meetingDone = !!(job.meetingDocUrl || '').trim()
  const kvDone = parseConfigs().some(c => c.key && c.value)
  const ready = !!job.prepReady

  async function put(body) {
    setBusy(true)
    const res = await fetch(`/api/supplier-jobs/${job.id}/prep`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    })
    const d = await res.json().catch(() => ({}))
    setBusy(false)
    if (!res.ok) { showToast(d.error || '保存失败', 'error'); return false }
    showToast('已保存', 'success'); reload()
    return true
  }
  async function genPrompt() {
    setBusy(true)
    const res = await fetch(`/api/supplier-jobs/${job.id}/codegen-prompt/generate`, { method: 'POST' })
    const d = await res.json().catch(() => ({}))
    setBusy(false)
    if (!res.ok) return showToast(d.error || '生成失败', 'error')
    setPrompt(d.prompt)
  }
  async function copy() {
    try { await navigator.clipboard.writeText(prompt); showToast('提示词已复制 — 去飞书粘贴发给实例吧', 'success') }
    catch { showToast('复制失败，请手动全选文本框内容', 'error') }
  }
  async function runCheck() {
    setBusy(true)
    const res = await fetch(`/api/supplier-jobs/${job.id}/codegen-check`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ prompt }),
    })
    const d = await res.json().catch(() => ({}))
    setBusy(false)
    if (!res.ok) return showToast(d.error || '检查失败', 'error')
    setChecks(d.checks || [])
  }
  async function applyFix() {
    setBusy(true)
    const res = await fetch(`/api/supplier-jobs/${job.id}/codegen-fix`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ prompt }),
    })
    const d = await res.json().catch(() => ({}))
    setBusy(false)
    if (!res.ok) return showToast(d.error || '修正失败', 'error')
    setPrompt(d.prompt)
    setChecks(null)
    showToast('缺失要点已补写进提示词', 'success')
  }

  return (
    <div style={{ marginTop: 14, border: '1px solid var(--border,#e5e7eb)', borderRadius: 10, padding: '12px 16px', background: 'rgba(59,130,246,.03)' }}>
      <div style={{ fontSize: 13, fontWeight: 700 }}>🧾 写代码准备 <span style={{ fontWeight: 400, fontSize: 11, color: 'var(--text-dim)' }}>（完成后才能勾选本节点，提示词聚合全部参考料）</span></div>

      {/* 1) 三方会议文档 */}
      <div style={{ marginTop: 10, fontSize: 12 }}>
        <div style={{ fontWeight: 600, marginBottom: 4 }}>{meetingDone ? '✅' : '☐'} 1. 三方会议文档（飞书云文档链接，必填，写代码的重要参考）</div>
        <div style={{ display: 'flex', gap: 8 }}>
          <input className="form-input" style={{ flex: 1, fontSize: 12 }} placeholder="https://xxx.feishu.cn/docx/…（仅支持 feishu.cn / larksuite.com）"
            value={meeting} onChange={e => setMeeting(e.target.value)} />
          <button className="btn" style={{ fontSize: 12, padding: '4px 14px' }} disabled={busy || !meeting.trim()} onClick={() => put({ meetingDocUrl: meeting })}>提交</button>
        </div>
      </div>

      {/* 2) spec（可选）+ KV 配置（必填，会议文档提交后才开放） */}
      <div style={{ marginTop: 12, fontSize: 12 }}>
        <div style={{ fontWeight: 600, marginBottom: 4 }}>{kvDone ? '✅' : '☐'} 2. 当前配置 key/value（必填 ≥1 组；会议文档提交后开放）</div>
        {!meetingDone && <div style={{ fontSize: 11, color: 'var(--text-dim)', marginBottom: 6 }}>⬆️ 先提交三方会议文档，再补充配置</div>}
        <div style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
          <input className="form-input" style={{ flex: 1, fontSize: 12 }} placeholder="spec 文档绝对路径或已解析入库的链接（生成提示词必填）"
            value={spec} onChange={e => setSpec(e.target.value)} />
          <button className="btn" style={{ fontSize: 12, padding: '4px 14px' }} disabled={busy} onClick={() => put({ specDocPath: spec })}>存 spec</button>
        </div>
        <div style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
          <input className="form-input" value={promptSkill} onChange={e => setPromptSkill(e.target.value)} placeholder="公司提示词生成 Skill 绝对路径（目录或 SKILL.md）" />
          <button className="btn" disabled={busy} onClick={() => put({ promptSkillPath: promptSkill })}>存提示词 Skill</button>
        </div>
        <div disabled={!meetingDone} style={{ opacity: meetingDone ? 1 : .45, pointerEvents: meetingDone ? 'auto' : 'none' }}>
          {configs.map((c, i) => (
            <div key={i} style={{ display: 'flex', gap: 6, marginBottom: 6 }}>
              <input className="form-input" style={{ flex: '0 0 40%', fontSize: 12 }} placeholder="配置 key（如 redis.switch / account.xxx）"
                value={c.key || ''} onChange={e => setConfigs(a => a.map((x, xi) => xi === i ? { ...x, key: e.target.value } : x))} />
              <input className="form-input" style={{ flex: 1, fontSize: 12 }} placeholder="value"
                value={c.value || ''} onChange={e => setConfigs(a => a.map((x, xi) => xi === i ? { ...x, value: e.target.value } : x))} />
              <button className="btn" style={{ fontSize: 11, padding: '2px 8px', color: 'var(--error)' }}
                onClick={() => setConfigs(a => a.filter((_, xi) => xi !== i))}>✕</button>
            </div>
          ))}
          <div style={{ display: 'flex', gap: 8 }}>
            <button className="btn" style={{ fontSize: 11, padding: '3px 10px' }} onClick={() => setConfigs(a => [...a, { key: '', value: '' }])}>+ 加一行</button>
            <button className="btn btn-primary" style={{ fontSize: 11, padding: '3px 12px' }} disabled={busy}
              onClick={() => put({ configs: configs.filter(c => c.key.trim() || c.value.trim()) })}>💾 保存配置</button>
          </div>
        </div>
      </div>

      {/* 3) redis 提醒 */}
      {kvDone && (
        <div style={{ marginTop: 10, fontSize: 12, color: '#b45309', background: 'rgba(245,158,11,.08)', border: '1px solid rgba(245,158,11,.3)', borderRadius: 6, padding: '6px 10px' }}>
          📣 配置已录入。如果实现用到 <b>redis</b>（缓存 key / 开关），记得去<b>配置中心</b>完成配置，代码才能跑通。
        </div>
      )}

      {/* 4) 生成提示词 */}
      <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 10 }}>
        <button className="btn btn-primary" style={{ fontSize: 12.5, padding: '6px 16px' }} disabled={busy || !ready} onClick={genPrompt}>
          📝 用公司 Skill 生成提示词
        </button>
        {!ready && <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>请保存会议文档、配置、spec、提示词 Skill 和对接 Skill；生成时还会检查实测证据与 Trellis</span>}
      </div>
      {prompt && (
        <div style={{ marginTop: 10 }}>
          <div style={{ display: 'flex', gap: 8, marginBottom: 6, alignItems: 'center', flexWrap: 'wrap' }}>
            <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-dim)' }}>发给实例的提示词（可直接编辑后复制）</span>
            <div style={{ flex: 1 }} />
            <button className="btn" style={{ fontSize: 11, padding: '3px 12px' }} disabled={busy} onClick={runCheck}>🧪 自动检查要点</button>
            <button className="btn" style={{ fontSize: 11, padding: '3px 12px' }} onClick={() => setPrompt(null)}>收起</button>
            <button className="btn btn-primary" style={{ fontSize: 11, padding: '3px 12px' }} onClick={copy}>📋 一键复制</button>
          </div>
          {checks && (
            <div style={{ marginBottom: 8, border: '1px solid var(--border,#e5e7eb)', borderRadius: 8, overflow: 'hidden' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12 }}>
                <tbody>
                  {checks.map((c, i) => (
                    <tr key={c.item} style={{ background: c.pass ? undefined : 'rgba(220,38,38,.05)', borderTop: i ? '1px solid var(--border,#eee)' : undefined }}>
                      <td style={{ width: 36, textAlign: 'center', color: c.pass ? '#16a34a' : '#dc2626', fontWeight: 700 }}>{c.pass ? '√' : '×'}</td>
                      <td style={{ padding: '5px 8px', whiteSpace: 'nowrap', fontWeight: 600 }}>{c.item}</td>
                      <td style={{ padding: '5px 8px', color: 'var(--text-dim)', fontSize: 11.5 }}>{c.pass ? '' : '缺失 → ' + c.hint}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {checks.some(c => !c.pass) && (
                <div style={{ padding: '6px 10px', borderTop: '1px solid var(--border,#eee)' }}>
                  <button className="btn btn-primary" style={{ fontSize: 11, padding: '3px 12px' }} disabled={busy} onClick={applyFix}>🔧 按检查结果修正提示词</button>
                </div>
              )}
            </div>
          )}
          <textarea className="form-input" rows={16} style={{ fontFamily: 'var(--font-mono)', fontSize: 12, resize: 'vertical' }}
            value={prompt} onChange={e => setPrompt(e.target.value)} />
        </div>
      )}
    </div>
  )
}

// ===== 供应商知识库面板（R3：随时补充 URL/PDF/聊天记录/笔记；解析状态可视；产物可人工校正） =====

const KB_TYPES = {
  url: { icon: '🔗', label: '文档链接', ph: 'https://docs.supplier.com/api（入库后点「解析」抓取转 MD）' },
  pdf: { icon: '📕', label: '本地 PDF', ph: 'D:\\docs\\supplier-api.pdf（入库后点「解析」提取转 MD）' },
  md: { icon: '📃', label: '本地 MD', ph: 'D:\\docs\\api.md（已是 Markdown，入库即产物，无需解析）' },
  chat: { icon: '💬', label: '聊天记录', ph: '粘贴与供应商对接人的沟通内容，可分多次补充…' },
  note: { icon: '📝', label: '笔记', ph: '随手记的要点、口头约定、会议结论…' },
}
const KB_STATUS = {
  RAW: { label: '未解析', color: '#6b7280' },
  PENDING: { label: '排队解析', color: '#6b7280' },
  PARSING: { label: '解析中', color: '#3b82f6' },
  DONE: { label: '已解析', color: '#16a34a' },
  FAILED: { label: '解析失败', color: '#dc2626' },
}

function KbPanel({ kbCode, showToast }) {
  const confirmAction = useConfirm()
  const [shared, setShared] = useState(true)
  useEffect(() => { let alive = true; fetch('/api/supplier-jobs').then(r => r.ok ? r.json() : []).then(rows => { if (alive && Array.isArray(rows)) setShared(rows.filter(job => job.supplierCode === kbCode || job.kbCode === kbCode).length > 1) }).catch(() => {}); return () => { alive = false } }, [kbCode])
  const [entries, setEntries] = useState([])
  const [type, setType] = useState('chat')
  const [value, setValue] = useState('')
  const [title, setTitle] = useState('')
  const [site, setSite] = useState(false)           // url 类型：同站点目录递归抓取(≤200页)
  const [pagesModal, setPagesModal] = useState(null) // 打开页面清单的 entry
  const [pview, setPview] = useState(null)           // 单页正文预览 {title, content}
  const [view, setView] = useState(null)   // {id, title, content, editing}

  const kbMeta = (e) => { try { return JSON.parse(e.metaJson || '{}') } catch { return {} } }

  const load = () => fetch(`/api/supplier-kb/${encodeURIComponent(kbCode || '')}/entries`)
    .then(r => r.ok ? r.json() : []).then(setEntries).catch(() => {})
  const busy = entries.some(e => e.status === 'PARSING' || e.status === 'PENDING')
  useEffect(() => {
    if (!kbCode) return
    load()
    const t = setInterval(load, busy ? 2000 : 5000)   // 抓取/解析进行中加密轮询，进度 2s 内可见
    return () => clearInterval(t)
  }, [kbCode, busy])

  async function add() {
    if (!value.trim()) return showToast('内容不能为空', 'error')
    const res = await fetch(`/api/supplier-kb/${encodeURIComponent(kbCode)}/entries`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type, value, title, site: type === 'url' && site }),
    })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '添加失败', 'error')
    setValue(''); setTitle('')
    showToast(type === 'chat' || type === 'note' || type === 'md' ? '已入库，下次节点执行即可引用' : '已入库（原文）。点该行右侧「解析」生成解析文档', 'success')
    load()
  }
  async function changeStage(id, stage) {
    const res = await fetch(`/api/supplier-kb/entries/${id}/stage`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ stage }) })
    if (!res.ok) return showToast('使用阶段保存失败', 'error')
    load()
  }
  async function openMd(e) {
    const r = await fetch(`/api/supplier-kb/entries/${e.id}/md`)
    const d = await r.json().catch(() => ({}))
    if (!r.ok) return showToast(d.error || '读取失败', 'error')
    setView({ id: e.id, title: e.title, content: d.content || '', originalContent: d.content || '', editing: false })
  }
  async function saveMd() {
    const res = await fetch(`/api/supplier-kb/entries/${view.id}/md`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: view.content }),
    })
    if (!res.ok) { const d = await res.json().catch(() => ({})); return showToast(d.error || '保存失败', 'error') }
    setView(null); showToast('产物已校正保存', 'success'); load()
  }
  async function reparse(e) {
    const res = await fetch(`/api/supplier-kb/entries/${e.id}/reparse`, { method: 'POST' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '操作失败', 'error')
    showToast('已重新排队解析', 'success'); load()
  }
  async function removeEntry(e) {
    if (!await confirmAction({ kind: shared || /"site"\s*:\s*true/.test(e.metaJson || '') ? 'dialog' : 'popconfirm', title: '删除知识库条目？', body: `删除「${e.title || '该条目'}」？${(e.type === 'url' || e.type === 'pdf') && e.source ? '（来源仍在创建时的文档清单里，但不会再被自动同步回来）' : ''}删除后无法恢复，关联的站点页面也会删除，使用这些材料的 Wiki 将过期。`, confirmLabel: '删除条目' })) return
    const res = await fetch(`/api/supplier-kb/entries/${e.id}`, { method: 'DELETE' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '删除失败', 'error')
    showToast('已删除', 'success'); load()
  }

  return (
    <div className="card" style={{ marginTop: 14, padding: '14px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 14, fontWeight: 700 }}>📚 供应商知识库</span>
        <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>统一补充入口：文档随时进，解析一次永久复用；节点执行自动引用（先查库勿重抓）</span>
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 11, color: 'var(--text-dim)', fontFamily: 'var(--font-mono)' }}>{kbCode}</span>
      </div>

      {/* 补充表单 */}
      <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
        <select className="form-input" style={{ width: 120, fontSize: 12 }} value={type} onChange={e => setType(e.target.value)}>
          {Object.entries(KB_TYPES).map(([k, v]) => <option key={k} value={k}>{v.icon} {v.label}</option>)}
        </select>
        {type === 'chat' && <input className="form-input" style={{ width: 150, fontSize: 12 }} placeholder="标题(可选)" value={title} onChange={e => setTitle(e.target.value)} />}
        <textarea className="form-input" rows={type === 'chat' ? 3 : 1} style={{ flex: 1, minWidth: 260, fontSize: 12.5 }}
          placeholder={KB_TYPES[type].ph} value={value} onChange={e => setValue(e.target.value)} />
        {type === 'url' && (
          <label style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 11.5, color: 'var(--text-dim)', alignSelf: 'center', cursor: 'pointer' }}
            data-tooltip="从入口页发现链接，按范围逐页抓取：严格同源 + 入口第一级路径（如 /docs/），最多发现 200 页；第 201 页发现即报错终止" tabIndex={0}>
            <input type="checkbox" checked={site} onChange={ev => setSite(ev.target.checked)} />🕸 导入同站点文档目录（最多 200 页）
          </label>
        )}
        <button className="btn btn-primary" style={{ fontSize: 12.5, padding: '6px 16px', alignSelf: 'flex-start' }}
          onClick={add}>＋ 补充入库</button>
      </div>

      {/* 条目列表 */}
      <div style={{ marginTop: 12 }}>
        {entries.length === 0 && <div style={{ fontSize: 12, color: 'var(--text-dim)', padding: '8px 0' }}>（知识库还是空的 — 上方随时补充；创建时录入的旧文档已自动并入）</div>}
        {entries.map(e => {
          const st = KB_STATUS[e.status] || { label: e.status, color: '#6b7280' }
          const m = kbMeta(e)
          const isSite = e.type === 'url' && m.site
          return (
            <div key={e.id} className="anim-in" style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 0', borderTop: '1px solid var(--border,#eee)', flexWrap: 'wrap' }}>
              <span style={{ fontSize: 14 }}>{KB_TYPES[e.type]?.icon || '•'}</span>
              <span style={{ fontSize: 12.5, fontWeight: 600, maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-tooltip={e.title} tabIndex={0}>{e.title || '(未命名)'}</span>
              <span style={{ fontSize: 11, color: 'var(--text-dim)', fontFamily: 'var(--font-mono)', maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-tooltip={e.source} tabIndex={0}>{e.source}</span>
              {isSite && <span className="hook-badge dim" data-tooltip="同站点目录递归抓取（≤200页）" tabIndex={0}>🕸站点</span>}
              <span className="hook-badge" style={{ color: st.color, background: st.color + '14', borderColor: st.color + '40' }}>
                {(e.status === 'PARSING' || e.status === 'PENDING') && <span className="spinner" style={{ width: 8, height: 8, marginRight: 4, borderWidth: 1.5 }} />}
                {st.label}
              </span>
              {isSite && (e.status === 'PARSING' || e.status === 'DONE' || e.status === 'FAILED') && m.found != null && (
                <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>
                  已发现 {m.found} · 已载入 {m.loaded || 0} · 失败 {m.failed || 0} · 未抓取 {m.pending || 0}
                </span>
              )}
              {isSite && m.partial && <span className="hook-badge" style={{ color: '#b45309', background: 'rgba(245,158,11,.12)', borderColor: 'rgba(245,158,11,.4)' }}>⚠️ 部分导入</span>}
              {e.status === 'FAILED' && <span style={{ fontSize: 11, color: 'var(--error)' }} data-tooltip={e.error} tabIndex={0}>{String(e.error || '').slice(0, 40)}</span>}
              <select aria-label="材料使用阶段" className="form-input" style={{ width: 130, fontSize: 11 }} value={e.contextStage || 'all'} onChange={ev => changeStage(e.id, ev.target.value)}>
                <option value="all">通用 · 每次交付</option><option value="analysis">文档分析</option><option value="probe">接口实测</option><option value="codegen">代码开发</option><option value="archive">仅留档</option>
              </select>
              <div style={{ flex: 1 }} />
              {e.status === 'DONE' && <button className="btn" style={{ fontSize: 11, padding: '2px 10px' }} onClick={() => openMd(e)}>👁 产物/校正</button>}
              {isSite && e.status !== 'RAW' && (
                <button className="btn" style={{ fontSize: 11, padding: '2px 10px' }} onClick={() => setPagesModal(e)}>📑 页面</button>
              )}
              {isSite && e.status === 'PARSING' && (
                <button className="btn" style={{ fontSize: 11, padding: '2px 10px', color: 'var(--error)' }}
                  onClick={async () => { await fetch(`/api/supplier-kb/entries/${e.id}/cancel`, { method: 'POST' }); showToast('已请求取消，当前页完成后停止', 'success') }}>✋ 取消</button>
              )}
              {(e.type === 'url' || e.type === 'pdf') && e.status === 'RAW' && (
                <button className="btn btn-primary" style={{ fontSize: 11, padding: '2px 10px' }} onClick={() => reparse(e)}>🔨 解析</button>
              )}
              {(e.type === 'url' || e.type === 'pdf') && (e.status === 'DONE' || e.status === 'FAILED') && (
                <button className="btn" style={{ fontSize: 11, padding: '2px 10px' }} onClick={() => reparse(e)}>🔄 重解析</button>
              )}
              <button className="btn" style={{ fontSize: 11, padding: '2px 8px', color: 'var(--error)', borderColor: 'rgba(220,38,38,.35)' }}
                data-tooltip="删除该条目" onClick={() => removeEntry(e)} aria-label="删除该条目">✕</button>
            </div>
          )
        })}
      </div>

      {/* 产物预览/编辑弹窗 */}
      {view && (
        <Surface kind="drawer" onClose={() => setView(null)} form>
          <div className="modal-box" style={{ maxWidth: 860, width: 'min(860px,94vw)' }} onClick={ev => ev.stopPropagation()}>
            <div className="modal-title">📄 {view.title}（解析产物 Markdown）</div>
            <div className="modal-body">
              {view.editing ? (
                <textarea className="form-input" rows={22} style={{ fontFamily: 'var(--font-mono)', fontSize: 12, resize: 'vertical' }}
                  value={view.content} onChange={ev => setView(v => ({ ...v, content: ev.target.value }))} />
              ) : (
                <div className="artifact-box md-body" style={{ maxHeight: 480, overflowY: 'auto' }}>
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{view.content}</ReactMarkdown>
                </div>
              )}
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
              <button className="btn" onClick={() => setView(null)}>关闭</button>
              {view.editing ? (
                <>
                  <button className="btn" onClick={() => setView(v => ({ ...v, content: v.originalContent, editing: false }))}>取消编辑</button>
                  <button className="btn btn-primary" onClick={saveMd}>💾 保存校正</button>
                </>
              ) : (
                <button className="btn" onClick={() => setView(v => ({ ...v, editing: true }))}>✏️ 编辑校正</button>
              )}
            </div>
          </div>
        </Surface>
      )}

      {pagesModal && (
        <PagesModal kbCode={kbCode} entry={pagesModal} onClose={() => setPagesModal(null)} showToast={showToast}
          onView={async (p) => {
            const r = await fetch(`/api/supplier-kb/pages/${p.id}/md`)
            const d = await r.json().catch(() => ({}))
            if (!r.ok) return showToast(d.error || '读取失败', 'error')
            setPview({ title: p.title || p.url, content: d.content || '' })
          }} />
      )}

      {pview && (
        <Surface kind="drawer" onClose={() => setPview(null)}>
          <div className="modal-box" style={{ maxWidth: 820, width: 'min(820px,94vw)' }} onClick={ev => ev.stopPropagation()}>
            <div className="modal-title">📄 {pview.title}（页面正文）</div>
            <div className="modal-body">
              <div className="artifact-box md-body" style={{ maxHeight: 480, overflowY: 'auto' }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{pview.content}</ReactMarkdown>
              </div>
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
              <button className="btn" onClick={() => setPview(null)}>关闭</button>
            </div>
          </div>
        </Surface>
      )}
    </div>
  )
}

// ===== 站点抓取页面清单弹窗（11-03-kb-site-crawl） =====

const PAGE_STATUS = {
  pending: { label: '未抓取', color: '#6b7280' },
  loaded: { label: '已载入', color: '#16a34a' },
  failed: { label: '失败', color: '#dc2626' },
}

function PagesModal({ kbCode, entry, onClose, onView, showToast }) {
  const [pages, setPages] = useState(null)
  const load = () => fetch(`/api/supplier-kb/${encodeURIComponent(kbCode)}/entries/${entry.id}/pages`)
    .then(r => r.ok ? r.json() : []).then(setPages).catch(() => setPages([]))
  useEffect(() => {
    load()
    const t = setInterval(load, entry.status === 'PARSING' ? 2000 : 6000)
    return () => clearInterval(t)
  }, [entry.id, entry.status])
  const m = (() => { try { return JSON.parse(entry.metaJson || '{}') } catch { return {} } })()
  return (
    <Surface kind="drawer" onClose={onClose}>
      <div className="modal-box" style={{ maxWidth: 920, width: 'min(920px,95vw)' }} onClick={ev => ev.stopPropagation()}>
        <div className="modal-title">📑 站点页面清单 · {entry.title}
          <span style={{ fontWeight: 400, fontSize: 12, color: 'var(--text-dim)', marginLeft: 8 }}>
            已发现 {m.found ?? pages?.length ?? 0} · 已载入 {m.loaded ?? '-'} · 失败 {m.failed ?? '-'} · 未抓取 {m.pending ?? '-'}（发现数是实际抓到的，不代表全站总量）
          </span>
        </div>
        {m.partial && <div style={{ margin: '0 0 8px', fontSize: 12, color: '#b45309' }}>⚠️ 部分导入：存在失败页面，正文索引已标注缺口</div>}
        <div className="modal-body" style={{ maxHeight: '58vh', overflowY: 'auto' }}>
          {!pages && <div style={{ color: 'var(--text-dim)', fontSize: 12 }}>加载中…</div>}
          {pages && pages.length === 0 && <div style={{ color: 'var(--text-dim)', fontSize: 12 }}>（历史抓取无逐页记录，无法还原——重解析后可见）</div>}
          {pages && pages.map(p => {
            const ps = PAGE_STATUS[p.status] || { label: p.status, color: '#6b7280' }
            return (
              <div key={p.id} style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '6px 0', borderTop: '1px solid var(--border,#eee)', flexWrap: 'wrap' }}>
                <span className="hook-badge" style={{ color: ps.color, background: ps.color + '14', borderColor: ps.color + '40', flexShrink: 0 }}>{ps.label}</span>
                <span style={{ fontSize: 12, fontWeight: 600, maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-tooltip={p.title} tabIndex={0}>{p.title || '(无标题)'}</span>
                <span style={{ fontSize: 11, color: 'var(--text-dim)', fontFamily: 'var(--font-mono)', flex: 1, minWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-tooltip={p.url} tabIndex={0}>{p.url}</span>
                {p.status === 'failed' && <span style={{ fontSize: 11, color: 'var(--error)', maxWidth: 260, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-tooltip={p.error} tabIndex={0}>{p.error}</span>}
                {p.status === 'loaded' && p.mdPath && <button className="btn" style={{ fontSize: 11, padding: '2px 10px', flexShrink: 0 }} onClick={() => onView(p)}>预览</button>}
              </div>
            )
          })}
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 12 }}>
          <button className="btn" onClick={onClose}>关闭</button>
        </div>
      </div>
    </Surface>
  )
}

// ===== 对话区：与执行实例随问随答（提问排队，当前执行段结束后被回答） =====

function ChatPanel({ jobId, running, showToast }) {
  const [msgs, setMsgs] = useState([])
  const [q, setQ] = useState('')
  const [sending, setSending] = useState(false)
  const bodyRef = useRef(null)

  const load = () => fetch(`/api/supplier-jobs/${jobId}/chat`).then(r => r.ok ? r.json() : []).then(setMsgs).catch(() => {})
  useEffect(() => {
    load()
    const hasPending = msgs.some(m => m.status === 'PENDING')
    const t = setInterval(load, hasPending || running ? 2500 : 8000)
    return () => clearInterval(t)
  }, [jobId, running, msgs.some(m => m.status === 'PENDING')])
  useEffect(() => {
    if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight
  }, [msgs.length, msgs[msgs.length - 1]?.text?.length])

  async function send() {
    if (!q.trim() || sending) return
    setSending(true)
    const res = await fetch(`/api/supplier-jobs/${jobId}/chat`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question: q }),
    })
    const d = await res.json().catch(() => ({}))
    setSending(false)
    if (!res.ok) return showToast(d.error || '发送失败', 'error')
    setQ('')
    load()
  }

  return (
    <div className="card" style={{ marginTop: 14, padding: '14px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span style={{ fontSize: 14, fontWeight: 700 }}>💬 对话区</span>
        <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>随时问实例"现在什么情况"——节点执行中会排队，当前执行段结束后回复（同会话上下文，知道所有细节）</span>
        {running && <span className="hook-badge" style={{ color: '#3b82f6' }}>执行中·提问将排队</span>}
      </div>
      <div ref={bodyRef} style={{ maxHeight: 320, overflowY: 'auto', marginTop: 10, display: 'flex', flexDirection: 'column', gap: 8 }}>
        {msgs.length === 0 && <div style={{ fontSize: 12, color: 'var(--text-dim)', padding: '12px 0', textAlign: 'center' }}>（还没有对话 — 问点什么，比如"现在进行到哪一步了？""刚才失败的原因是什么？"）</div>}
        {msgs.map(m => m.role === 'user' ? (
          <div key={m.id} style={{ alignSelf: 'flex-end', maxWidth: '78%', background: 'var(--accent,#2563eb)', color: '#fff', borderRadius: '10px 10px 2px 10px', padding: '7px 11px', fontSize: 12.5, whiteSpace: 'pre-wrap' }}>
            {m.text}
            <div style={{ fontSize: 10, opacity: .65, marginTop: 3 }}>{String(m.createdAt).substring(11, 16)}</div>
          </div>
        ) : (
          <div key={m.id} style={{ alignSelf: 'flex-start', maxWidth: '86%', background: 'rgba(107,114,128,.1)', border: '1px solid var(--border,#e5e7eb)', borderRadius: '10px 10px 10px 2px', padding: '7px 11px', fontSize: 12.5 }}>
            {m.status === 'PENDING' ? (
              <span style={{ color: 'var(--text-dim)' }}><span className="spinner" style={{ width: 9, height: 9, display: 'inline-block', marginRight: 6 }} />排队/思考中…{running ? '（节点正在执行，结束后自动回答）' : ''}</span>
            ) : (
              <div className="md-body" style={{ color: m.status === 'FAILED' ? 'var(--error)' : 'var(--text)' }}>
                <ReactMarkdown remarkPlugins={[remarkGfm]}>{m.text}</ReactMarkdown>
              </div>
            )}
            <div style={{ fontSize: 10, color: 'var(--text-dim)', marginTop: 3 }}>{String(m.createdAt).substring(11, 16)}</div>
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
        <input className="form-input" style={{ flex: 1 }} placeholder="问实例现在的情况、失败原因、某个结论的依据…（回车发送）"
          value={q} onChange={e => setQ(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') send() }} />
        <button className="btn btn-primary" style={{ flexShrink: 0 }} disabled={!q.trim() || sending} onClick={send}>
          {sending ? '发送中…' : '📤 提问'}
        </button>
      </div>
    </div>
  )
}

// ===== 开发期对话节点（三期 R4）：贴原始话 → 实例重写为可执行提示词，无限轮 =====

function DevChatPanel({ jobId, nodeId, disabled, showToast }) {
  const [msgs, setMsgs] = useState([])
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const [copiedId, setCopiedId] = useState(null)
  const bodyRef = useRef(null)

  const load = () => fetch(`/api/supplier-jobs/${jobId}/nodes/${nodeId}/devchat`)
    .then(r => r.ok ? r.json() : []).then(setMsgs).catch(() => {})
  const pending = msgs.some(m => m.status === 'PENDING')
  useEffect(() => {
    load()
    const t = setInterval(load, pending ? 2000 : 7000)
    return () => clearInterval(t)
  }, [jobId, nodeId, pending])
  useEffect(() => { if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight }, [msgs.length, msgs[msgs.length - 1]?.text?.length])

  async function send() {
    if (!text.trim() || sending) return
    setSending(true)
    const res = await fetch(`/api/supplier-jobs/${jobId}/nodes/${nodeId}/devchat`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ text }),
    })
    const d = await res.json().catch(() => ({}))
    setSending(false)
    if (!res.ok) return showToast(d.error || '发送失败', 'error')
    setText(''); load()
  }
  async function copyMsg(m) {
    try { await navigator.clipboard.writeText(m.text); setCopiedId(m.id); setTimeout(() => setCopiedId(null), 1500) }
    catch { showToast('复制失败', 'error') }
  }

  return (
    <div style={{ marginTop: 12, border: '1px dashed var(--border,#e5e7eb)', borderRadius: 10, padding: 12 }}>
      <div style={{ fontSize: 12.5, fontWeight: 700, marginBottom: 8 }}>🗣 开发期沟通 → 提示词重写
        <span style={{ fontWeight: 400, fontSize: 11, color: 'var(--text-dim)', marginLeft: 8 }}>贴入与供应商沟通的原始结论/要调整的点，结合知识库与历史重写成可执行提示词（可无限轮）</span>
      </div>
      <div ref={bodyRef} style={{ maxHeight: 320, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {msgs.length === 0 && <div style={{ fontSize: 12, color: 'var(--text-dim)', textAlign: 'center', padding: '10px 0' }}>（还没有沟通 — 例如贴"供应商刚说查价接口批量上限是 20 不是 50，报价缓存要 5 分钟过期"）</div>}
        {msgs.map(m => m.role === 'user' ? (
          <div key={m.id} style={{ alignSelf: 'flex-end', maxWidth: '80%', background: 'var(--accent,#2563eb)', color: '#fff', borderRadius: '10px 10px 2px 10px', padding: '7px 11px', fontSize: 12.5, whiteSpace: 'pre-wrap' }}>{m.text}</div>
        ) : (
          <div key={m.id} style={{ alignSelf: 'flex-start', maxWidth: '92%', background: 'rgba(22,163,74,.06)', border: '1px solid rgba(22,163,74,.25)', borderRadius: '10px 10px 10px 2px', padding: '7px 11px', fontSize: 12.5 }}>
            {m.status === 'PENDING'
              ? <span style={{ color: 'var(--text-dim)' }}><span className="spinner" style={{ width: 9, height: 9, display: 'inline-block', marginRight: 6 }} />结合上下文重写中…</span>
              : <>
                <div style={{ whiteSpace: 'pre-wrap', color: m.status === 'FAILED' ? 'var(--error)' : 'var(--text)' }}>{m.text}</div>
                {m.status === 'DONE' && (
                  <button className="btn" style={{ fontSize: 10.5, padding: '2px 10px', marginTop: 6 }} onClick={() => copyMsg(m)}>
                    {copiedId === m.id ? '✅ 已复制，去发给实例' : '📋 复制这条提示词'}
                  </button>
                )}
              </>}
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
        <textarea className="form-input" rows={3} style={{ flex: 1, fontSize: 12.5, resize: 'vertical', fontFamily: 'inherit' }}
          placeholder={disabled ? '先在右上角绑定执行实例' : '原始诉求（Shift+Enter 换行，Enter 生成）'}
          value={text} onChange={e => setText(e.target.value)} disabled={disabled}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }} />
        <button className="btn btn-primary" style={{ fontSize: 12.5, padding: '6px 14px', flexShrink: 0 }}
          disabled={disabled || !text.trim() || sending} onClick={send}>✨ 生成</button>
      </div>
    </div>
  )
}

// ===== 工作流模板编辑器 =====

function WorkflowEditor({ onBack, showToast }) {
  const pageFormRef = useRef(null)
  const confirmAction = useConfirm()
  const [templates, setTemplates] = useState([])
  const [editing, setEditing] = useState(null)   // {id?, name, description, mode, nodes:[...]}

  const load = () => fetch('/api/workflows').then(r => r.json()).then(setTemplates).catch(() => {})
  useEffect(() => { load() }, [])

  function startEdit(t) {
    setEditing({
      id: t.id, name: t.name, description: t.description || '', mode: t.mode, builtin: t.builtin,
      nodes: safeParseNodes(t.nodesJson).map(n => ({ ...n })),
    })
  }
  function startCreate() {
    setEditing({ id: null, name: '', description: '', mode: 'API', nodes: [] })
  }
  function duplicate(t) {
    setEditing({
      id: null, name: t.name + '（副本）', description: t.description || '', mode: t.mode,
      nodes: safeParseNodes(t.nodesJson).map(n => ({ ...n })),
    })
  }
  async function saveTemplate() {
    if (!editing.name.trim()) return showToast('请填写模板名称', 'error')
    const body = JSON.stringify({
      name: editing.name, description: editing.description, mode: editing.mode,
      nodesJson: JSON.stringify(editing.nodes.map((n, i) => ({ ...n, id: n.id || `n${i + 1}` }))),
    })
    const url = editing.id ? `/api/workflows/${editing.id}` : '/api/workflows'
    const res = await fetch(url, { method: editing.id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body })
    if (!res.ok) {
      const d = await res.json().catch(() => ({}))
      return showToast(d.error || '保存失败', 'error')
    }
    pageFormRef.current?.commit(); showToast('模板已保存', 'success')
    setEditing(null)
    load()
  }
  async function removeTemplate(t) {
    if (!await confirmAction({ kind: 'popconfirm', title: '删除模板？', body: `删除模板「${t.name}」？（已创建的工作存档不受影响）删除后无法恢复。`, confirmLabel: '删除模板' })) return
    const res = await fetch(`/api/workflows/${t.id}`, { method: 'DELETE' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '删除失败', 'error')
    showToast('已删除', 'success')
    load()
  }

  const templateDirty = useDirtyValue(editing, editing ? `template:${editing.id || 'new'}` : 'closed')

  // 节点编辑
  const [nodeDraft, setNodeDraft] = useState(null)   // {index, node}
  function moveNode(i, dir) {
    setEditing(e => {
      const nodes = [...e.nodes]
      const j = i + dir
      if (j < 0 || j >= nodes.length) return e
      ;[nodes[i], nodes[j]] = [nodes[j], nodes[i]]
      return { ...e, nodes }
    })
  }

  if (editing) {
    return (
      <DraftPage ref={pageFormRef} dirty={templateDirty}><div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: '1rem' }}>
          <button className="btn" onClick={() => setEditing(null)}>← 返回</button>
          <h1 className="page-title" style={{ margin: 0, fontSize: 18 }}>{editing.id ? '编辑模板' : '新建模板'}</h1>
          <div style={{ flex: 1 }} />
          <button className="btn btn-primary" onClick={saveTemplate}>💾 保存模板</button>
        </div>
        <div className="card" style={{ padding: '16px 20px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: 12 }}>
            <div className="form-group"><label className="form-label">模板名称 *</label>
              <input className="form-input" value={editing.name} onChange={e => setEditing(x => ({ ...x, name: e.target.value }))} /></div>
            <div className="form-group"><label className="form-label">模式</label>
              <select className="form-input" value={editing.mode} disabled={!!editing.builtin}
                onChange={e => setEditing(x => ({ ...x, mode: e.target.value }))}>
                <option value="API">API 供应商</option><option value="CM">CM 供应商</option>
              </select></div>
            <div className="form-group"><label className="form-label">描述</label>
              <input className="form-input" value={editing.description} onChange={e => setEditing(x => ({ ...x, description: e.target.value }))} /></div>
          </div>

          <div style={{ fontSize: 13, fontWeight: 700, margin: '8px 0' }}>节点路径（顺序即游戏路径）</div>
          {editing.nodes.map((n, i) => (
            <div key={i} className="wf-editor-row">
              <span style={{ color: 'var(--text-dim)', fontSize: 12, width: 24 }}>{i + 1}</span>
              <span style={{ width: 90, fontSize: 12 }}>{NODE_TYPE[n.type]?.icon} {NODE_TYPE[n.type]?.label}</span>
              <span style={{ flex: 1, fontSize: 13, fontWeight: 600 }}>{(nodeDraft?.index === i ? nodeDraft.node.title : n.title) || '(未命名)'}</span>
              <span style={{ fontSize: 12, color: 'var(--text-dim)', maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{n.description}</span>
              <button className="btn" style={{ fontSize: 12, padding: '2px 8px' }} onClick={() => moveNode(i, -1)}>↑</button>
              <button className="btn" style={{ fontSize: 12, padding: '2px 8px' }} onClick={() => moveNode(i, 1)}>↓</button>
              <button className="btn" style={{ fontSize: 12, padding: '2px 8px' }} onClick={() => setNodeDraft({ index: i, node: { ...n } })}>编辑</button>
              <button className="btn" style={{ fontSize: 12, padding: '2px 8px', color: 'var(--error)' }}
                onClick={() => setEditing(x => ({ ...x, nodes: x.nodes.filter((_, xi) => xi !== i) }))}>删</button>
            </div>
          ))}
          <button className="btn" style={{ fontSize: 12, marginTop: 8 }}
            onClick={() => setNodeDraft({ index: -1, node: { id: `n${editing.nodes.length + 1}`, title: '', type: 'manual', description: '', prompt: '' } })}>
            + 添加节点
          </button>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 10, lineHeight: 1.8 }}>
            节点 prompt 支持占位符：{'{supplierName} {supplierId} {libraryPath} {docs} {skills} {extra}'}（{'{docs}'} 现为<b>所选阶段的知识库正文</b>：来源、指纹和实际文本，创建时录入的文档已自动并入）、{'{artifact:n1}'}（引用某节点工件）。
            所有 agent 节点末尾会自动追加「执行动作规范」（Trellis 四段 / 卡点协议 / Postman 实测协议），无需写进模板。
            存档配置了 Skill 时，即使 prompt 不写 {'{skills}'} 也会自动在末尾追加"可用技能"段。
          </div>
        </div>

        {nodeDraft && (
          <Surface kind="drawer" onClose={() => setNodeDraft(null)} form>
            <div className="modal-box" style={{ maxWidth: 800, width: 'min(800px,94vw)' }} onClick={e => e.stopPropagation()}>
              <div className="modal-title">{nodeDraft.index >= 0 ? `编辑节点 ${nodeDraft.index + 1}` : '添加节点'}</div>
              <div className="modal-body">
                <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}>
                  <div className="form-group"><label className="form-label">节点标题 *</label>
                    <input className="form-input" value={nodeDraft.node.title}
                      onChange={e => setNodeDraft(d => ({ ...d, node: { ...d.node, title: e.target.value } }))} /></div>
                  <div className="form-group"><label className="form-label">类型</label>
                    <select className="form-input" value={nodeDraft.node.type}
                      onChange={e => setNodeDraft(d => ({ ...d, node: { ...d.node, type: e.target.value } }))}>
                      {Object.entries(NODE_TYPE).map(([k, v]) => <option key={k} value={k}>{v.icon} {v.label}</option>)}
                    </select></div>
                </div>
                <div className="form-group"><label className="form-label">上下文使用阶段</label>
                  <select className="form-input" value={nodeDraft.node.contextStage || 'auto'} onChange={e => setNodeDraft(d => ({ ...d, node: { ...d.node, contextStage: e.target.value } }))}>
                    <option value="auto">兼容旧模板（按标题识别）</option><option value="all">全部材料</option><option value="analysis">文档分析</option><option value="probe">接口实测（需要工具证据）</option><option value="codegen">代码开发（完整前置门禁）</option>
                  </select>
                </div>
                <div className="form-group"><label className="form-label">节点说明（给玩家看的"这一步做什么"）</label>
                  <textarea className="form-input" rows={3} value={nodeDraft.node.description || ''}
                    onChange={e => setNodeDraft(d => ({ ...d, node: { ...d.node, description: e.target.value } }))} /></div>
                <div className="form-group">
                  <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, cursor: 'pointer' }}>
                    <input type="checkbox" checked={!!nodeDraft.node.codegenPrep}
                      onChange={e => setNodeDraft(d => ({ ...d, node: { ...d.node, codegenPrep: e.target.checked } }))} />
                    🧾 写代码准备门禁（玩家须先提交三方会议文档+配置KV才能点完成；节点页提供提示词生成）
                  </label>
                </div>
                {(nodeDraft.node.type === 'agent' || nodeDraft.node.type === 'agent_check') && (
                  <div className="form-group"><label className="form-label">Prompt 模板（发给实例执行；支持占位符，见下方提示）</label>
                    <textarea className="form-input" rows={10} style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
                      value={nodeDraft.node.prompt || ''}
                      onChange={e => setNodeDraft(d => ({ ...d, node: { ...d.node, prompt: e.target.value } }))} /></div>
                )}
              </div>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 14 }}>
                <button className="btn" onClick={() => setNodeDraft(null)}>取消</button>
                <button className="btn btn-primary" onClick={() => {
                  if (!nodeDraft.node.title.trim()) return showToast('请填写节点标题', 'error')
                  setEditing(e => {
                    const nodes = [...e.nodes]
                    if (nodeDraft.index >= 0) nodes[nodeDraft.index] = nodeDraft.node
                    else nodes.push(nodeDraft.node)
                    return { ...e, nodes }
                  })
                  setNodeDraft(null)
                }}>应用到模板</button>
              </div>
            </div>
          </Surface>
        )}
      </div></DraftPage>
    )
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: '1rem' }}>
        <button className="btn" onClick={onBack}>← 工作台</button>
        <h1 className="page-title" style={{ margin: 0 }}>工作流模板</h1>
        <div style={{ flex: 1 }} />
        <button className="btn btn-primary" onClick={startCreate}>+ 新建模板</button>
      </div>
      {templates.map(t => (
        <div className="card" key={t.id} style={{ padding: '14px 18px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <b>{t.name}</b>
            <span className="hook-badge">{t.mode === 'API' ? 'API 供应商' : 'CM 供应商'}</span>
            {t.builtin && <span className="hook-badge dim">内置</span>}
            <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>{safeParseNodes(t.nodesJson).length} 个节点</span>
            <div style={{ flex: 1 }} />
            <button className="btn" style={{ fontSize: 12, padding: '3px 12px' }} onClick={() => startEdit(t)}>编辑</button>
            <button className="btn" style={{ fontSize: 12, padding: '3px 12px' }} onClick={() => duplicate(t)}>复制为新模板</button>
            {!t.builtin && <button className="btn" style={{ fontSize: 12, padding: '3px 12px', color: 'var(--error)' }} onClick={() => removeTemplate(t)}>删除</button>}
          </div>
          {t.description && <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6 }}>{t.description}</div>}
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 8 }}>
            {safeParseNodes(t.nodesJson).map((n, i) => (
              <span key={i} className="hook-badge dim" data-tooltip={n.description} tabIndex={0}>{i + 1}. {NODE_TYPE[n.type]?.icon} {n.title}</span>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

// ===== 共享小工具（供应商模块使用；数字员工已拆分为独立页面 pages/Employees.jsx） =====

/** 创建者标签：数字员工（👔 名字）或管理员（👑 admin） */
function CreatorBadge({ id, name, dim = false }) {
  return id
    ? <span className={'hook-badge' + (dim ? ' dim' : '')} data-tooltip={`由数字员工「${name || id}」创建`} tabIndex={0}>👔 {name || `员工#${id}`}</span>
    : <span className="hook-badge dim" data-tooltip="由管理员在面板创建" tabIndex={0}>👑 admin</span>
}

/** 实例独占绑定映射：String(instId) → {empId, label}（被数字员工绑定的实例不可再分配给他人） */
function useOccupied() {
  const [occupied, setOccupied] = useState({})
  useEffect(() => {
    fetch('/api/employees').then(r => r.json()).then(emps => {
      const map = {}
      ;(emps || []).forEach(emp => (emp.instances || []).forEach(i => {
        map[String(i.id)] = { empId: emp.id, label: `${emp.emoji || '👔'} ${emp.name}` }
      }))
      setOccupied(map)
    }).catch(() => {})
  }, [])
  return occupied
}

