import Toast from '../components/ui/Toast'
import { Surface, useConfirm, DraftPage } from '../components/ui/FloatingUI'
import { useState, useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useOccupied } from './Supplier'

// ===== 常量 =====

// 这台机器上的真实 skill 路径（已核实存在），新建任务默认预填
const DEFAULT_REVIEW_SKILL = 'D:/dida/SupplierV2/.claude/skills/review-supplier'
const DEFAULT_CREATE_SKILL = 'D:/dida/SupplierV2/.claude/skills/create-supplier'

const KINDS = [
  { value: 'build', icon: '🏗', label: 'build · 编译', hint: '判定来源：编译退出码（基线门槛）' },
  { value: 'test', icon: '🧪', label: 'test · 测试', hint: '判定来源：测试退出码 + 用例计数断言' },
  { value: 'script', icon: '📜', label: 'script · 脚本', hint: '判定来源：脚本退出码 + stdout 断言（自然语言 eval 翻译的主要落点）' },
  { value: 'http', icon: '🌐', label: 'http · 实测', hint: '判定来源：Postman CLI 真实调用的退出码 + 响应断言' },
  { value: 'static', icon: '🧱', label: 'static · 结构规则', hint: '判定来源：确定性规则程序（grep/文件存在性/计数），不是模型' },
]

const VERDICT = {
  PASS: { label: 'PASS', color: '#16a34a' },
  FAIL: { label: 'FAIL', color: '#dc2626' },
  TIMEOUT: { label: '超时', color: '#d97706' },
  SKIPPED: { label: '跳过', color: '#6b7280' },
  ERROR: { label: '执行错误', color: '#dc2626' },
  RUNNING: { label: '执行中', color: '#0891b2' },
}

// 默认基线命令占位（PRD Q1 未定项，待真实验证——仅提示，不会自动执行）
const TARGET_PLACEHOLDER = {
  build: 'dotnet build <解决方案或项目路径> --nologo（默认基线建议，待真实验证后填入）',
  test: 'dotnet test <测试项目路径> --filter "FullyQualifiedName~<供应商命名空间>" --nologo（待真实验证）',
  script: '如：python scripts/check_price_split.py 2166   或   findstr /s /c:"catch { }" src\\*.cs',
  http: '如：postman collection run <集合文件> --env-var ...',
  static: '如：findstr /s /m /c:"HttpClient" src\\Business\\Supplier\\Dorak\\*.cs && exit /b 1 || exit /b 0',
}

const EXPECT_HELP = `断言语法（每行一条，全部满足才 PASS；留空 = 只看退出码 0）：
exit=2            退出码等值（不写默认要求 0）
stdout~正则        输出匹配正则，如 stdout~Tests run: \\d+, Failed: 0
stdout=子串        输出包含字面子串
json:data.count=3  输出为 JSON 且该路径的值等于 3`

const emptyCaseForm = { title: '', kind: 'script', target: '', expect: '', notes: '', timeoutSec: 120, requiresSandbox: false, enabled: true }

// ===== 主页面（左列表 + 右详情） =====

export default function EvalLoop() {
  const [route, setRoute] = useSearchParams()
  const taskId = /^\d+$/.test(route.get('task') || '') ? Number(route.get('task')) : null
  const creating = route.get('view') === 'create'
  const [toast, setToast] = useState(null)

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 4000)
  }

  return (
    <div>
      {creating ? (
        <CreateTask onBack={() => setRoute({})} onCreated={id => setRoute({ task: String(id) })} showToast={showToast} />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: '300px 1fr', gap: 16, alignItems: 'start' }}>
          <TaskList selected={taskId} onSelect={id => setRoute({ task: String(id) })}
            onCreate={() => setRoute({ view: 'create' })} showToast={showToast} />
          {taskId
            ? <TaskDetail key={taskId} taskId={taskId} showToast={showToast} onDeleted={() => setRoute({})} />
            : <div className="card" style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
                选择左侧任务查看 eval，或点「+ 新建测评任务」开始。<br />
                <span style={{ fontSize: 12 }}>每条 eval 都由后端起进程真实执行，PASS/FAIL 只来自退出码与输出断言。</span>
              </div>}
        </div>
      )}
      <Toast toast={toast} onDismiss={() => setToast(null)} />
    </div>
  )
}

// ===== 左栏：任务列表 =====

function TaskList({ selected, onSelect, onCreate, showToast }) {
  const confirmAction = useConfirm()
  const [tasks, setTasks] = useState([])

  const load = () => fetch('/api/eval-tasks').then(r => r.ok ? r.json() : []).then(rows => { if (Array.isArray(rows)) setTasks(rows) }).catch(() => {})
  useEffect(() => {
    load()
    const t = setInterval(load, 5000)
    return () => clearInterval(t)
  }, [])

  async function remove(task, e) {
    e.stopPropagation()
    if (!await confirmAction({ kind: 'popconfirm', title: '删除测评任务？', body: `删除任务「${task.name}」？其下全部 eval 条目与执行记录将清除，删除后无法恢复。`, confirmLabel: '删除任务' })) return
    const res = await fetch(`/api/eval-tasks/${task.id}`, { method: 'DELETE' })
    if (!res.ok) { const d = await res.json().catch(() => ({})); return showToast(d.error || '删除失败', 'error') }
    showToast('已删除', 'success')
    load()
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h1 className="page-title" style={{ margin: 0, fontSize: 17 }}>代码质量测评</h1>
        <button className="btn btn-primary" style={{ fontSize: 12, padding: '4px 12px' }} onClick={onCreate}>+ 新建测评任务</button>
      </div>
      {tasks.length === 0 && (
        <div className="card" style={{ color: 'var(--text-dim)', padding: '1.4rem', fontSize: 13 }}>
          还没有测评任务。新建任务后可从 review skill 生成初版 eval，再逐条补成可执行检查。
        </div>
      )}
      {tasks.map(t => {
        const s = t.stats || {}
        const active = t.id === selected
        return (
          <div key={t.id} className="card" onClick={() => onSelect(t.id)}
            style={{ cursor: 'pointer', padding: '12px 14px', marginBottom: 10, borderColor: active ? 'var(--accent, #3b82f6)' : undefined, boxShadow: active ? '0 0 0 1px var(--accent, #3b82f6) inset' : undefined }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ fontSize: 14, fontWeight: 700, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{t.name}</span>
              {t.supplierCode && <span className="hook-badge">{t.supplierCode}</span>}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <span data-tooltip="分母 = 已落地且真实执行出结果的 eval；未落地/跳过不计入" tabIndex={0}>
                通过 <b style={{ color: s.executed > 0 && s.passed === s.executed ? '#16a34a' : undefined }}>{s.passed ?? 0}/{s.executed ?? 0}</b>
              </span>
              {(s.notLanded ?? 0) > 0 && <span className="hook-badge dim">未落地 {s.notLanded}</span>}
              {(s.skipped ?? 0) > 0 && <span className="hook-badge dim">跳过 {s.skipped}</span>}
              {(s.running ?? 0) > 0 && <span className="spinner" style={{ width: 9, height: 9 }} />}
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 4 }}>
              <button className="btn" style={{ fontSize: 11, padding: '1px 8px', color: 'var(--error)' }} onClick={e => remove(t, e)}>删除</button>
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ===== 新建任务（就这几项：任务名 / 供应商标识 / 绑定实例 / 两个 skill 路径） =====

function CreateTask({ onBack, onCreated, showToast }) {
  const pageFormRef = useRef(null)
  const occupied = useOccupied()
  const [instances, setInstances] = useState([])
  const [form, setForm] = useState({
    name: '', supplierCode: '', instanceId: '',
    reviewSkillPath: DEFAULT_REVIEW_SKILL, createSkillPath: DEFAULT_CREATE_SKILL,
  })
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {})
  }, [])

  async function submit() {
    if (!form.name.trim()) return showToast('请填写任务名', 'error')
    if (!form.instanceId) return showToast('请绑定执行实例', 'error')
    setSaving(true)
    try {
      const res = await fetch('/api/eval-tasks', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: form.name.trim(),
          supplierCode: form.supplierCode.trim(),
          instanceId: Number(form.instanceId),
          reviewSkillPath: form.reviewSkillPath.trim(),
          createSkillPath: form.createSkillPath.trim(),
        }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || '创建失败')
      showToast('测评任务已创建', 'success')
      pageFormRef.current?.commit(); onCreated(data.id)
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <DraftPage ref={pageFormRef}><div style={{ maxWidth: 720, margin: '0 auto' }}>
      <h1 className="page-title">📐 新建测评任务</h1>
      <div className="card">
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div className="form-group">
            <label className="form-label">任务名 *</label>
            <input className="form-input" placeholder="如：Dorak 代码质量测评" value={form.name}
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          </div>
          <div className="form-group">
            <label className="form-label">供应商标识</label>
            <input className="form-input" placeholder="如：2166 / dorak" value={form.supplierCode}
              onChange={e => setForm(f => ({ ...f, supplierCode: e.target.value }))} />
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">绑定实例 *（提供 eval 执行工作目录；改代码循环在后续版本接入）</label>
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
        {/* skill 路径输入：复用工作台「关联 Skill」的实现风格（图标 + 路径输入行） */}
        <div className="form-group">
          <label className="form-label">review-supplier skill 路径（「生成初版 eval」会读它的 references/ 检查表，只读不改）</label>
          <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
            <span style={{ display: 'flex', alignItems: 'center', fontSize: 15, flexShrink: 0 }}>🧠</span>
            <input className="form-input" style={{ flex: 1 }} placeholder={DEFAULT_REVIEW_SKILL}
              value={form.reviewSkillPath}
              onChange={e => setForm(f => ({ ...f, reviewSkillPath: e.target.value }))} />
          </div>
          <label className="form-label">create-supplier skill 路径（本轮仅记录，经验写回提案在后续版本）</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <span style={{ display: 'flex', alignItems: 'center', fontSize: 15, flexShrink: 0 }}>🧠</span>
            <input className="form-input" style={{ flex: 1 }} placeholder={DEFAULT_CREATE_SKILL}
              value={form.createSkillPath}
              onChange={e => setForm(f => ({ ...f, createSkillPath: e.target.value }))} />
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8 }}>
          <button className="btn" onClick={onBack}>取消</button>
          <button className="btn btn-primary" onClick={submit} disabled={saving}>{saving ? '创建中…' : '创建任务'}</button>
        </div>
      </div>
    </div></DraftPage>
  )
}

// ===== 右栏：任务详情（eval 表格 + 统计 + 试跑） =====

function TaskDetail({ taskId, showToast, onDeleted }) {
  const confirmAction = useConfirm()
  const [detail, setDetail] = useState(null)
  const [editing, setEditing] = useState(null)        // null | 'new' | case 对象
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [draftsOpen, setDraftsOpen] = useState(false)
  const [expanded, setExpanded] = useState(null)      // caseId
  const [history, setHistory] = useState(null)        // {caseId, rows}
  const pollRef = useRef(null)

  const load = () => fetch(`/api/eval-tasks/${taskId}`).then(r => r.ok ? r.json() : null).then(d => { if (d) setDetail(d) }).catch(() => {})
  useEffect(() => {
    load()
    pollRef.current = setInterval(load, 3000)
    return () => clearInterval(pollRef.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskId])

  if (!detail) return <div className="card">加载中…</div>
  const s = detail.stats || {}
  const cases = detail.cases || []
  const running = (s.running ?? 0) > 0

  async function trial(c) {
    const res = await fetch(`/api/eval-tasks/${taskId}/cases/${c.id}/trial`, { method: 'POST' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '试跑启动失败', 'error')
    showToast(`▶ 已开始执行「${c.title}」，结果出来会自动刷新`, 'success')
    load()
  }

  async function toggleEnabled(c) {
    const res = await fetch(`/api/eval-tasks/${taskId}/cases/${c.id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title: c.title, kind: c.kind, target: c.target, expect: c.expect, notes: c.notes, timeoutSec: c.timeoutSec, requiresSandbox: c.requiresSandbox, enabled: !c.enabled }),
    })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '操作失败', 'error')
    setDetail(d); load()
  }

  async function removeCase(c, e) {
    e.stopPropagation()
    if (!await confirmAction({ kind: 'popconfirm', title: '删除这条 eval？', body: `「${c.title}」及其执行记录将清除，删除后无法恢复。`, confirmLabel: '删除' })) return
    const res = await fetch(`/api/eval-tasks/${taskId}/cases/${c.id}`, { method: 'DELETE' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '删除失败', 'error')
    showToast('已删除', 'success')
    load()
  }

  async function removeTask() {
    if (!await confirmAction({ title: '删除测评任务？', body: `删除任务「${detail.name}」？其下 ${cases.length} 条 eval 与全部执行记录将清除，删除后无法恢复。`, confirmLabel: '删除任务' })) return
    const res = await fetch(`/api/eval-tasks/${taskId}`, { method: 'DELETE' })
    const d = await res.json().catch(() => ({}))
    if (!res.ok) return showToast(d.error || '删除失败', 'error')
    showToast('已删除', 'success')
    onDeleted()
  }

  async function loadHistory(c) {
    const rows = await fetch(`/api/eval-tasks/${taskId}/cases/${c.id}/results`).then(r => r.ok ? r.json() : []).catch(() => [])
    setHistory({ caseId: c.id, rows: Array.isArray(rows) ? rows : [] })
  }

  return (
    <div>
      {/* 头部 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
        <h1 className="page-title" style={{ margin: 0, fontSize: 18 }}>📐 {detail.name}</h1>
        {detail.supplierCode && <span className="hook-badge">{detail.supplierCode}</span>}
        <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>
          实例: {detail.instanceName || '未绑定'}{detail.instanceName ? '' : '（eval 在后端进程目录执行）'}
        </span>
        <div style={{ flex: 1 }} />
        <button className="btn" style={{ fontSize: 12, padding: '3px 12px' }} onClick={() => setSettingsOpen(true)}>任务设置</button>
        <button className="btn" style={{ fontSize: 12, padding: '3px 12px', color: 'var(--error)' }} onClick={removeTask}>删除任务</button>
      </div>

      {/* 统计条：分母诚实——未落地/跳过单列，绝不混进通过率 */}
      <div className="card" style={{ padding: '12px 16px', display: 'flex', gap: 18, alignItems: 'center', flexWrap: 'wrap' }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>通过率（分母 = 已落地且真跑出结果）</div>
          <div style={{ fontSize: 24, fontWeight: 800 }}>
            {s.executed > 0
              ? <span style={{ color: s.passed === s.executed ? '#16a34a' : undefined }}>{s.passed}<span style={{ fontSize: 14, color: 'var(--text-dim)' }}> / {s.executed}</span></span>
              : <span style={{ color: 'var(--text-dim)' }}>—</span>}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', fontSize: 12 }}>
          <span className="hook-badge dim" data-tooltip="target 为空、还翻译不出可执行形式的条目——不计入通过率" tabIndex={0}>未落地 {s.notLanded ?? 0}</span>
          <span className="hook-badge dim" data-tooltip="requiresSandbox 且当前无沙箱凭据（环境变量 EVAL_SANDBOX_CREDENTIALS）——不计入通过率" tabIndex={0}>跳过 {s.skipped ?? 0}</span>
          <span className="hook-badge dim" data-tooltip="已落地但还没跑过——不计入通过率" tabIndex={0}>未跑 {s.notRun ?? 0}</span>
          {(s.failed ?? 0) > 0 && <span className="hook-badge" style={{ color: '#dc2626' }}>FAIL {s.failed}</span>}
          {(s.timeout ?? 0) > 0 && <span className="hook-badge" style={{ color: '#d97706' }}>超时 {s.timeout}</span>}
          {(s.error ?? 0) > 0 && <span className="hook-badge" style={{ color: '#dc2626' }}>执行错误 {s.error}</span>}
          {running && <span className="hook-badge"><span className="spinner" style={{ width: 9, height: 9, marginRight: 4 }} />执行中 {s.running}</span>}
        </div>
      </div>

      {/* 工具条 */}
      <div style={{ display: 'flex', gap: 8, margin: '12px 0' }}>
        <button className="btn btn-primary" style={{ fontSize: 13 }} onClick={() => setEditing('new')}>+ 新增 eval</button>
        <button className="btn" style={{ fontSize: 13 }} onClick={() => setDraftsOpen(true)}
          data-tooltip="只读 review-supplier 的 references/ 检查表，逐条搬成草稿（未落地），勾选确认后才入库；生成环节不做任何判定" tabIndex={0}>
          🧠 从 review skill 生成初版 eval
        </button>
      </div>

      {/* eval 表格 */}
      {cases.length === 0 && (
        <div className="card" style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '2rem', fontSize: 13 }}>
          还没有 eval 条目。可以手动新增，或用「从 review skill 生成初版 eval」导入草稿后逐条补成可执行检查。
        </div>
      )}
      {cases.map(c => (
        <CaseRow key={c.id} c={c} expanded={expanded === c.id}
          onToggle={() => setExpanded(expanded === c.id ? null : c.id)}
          onTrial={() => trial(c)} onEdit={() => setEditing(c)} onRemove={e => removeCase(c, e)}
          onToggleEnabled={() => toggleEnabled(c)} onHistory={() => loadHistory(c)} />
      ))}

      {editing && <CaseEditor taskId={taskId} initial={editing === 'new' ? null : editing}
        onClose={() => setEditing(null)} onSaved={d => { setEditing(null); if (d?.id) setDetail(d); load(); showToast('已保存', 'success') }}
        showToast={showToast} />}
      {settingsOpen && <TaskSettings detail={detail} onClose={() => setSettingsOpen(false)}
        onSaved={d => { setSettingsOpen(false); if (d?.id) setDetail(d); showToast('设置已保存', 'success') }} showToast={showToast} />}
      {draftsOpen && <DraftsPanel taskId={taskId} onClose={() => setDraftsOpen(false)}
        onImported={n => { setDraftsOpen(null); load(); showToast(`已导入 ${n} 条草稿（均为未落地，补出可执行 target 并试跑后才计入通过率）`, 'success') }}
        showToast={showToast} />}
      {history && <Surface kind="drawer" label="执行记录" onClose={() => setHistory(null)}>
        <div className="modal-box" style={{ maxWidth: 640 }}>
          <div className="modal-title">最近执行记录</div>
          {history.rows.length === 0 && <div style={{ color: 'var(--text-dim)', fontSize: 13 }}>还没有执行记录。</div>}
          {history.rows.map(r => (
            <div key={r.id} className="card" style={{ padding: '10px 12px', marginBottom: 8 }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 12.5 }}>
                <VerdictBadge verdict={r.verdict} />
                <span>退出码 {r.exitCode ?? '—'}</span>
                <span>耗时 {fmtMs(r.elapsedMs)}</span>
                <span style={{ color: 'var(--text-dim)' }}>{String(r.ranAt || '').replace('T', ' ').substring(0, 19)}</span>
              </div>
              {r.reason && <div style={{ fontSize: 12, marginTop: 4, color: r.verdict === 'PASS' ? 'var(--text-dim)' : '#dc2626' }}>{r.reason}</div>}
            </div>
          ))}
        </div>
      </Surface>}
    </div>
  )
}

// ===== 表格行（含展开的输出详情） =====

function CaseRow({ c, expanded, onToggle, onTrial, onEdit, onRemove, onToggleEnabled, onHistory }) {
  const kind = KINDS.find(k => k.value === c.kind) || KINDS[2]
  const running = c.lastVerdict === 'RUNNING'
  return (
    <div className="card" style={{ padding: 0, marginBottom: 8, opacity: c.enabled ? 1 : 0.55 }}>
      <div onClick={onToggle} style={{ cursor: 'pointer', display: 'grid', gridTemplateColumns: '34px 1fr 108px 118px 168px', gap: 8, alignItems: 'center', padding: '10px 12px' }}>
        <input type="checkbox" checked={c.enabled} onClick={e => e.stopPropagation()} onChange={onToggleEnabled}
          aria-label={`启用/停用「${c.title}」`}
          data-tooltip="停用后不参与统计与后续轮跑" tabIndex={0} />
        <div style={{ overflow: 'hidden' }}>
          <div style={{ fontWeight: 600, fontSize: 13.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {kind.icon} {c.title}
            {!c.landed && <span className="hook-badge dim" style={{ marginLeft: 6 }} data-tooltip="target 为空：还翻译不出可执行形式——不可执行、不计入通过率" tabIndex={0}>未落地</span>}
            {c.requiresSandbox && <span className="hook-badge dim" style={{ marginLeft: 4 }} data-tooltip="需要沙箱凭据；无凭据时试跑记「跳过」，不计入通过率" tabIndex={0}>沙箱</span>}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--text-dim)', fontFamily: 'var(--font-mono)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {c.target || '（无可执行 target）'}
          </div>
        </div>
        <span className="hook-badge dim">{c.kind}</span>
        <VerdictBadge verdict={c.landed ? c.lastVerdict : null} />
        <div style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }} onClick={e => e.stopPropagation()}>
          <button className="btn" style={{ fontSize: 12, padding: '2px 10px' }} disabled={running || !c.landed || !c.enabled}
            onClick={onTrial}
            data-tooltip={!c.landed ? '未落地：先编辑补出可执行 target' : '真实执行这条 eval（超时强杀、输出落盘），判定只来自退出码与断言'} tabIndex={0}>
            {running ? <span className="spinner" style={{ width: 10, height: 10 }} /> : '▶'} 试跑
          </button>
          <button className="btn" style={{ fontSize: 12, padding: '2px 10px' }} disabled={running} onClick={onEdit}>编辑</button>
          <button className="btn" style={{ fontSize: 12, padding: '2px 8px', color: 'var(--error)' }} disabled={running} onClick={onRemove}>删除</button>
        </div>
      </div>
      {expanded && (
        <div style={{ borderTop: '1px solid var(--border, rgba(0,0,0,.08))', padding: '10px 14px', fontSize: 12.5 }}>
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', color: 'var(--text-dim)', marginBottom: 6 }}>
            <span>超时 {c.timeoutSec}s</span>
            {c.lastRunAt && <span>最近执行 {String(c.lastRunAt).replace('T', ' ').substring(0, 19)}</span>}
            {c.lastExitCode != null && <span>退出码 <b>{c.lastExitCode}</b></span>}
            {c.lastElapsedMs != null && <span>耗时 {fmtMs(c.lastElapsedMs)}</span>}
            <button className="btn" style={{ fontSize: 11.5, padding: '1px 8px' }} onClick={onHistory}>执行记录</button>
          </div>
          {c.lastReason && <div style={{ marginBottom: 6, color: c.lastVerdict === 'PASS' ? 'var(--text-dim)' : '#dc2626' }}>判定依据：{c.lastReason}</div>}
          {c.expect && <div style={{ marginBottom: 6 }}><b>断言：</b><pre style={preStyle}>{c.expect}</pre></div>}
          {c.notes && <div style={{ marginBottom: 6, color: 'var(--text-dim)' }}><b>自然语言预期/来源：</b>{c.notes}</div>}
          {c.lastOutputSummary ? (
            <div>
              <b>输出摘要（截断）：</b>
              <pre style={preStyle}>{c.lastOutputSummary}</pre>
              {c.lastOutputFile && <div style={{ color: 'var(--text-dim)', fontSize: 11.5 }}>完整输出：{c.lastOutputFile}</div>}
            </div>
          ) : (
            <div style={{ color: 'var(--text-dim)' }}>{c.landed ? '还没跑过——点「试跑」拿真实结果。' : '未落地：点「编辑」补出可执行 target 与断言。'}</div>
          )}
        </div>
      )}
    </div>
  )
}

const preStyle = { background: 'rgba(0,0,0,.05)', padding: '8px 10px', borderRadius: 6, fontSize: 11.5, fontFamily: 'var(--font-mono)', whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 220, overflow: 'auto', margin: '4px 0' }

function VerdictBadge({ verdict }) {
  if (!verdict) return <span className="hook-badge dim">未跑</span>
  const v = VERDICT[verdict]
  if (!v) return <span className="hook-badge dim">{verdict}</span>
  return (
    <span className="hook-badge" style={{ color: v.color, fontWeight: 700 }}>
      {verdict === 'RUNNING' && <span className="spinner" style={{ width: 9, height: 9, marginRight: 4 }} />}
      {v.label}
    </span>
  )
}

function fmtMs(ms) {
  if (ms == null) return '—'
  return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : ms + 'ms'
}

// ===== eval 编辑抽屉（新增 / 修改 / 启停都走这里） =====

function CaseEditor({ taskId, initial, onClose, onSaved, showToast }) {
  const [form, setForm] = useState(initial ? { ...emptyCaseForm, ...initial, timeoutSec: initial.timeoutSec || 120 } : emptyCaseForm)
  const [saving, setSaving] = useState(false)
  const kind = KINDS.find(k => k.value === form.kind) || KINDS[2]

  async function save() {
    if (!form.title.trim()) return showToast('请填写标题', 'error')
    setSaving(true)
    try {
      const url = initial ? `/api/eval-tasks/${taskId}/cases/${initial.id}` : `/api/eval-tasks/${taskId}/cases`
      const res = await fetch(url, {
        method: initial ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...form, title: form.title.trim(), timeoutSec: Number(form.timeoutSec) || 120 }),
      })
      const d = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(d.error || '保存失败')
      onSaved(d)
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <Surface kind="drawer" form label={initial ? '编辑 eval' : '新增 eval'} onClose={onClose}>
      <div className="modal-box" style={{ maxWidth: 680 }}>
        <div className="modal-title">{initial ? '编辑 eval' : '新增 eval'}</div>
        <div className="form-group">
          <label className="form-label">标题 *</label>
          <input className="form-input" value={form.title} placeholder="如：总价100/3晚逐晚累加精确"
            onChange={e => setForm(f => ({ ...f, title: e.target.value }))} />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 160px', gap: 12 }}>
          <div className="form-group">
            <label className="form-label">kind（判定来源类型）</label>
            <select className="form-input" value={form.kind}
              onChange={e => setForm(f => ({ ...f, kind: e.target.value, timeoutSec: defaultTimeout(e.target.value) }))}>
              {KINDS.map(k => <option key={k.value} value={k.value}>{k.label}</option>)}
            </select>
            <div style={{ fontSize: 11.5, color: 'var(--text-dim)', marginTop: 4 }}>{kind.hint}</div>
          </div>
          <div className="form-group">
            <label className="form-label">超时（秒，到点强杀）</label>
            <input className="form-input" type="number" min="1" max="86400" value={form.timeoutSec}
              onChange={e => setForm(f => ({ ...f, timeoutSec: e.target.value }))} />
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">执行 target *（完整命令行，Windows 走 cmd /c；留空 = 未落地，不可执行、不计入通过率）</label>
          <textarea className="form-input" rows={2} style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, resize: 'vertical' }}
            placeholder={TARGET_PLACEHOLDER[form.kind] || TARGET_PLACEHOLDER.script}
            value={form.target} onChange={e => setForm(f => ({ ...f, target: e.target.value }))} />
        </div>
        <div className="form-group">
          <label className="form-label">expect 输出断言（机器判定，逐行 AND；PASS/FAIL 只由退出码 + 这里的断言决定）</label>
          <textarea className="form-input" rows={3} style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, resize: 'vertical' }}
            placeholder={'stdout~Tests run: \\d+, Failed: 0'}
            value={form.expect} onChange={e => setForm(f => ({ ...f, expect: e.target.value }))} />
          <pre style={{ ...preStyle, maxHeight: 110 }}>{EXPECT_HELP}</pre>
        </div>
        <div className="form-group">
          <label className="form-label">自然语言预期 / 来源（草稿的原文摘录，翻译成可执行检查后仍保留作对照）</label>
          <textarea className="form-input" rows={2} style={{ fontSize: 12.5, resize: 'vertical' }}
            value={form.notes} onChange={e => setForm(f => ({ ...f, notes: e.target.value }))} />
        </div>
        <div style={{ display: 'flex', gap: 18, fontSize: 12.5, marginBottom: 10 }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer' }}
            data-tooltip="勾选后：当前无沙箱凭据（环境变量 EVAL_SANDBOX_CREDENTIALS）时，试跑记「跳过」并单列，不计入通过率" tabIndex={0}>
            <input type="checkbox" checked={form.requiresSandbox} onChange={e => setForm(f => ({ ...f, requiresSandbox: e.target.checked }))} />
            需要沙箱凭据
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: 5, cursor: 'pointer' }}>
            <input type="checkbox" checked={form.enabled} onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))} />
            启用（停用后不参与统计）
          </label>
        </div>
        <div className="surface-actions">
          <button className="btn" onClick={onClose}>取消</button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
        </div>
      </div>
    </Surface>
  )
}

function defaultTimeout(kind) {
  return kind === 'build' || kind === 'test' ? 600 : kind === 'static' ? 60 : 120
}

// ===== 任务设置（改绑定实例 / skill 路径） =====

function TaskSettings({ detail, onClose, onSaved, showToast }) {
  const occupied = useOccupied()
  const [instances, setInstances] = useState([])
  const [form, setForm] = useState({
    name: detail.name || '', supplierCode: detail.supplierCode || '',
    instanceId: detail.instanceId != null ? String(detail.instanceId) : '',
    reviewSkillPath: detail.reviewSkillPath || '', createSkillPath: detail.createSkillPath || '',
  })
  const [saving, setSaving] = useState(false)

  useEffect(() => { fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {}) }, [])

  async function save() {
    if (!form.name.trim()) return showToast('任务名不能为空', 'error')
    setSaving(true)
    try {
      const res = await fetch(`/api/eval-tasks/${detail.id}`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: form.name.trim(), supplierCode: form.supplierCode.trim(),
          instanceId: form.instanceId ? Number(form.instanceId) : null,
          reviewSkillPath: form.reviewSkillPath.trim(), createSkillPath: form.createSkillPath.trim(),
        }),
      })
      const d = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(d.error || '保存失败')
      onSaved(d)
    } catch (e) { showToast(e.message, 'error') } finally { setSaving(false) }
  }

  return (
    <Surface kind="dialog" form label="任务设置" onClose={onClose}>
      <div className="modal-box" style={{ maxWidth: 620 }}>
        <div className="modal-title">任务设置</div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <div className="form-group">
            <label className="form-label">任务名 *</label>
            <input className="form-input" value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          </div>
          <div className="form-group">
            <label className="form-label">供应商标识</label>
            <input className="form-input" value={form.supplierCode} onChange={e => setForm(f => ({ ...f, supplierCode: e.target.value }))} />
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">绑定实例（提供 eval 执行工作目录）</label>
          <select className="form-input" value={form.instanceId} onChange={e => setForm(f => ({ ...f, instanceId: e.target.value }))}>
            <option value="">（不绑定，在后端进程目录执行）</option>
            {instances.filter(i => i.enabled).map(i => {
              const occ = occupied[String(i.id)]
              return (
                <option key={i.id} value={i.id} disabled={!!occ}>
                  {occ ? `🔒 ${i.name || `#${i.id}`}（已被员工 ${occ.label} 独占绑定）` : `${i.name || `#${i.id}`}${i.claudeModel ? ` (${i.claudeModel})` : ''}`}
                </option>
              )
            })}
          </select>
        </div>
        <div className="form-group">
          <label className="form-label">review-supplier skill 路径</label>
          <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
            <span style={{ display: 'flex', alignItems: 'center', fontSize: 15, flexShrink: 0 }}>🧠</span>
            <input className="form-input" style={{ flex: 1 }} value={form.reviewSkillPath}
              onChange={e => setForm(f => ({ ...f, reviewSkillPath: e.target.value }))} />
          </div>
          <label className="form-label">create-supplier skill 路径</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <span style={{ display: 'flex', alignItems: 'center', fontSize: 15, flexShrink: 0 }}>🧠</span>
            <input className="form-input" style={{ flex: 1 }} value={form.createSkillPath}
              onChange={e => setForm(f => ({ ...f, createSkillPath: e.target.value }))} />
          </div>
        </div>
        <div className="surface-actions">
          <button className="btn" onClick={onClose}>取消</button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? '保存中…' : '保存设置'}</button>
        </div>
      </div>
    </Surface>
  )
}

// ===== 草稿确认抽屉（生成 → 用户勾选/改标题 → 导入） =====

function DraftsPanel({ taskId, onClose, onImported, showToast }) {
  const [drafts, setDrafts] = useState(null)
  const [error, setError] = useState('')
  const [importing, setImporting] = useState(false)

  useEffect(() => {
    fetch(`/api/eval-tasks/${taskId}/drafts/generate`, { method: 'POST' })
      .then(async r => {
        const d = await r.json().catch(() => ({}))
        if (!r.ok) throw new Error(d.error || '草稿生成失败')
        setDrafts((d.drafts || []).map((x, i) => ({ ...x, _i: i, _checked: !!x.selected })))
      })
      .catch(e => setError(e.message))
  }, [taskId])

  const checked = (drafts || []).filter(d => d._checked)

  async function doImport() {
    if (checked.length === 0) return showToast('没有勾选任何草稿', 'error')
    setImporting(true)
    try {
      const res = await fetch(`/api/eval-tasks/${taskId}/drafts/import`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ drafts: checked.map(({ _i, _checked, ...d }) => d) }),
      })
      const d = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(d.error || '导入失败')
      onImported(d.imported ?? checked.length)
    } catch (e) { showToast(e.message, 'error') } finally { setImporting(false) }
  }

  return (
    <Surface kind="drawer" label="从 review skill 生成初版 eval" onClose={onClose}>
      <div className="modal-box" style={{ maxWidth: 860 }}>
        <div className="modal-title">🧠 从 review skill 生成初版 eval（草稿）</div>
        <div style={{ fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 10 }}>
          只读 review-supplier 的 references/（「场景 → 关键预期」表 + 核心/工程检查表），逐条搬成草稿——
          这一步<b>只做翻译、不做判定</b>：导入的条目一律「未落地」（没有可执行 target），
          需要你补出命令与断言并<b>试跑真跑出结果</b>后，才会计入通过率。
        </div>
        {error && <div className="card" style={{ color: 'var(--error)', fontSize: 13 }}>{error}</div>}
        {drafts === null && !error && <div style={{ color: 'var(--text-dim)', fontSize: 13 }}>解析中…</div>}
        {drafts && (
          <>
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, fontSize: 12.5, flexWrap: 'wrap' }}>
              <button className="btn" style={{ fontSize: 12, padding: '2px 10px' }} onClick={selectTableRows}
                data-tooltip="勾上全部「场景 → 关键预期」表草稿（检查表条目量大，按需再勾）" tabIndex={0}>全选「场景表」</button>
              <button className="btn" style={{ fontSize: 12, padding: '2px 10px' }}
                onClick={() => setDrafts(rows => rows.map(x => ({ ...x, _checked: !x._checked })))}>反选</button>
              <button className="btn" style={{ fontSize: 12, padding: '2px 10px' }}
                onClick={() => setDrafts(rows => rows.map(x => ({ ...x, _checked: false })))}>全不选</button>
              <span style={{ color: 'var(--text-dim)' }}>共 {drafts.length} 条草稿，已勾 {checked.length} 条</span>
            </div>
            <div style={{ maxHeight: '52vh', overflow: 'auto' }}>
              {drafts.map(d => (
                <div key={d._i} className="card" style={{ padding: '8px 10px', marginBottom: 6 }}>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    <input type="checkbox" checked={d._checked} aria-label={`勾选草稿「${d.title}」`}
                      onChange={() => setDrafts(rows => rows.map(x => x._i === d._i ? { ...x, _checked: !x._checked } : x))} />
                    <select className="form-input" style={{ width: 110, padding: '2px 6px', fontSize: 12 }} value={d.kind}
                      onChange={e => setDrafts(rows => rows.map(x => x._i === d._i ? { ...x, kind: e.target.value } : x))}>
                      {KINDS.map(k => <option key={k.value} value={k.value}>{k.value}</option>)}
                    </select>
                    <input className="form-input" style={{ flex: 1, padding: '2px 8px', fontSize: 12.5 }} value={d.title}
                      onChange={e => setDrafts(rows => rows.map(x => x._i === d._i ? { ...x, title: e.target.value } : x))} />
                  </div>
                  <div style={{ fontSize: 11.5, color: 'var(--text-dim)', marginTop: 4, lineHeight: 1.6 }}>
                    <span className="hook-badge dim" style={{ marginRight: 6 }}>{d.source}</span>
                    {d.notes}
                  </div>
                </div>
              ))}
            </div>
            <div className="surface-actions" style={{ marginTop: 10 }}>
              <button className="btn" onClick={onClose}>取消</button>
              <button className="btn btn-primary" onClick={doImport} disabled={importing || checked.length === 0}>
                {importing ? '导入中…' : `导入勾选的 ${checked.length} 条草稿`}
              </button>
            </div>
          </>
        )}
      </div>
    </Surface>
  )

  function selectTableRows() {
    setDrafts(rows => rows.map(x => x.source && x.source.includes('report-and-validation') ? { ...x, _checked: true } : x))
  }
}
