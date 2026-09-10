import { useState, useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

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
  const [view, setView] = useState('list')       // list | create | game | workflows
  const [jobId, setJobId] = useState(null)
  const [toast, setToast] = useState(null)

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  return (
    <div>
      {view === 'list' && <JobList onOpen={id => { setJobId(id); setView('game') }}
        onCreate={() => setView('create')} onWorkflows={() => setView('workflows')} showToast={showToast} />}
      {view === 'create' && <CreateJob onBack={() => setView('list')}
        onCreated={id => { setJobId(id); setView('game') }} showToast={showToast} />}
      {view === 'game' && jobId && <GameView jobId={jobId} onBack={() => { setJobId(null); setView('list') }} showToast={showToast} />}
      {view === 'workflows' && <WorkflowEditor onBack={() => setView('list')} showToast={showToast} />}
      {toast && <div className={`toast toast-${toast.type}`}>{toast.msg}</div>}
    </div>
  )
}

// ===== 工作列表 =====

function JobList({ onOpen, onCreate, onWorkflows, showToast }) {
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
    if (!confirm(`删除工作「${j.name}」？存档和节点记录将清除。`)) return
    await fetch(`/api/supplier-jobs/${j.id}`, { method: 'DELETE' })
    showToast('已删除', 'success')
    load()
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.2rem' }}>
        <div>
          <h1 className="page-title" style={{ margin: 0 }}>🏭 供应商接入工作台</h1>
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
              </div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 8, lineHeight: 1.9 }}>
                <div>供应商: {j.supplierName || '—'}{j.supplierCode ? ` (ID: ${j.supplierCode})` : ''}</div>
                <div>执行实例: {instName(j.instanceId)} · 当前节点: {j.currentNodeId || (done ? '已通关' : '—')}</div>
                <div>更新: {String(j.updatedAt || '').replace('T', ' ').substring(5, 16)}</div>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
                <button className="btn" style={{ fontSize: 11, padding: '2px 10px', color: 'var(--error)' }} onClick={e => remove(j, e)}>删除</button>
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
  const [step, setStep] = useState(1)
  const [mode, setMode] = useState('API')
  const [templates, setTemplates] = useState([])
  const [instances, setInstances] = useState([])
  const [form, setForm] = useState({
    name: '', supplierCode: '', supplierName: '', libraryPath: '',
    docs: [{ ...emptyDoc }], skills: [], instanceId: '', templateId: '',
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
      onCreated(data.id)
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <div style={{ maxWidth: 760, margin: '0 auto' }}>
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
            <div className="mode-card disabled" title="CM 模式工作流尚未开发">
              <div style={{ fontSize: 30 }}>🏨</div>
              <div style={{ fontWeight: 700, margin: '8px 0 4px' }}>新接 CM 供应商</div>
              <div style={{ fontSize: 12, color: 'var(--text-dim)'}}>即将上线（可先到工作流模板里自行设计）</div>
            </div>
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
                <input className="form-input" style={{ flex: 1 }} placeholder={d.type === 'url' ? 'https://docs.supplier.com/api' : 'D:\\docs\\supplier-api.pdf'}
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
                {instances.filter(i => i.enabled).map(i => (
                  <option key={i.id} value={i.id}>{i.name || `#${i.id}`}{i.claudeModel ? ` (${i.claudeModel})` : ''}</option>
                ))}
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
    </div>
  )
}

function safeParseNodes(json) {
  try { const a = JSON.parse(json || '[]'); return Array.isArray(a) ? a : [] } catch { return [] }
}

// ===== 游戏视图 =====

function GameView({ jobId, onBack, showToast }) {
  const [job, setJob] = useState(null)
  const [selected, setSelected] = useState(null)     // nodeId
  const [instances, setInstances] = useState([])
  const [instruction, setInstruction] = useState('')
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
  async function completeNode(nodeId) {
    await fetch(`/api/supplier-jobs/${jobId}/nodes/${nodeId}/complete`, { method: 'POST' })
    showToast('✅ 节点完成', 'success')
    setSelected(null)   // 自动跳到下一个推荐节点
    load()
  }
  async function saveArtifact(nodeId) {
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

  return (
    <div>
      {/* 头部 */}
      <div className="card" style={{ padding: '12px 18px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <button className="btn" style={{ fontSize: 12, padding: '4px 12px' }} onClick={onBack}>← 工作台</button>
          <span style={{ fontSize: 16, fontWeight: 700 }}>{job.status === 'COMPLETED' ? '🏆' : '🎮'} {job.name}</span>
          <span className="hook-badge">{job.mode === 'API' ? 'API 供应商' : 'CM 供应商'}</span>
          {job.status === 'COMPLETED' && <span className="hook-badge" style={{ background: 'rgba(22,163,74,.1)', color: '#16a34a', borderColor: 'rgba(22,163,74,.3)' }}>已通关 🎉</span>}
          <div style={{ flex: 1 }} />
          <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>进度 {doneCount}/{nodes.length}</span>
          <div className="pass-bar" style={{ maxWidth: 140 }}><div className="pass-fill" style={{ width: `${nodes.length ? doneCount / nodes.length * 100 : 0}%` }} /></div>
          <label style={{ fontSize: 12, color: 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: 6 }}>
            🤖 执行实例
            <select className="form-input" style={{ width: 170, padding: '4px 8px', fontSize: 12 }}
              value={job.instanceId || ''} onChange={e => switchInstance(e.target.value)}>
              <option value="">（未绑定）</option>
              {instances.filter(i => i.enabled).map(i => <option key={i.id} value={i.id}>{i.name || `#${i.id}`}</option>)}
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

      <div style={{ display: 'flex', gap: 14, alignItems: 'flex-start' }}>
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
                  {NODE_STATUS[cur.status]?.label}
                </span>
                <div style={{ flex: 1 }} />
                {cur.status !== 'DONE' && (
                  <button className="btn btn-primary" style={{ fontSize: 12, padding: '5px 14px' }}
                    onClick={() => completeNode(cur.nodeId)}>✓ 我已完成此节点</button>
                )}
              </div>

              <div style={{ fontSize: 13, color: 'var(--text-dim)', marginTop: 10, lineHeight: 1.8, whiteSpace: 'pre-wrap' }}>
                {cur.description}
              </div>

              {/* agent 节点：执行控制 */}
              {(cur.type === 'agent' || cur.type === 'agent_check') && (
                <div style={{ marginTop: 14, paddingTop: 12, borderTop: '1px dashed var(--border,#e5e7eb)' }}>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <input className="form-input" style={{ flex: 1, padding: '7px 10px', fontSize: 12.5 }}
                      placeholder={cur.artifact ? '纠正/补充指示（如：验价接口映射错了，应该是 xxx），留空则重跑' : '补充指示（可选）'}
                      value={instruction} onChange={e => setInstruction(e.target.value)}
                      onKeyDown={e => { if (e.key === 'Enter') runNode(cur.nodeId) }} />
                    <button className="btn btn-primary" style={{ fontSize: 12.5, padding: '6px 16px', flexShrink: 0 }}
                      disabled={cur.status === 'RUNNING' || !job.instanceId}
                      onClick={() => runNode(cur.nodeId)}>
                      {cur.status === 'RUNNING' ? '执行中…' : (cur.artifact ? '🔄 带指示重跑' : '▶ 让实例执行')}
                    </button>
                  </div>
                  {!job.instanceId && <div style={{ fontSize: 11, color: 'var(--error)', marginTop: 6 }}>⚠️ 还没绑定执行实例，先在右上角选择</div>}
                  {cur.note && <div style={{ fontSize: 12, color: 'var(--error)', marginTop: 8 }}>⚠️ {cur.note}</div>}
                </div>
              )}

              {/* 工件区 */}
              {(cur.artifact || editingArtifact) && (
                <div style={{ marginTop: 14 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
                    <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-dim)' }}>📄 工件（{cur.lastRunAt && cur.lastRunAt !== 'null' ? '生成于 ' + String(cur.lastRunAt).replace('T', ' ').substring(5, 16) : '手动编辑'}）</span>
                    <div style={{ flex: 1 }} />
                    {editingArtifact ? (
                      <>
                        <button className="btn" style={{ fontSize: 11, padding: '3px 10px' }} onClick={() => setEditingArtifact(false)}>取消</button>
                        <button className="btn btn-primary" style={{ fontSize: 11, padding: '3px 10px' }} onClick={() => saveArtifact(cur.nodeId)}>保存</button>
                      </>
                    ) : (
                      <button className="btn" style={{ fontSize: 11, padding: '3px 10px' }}
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
          {nodes.map((n, i) => {
            const st = NODE_STATUS[n.status] || NODE_STATUS.PENDING
            const isCur = n.nodeId === job.currentNodeId
            const isSel = cur && n.nodeId === cur.nodeId
            return (
              <div key={n.nodeId} className="wf-row">
                <div className="wf-line-col">
                  <span className={'wf-dot' + (isCur && n.status !== 'DONE' ? ' pulse' : '')}
                    style={{ background: n.status === 'DONE' ? '#16a34a' : (isCur ? '#3b82f6' : '#93c5fd') }}>
                    {n.status === 'DONE' ? '✓' : n.status === 'RUNNING' ? '' : i + 1}
                    {n.status === 'RUNNING' && <span className="spinner" style={{ width: 9, height: 9, borderWidth: 1.5 }} />}
                  </span>
                  {i < nodes.length - 1 && <span className="wf-line" />}
                </div>
                <div className={'wf-node' + (isSel ? ' sel' : '')} onClick={() => { setSelected(n.nodeId); setEditingArtifact(false) }}>
                  <div style={{ fontSize: 12.5, fontWeight: isSel ? 700 : 500, color: n.status === 'DONE' ? '#16a34a' : 'var(--text)' }}>
                    {NODE_TYPE[n.type]?.icon} {n.title}
                  </div>
                  <div style={{ fontSize: 10.5, color: 'var(--text-dim)', marginTop: 2 }}>
                    {NODE_TYPE[n.type]?.label} · <span style={{ color: st.color }}>{st.label}</span>
                    {n.artifact ? ' · 📄有工件' : ''}
                  </div>
                </div>
              </div>
            )
          })}
          {running && <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 10 }}>🔄 实例执行中，状态每 4 秒自动刷新…</div>}
        </div>
      </div>
    </div>
  )
}

// ===== 工作流模板编辑器 =====

function WorkflowEditor({ onBack, showToast }) {
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
    showToast('模板已保存', 'success')
    setEditing(null)
    load()
  }
  async function removeTemplate(t) {
    if (!confirm(`删除模板「${t.name}」？（已创建的工作存档不受影响）`)) return
    const res = await fetch(`/api/workflows/${t.id}`, { method: 'DELETE' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '删除失败', 'error')
    showToast('已删除', 'success')
    load()
  }

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
      <div>
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
              <span style={{ flex: 1, fontSize: 13, fontWeight: 600 }}>{n.title || '(未命名)'}</span>
              <span style={{ fontSize: 11, color: 'var(--text-dim)', maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{n.description}</span>
              <button className="btn" style={{ fontSize: 11, padding: '2px 8px' }} onClick={() => moveNode(i, -1)}>↑</button>
              <button className="btn" style={{ fontSize: 11, padding: '2px 8px' }} onClick={() => moveNode(i, 1)}>↓</button>
              <button className="btn" style={{ fontSize: 11, padding: '2px 8px' }} onClick={() => setNodeDraft({ index: i, node: { ...n } })}>编辑</button>
              <button className="btn" style={{ fontSize: 11, padding: '2px 8px', color: 'var(--error)' }}
                onClick={() => setEditing(x => ({ ...x, nodes: x.nodes.filter((_, xi) => xi !== i) }))}>删</button>
            </div>
          ))}
          <button className="btn" style={{ fontSize: 12, marginTop: 8 }}
            onClick={() => setNodeDraft({ index: -1, node: { id: `n${editing.nodes.length + 1}`, title: '', type: 'manual', description: '', prompt: '' } })}>
            + 添加节点
          </button>
          <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 10, lineHeight: 1.8 }}>
            节点 prompt 支持占位符：{'{supplierName} {supplierId} {libraryPath} {docs} {skills} {extra}'}（玩家在节点页填的补充指示）、{'{artifact:n1}'}（引用某节点工件）。
            存档配置了 Skill 时，即使 prompt 不写 {'{skills}'} 也会自动在末尾追加"可用技能"段。
          </div>
        </div>

        {nodeDraft && (
          <div className="modal-mask" onClick={() => setNodeDraft(null)}>
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
                <div className="form-group"><label className="form-label">节点说明（给玩家看的"这一步做什么"）</label>
                  <textarea className="form-input" rows={3} value={nodeDraft.node.description || ''}
                    onChange={e => setNodeDraft(d => ({ ...d, node: { ...d.node, description: e.target.value } }))} /></div>
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
                }}>确定</button>
              </div>
            </div>
          </div>
        )}
      </div>
    )
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: '1rem' }}>
        <button className="btn" onClick={onBack}>← 工作台</button>
        <h1 className="page-title" style={{ margin: 0 }}>🛠 工作流模板</h1>
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
              <span key={i} className="hook-badge dim" title={n.description}>{i + 1}. {NODE_TYPE[n.type]?.icon} {n.title}</span>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
