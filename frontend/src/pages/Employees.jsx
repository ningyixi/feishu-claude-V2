import { useState, useEffect, useRef } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

// ===== 数字员工（侧边栏独立页面）：员工 CRUD / 需求下发自动并行 / 实例工位实时进度 / 主动上报聊天框 =====

export default function Employees() {
  const [empId, setEmpId] = useState(null)
  const [toast, setToast] = useState(null)

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  return (
    <div>
      {empId
        ? <EmployeeDetail empId={empId} onBack={() => setEmpId(null)} showToast={showToast} />
        : <EmployeeList onOpen={setEmpId} showToast={showToast} />}
      {toast && <div className={`toast toast-${toast.type}`}>{toast.msg}</div>}
    </div>
  )
}

// ===== 常量 =====


const DEMAND_STATUS = {
  QUEUED: { label: '排队中', color: '#6b7280', bg: 'rgba(107,114,128,0.10)' },
  RUNNING: { label: '执行中', color: '#2563eb', bg: 'rgba(37,99,235,0.10)' },
  DONE: { label: '已完成', color: '#16a34a', bg: 'rgba(22,163,74,0.10)' },
  FAILED: { label: '失败', color: '#dc2626', bg: 'rgba(220,38,38,0.10)' },
  CANCELLED: { label: '已取消', color: '#d97706', bg: 'rgba(217,119,6,0.10)' },
  INTERRUPTED: { label: '重启中断', color: '#9333ea', bg: 'rgba(147,51,234,0.10)' },
}

const EMP_EMOJIS = ['👤', '👨‍💻', '👩‍💻', '🧑‍💼', '👷', '🕵️', '🧙', '🦸', '🤖', '🐝', '🦾', '🎯']

/** 数字员工可授予的创建权限（与后端 EmployeeService.PERM_LABELS 对齐） */
const EMP_PERMS = [
  { key: 'e2e', icon: '🧪', label: '创建 E2E 测试任务' },
  { key: 'hook', icon: '🪝', label: '创建 Hook 任务' },
  { key: 'supplier', icon: '🏭', label: '创建供应商工作' },
]

/** 主动上报消息类型（聊天框气泡样式） */
const MSG_TYPE = {
  REPORT: { icon: '✅', label: '完成汇报', color: '#16a34a' },
  SUPPORT: { icon: '🙋', label: '需要你的支持', color: '#d97706' },
  FAILED: { icon: '❌', label: '执行失败', color: '#dc2626' },
  INTERRUPTED: { icon: '⚠️', label: '重启中断', color: '#9333ea' },
  STUCK: { icon: '⏳', label: '排队卡住', color: '#b45309' },
  PERIODIC: { icon: '📊', label: '定期汇报', color: '#2563eb' },
  CHAT: { icon: '💬', label: '回复', color: '#2563eb' },
  E2E: { icon: '🧪', label: 'E2E 结果', color: '#0891b2' },
  SHELL: { icon: '🖥', label: '命令执行', color: '#475569' },
}

/** 消息里的 URL 渲染成可点链接（SHELL 结果里的 PR 地址等） */
function linkify(text) {
  const parts = String(text || '').split(/(https?:\/\/[^\s"'<>]+)/g)
  return parts.map((p, i) => p.startsWith('http')
    ? <a key={i} href={p} target="_blank" rel="noreferrer">{p}</a>
    : p)
}

function fmtDur(ms) {
  if (!ms || ms <= 0) return ''
  const s = ms / 1000
  if (s < 60) return Math.round(s) + 's'
  if (s < 3600) return Math.floor(s / 60) + 'm' + Math.round(s % 60) + 's'
  return Math.floor(s / 3600) + 'h' + Math.floor((s % 3600) / 60) + 'm'
}
function fmtTime(t) { return t ? String(t).replace('T', ' ').substring(5, 16) : '' }

function EmployeeList({ onOpen, showToast }) {
  const [employees, setEmployees] = useState([])
  const [creating, setCreating] = useState(false)

  const load = () => fetch('/api/employees').then(r => r.json()).then(setEmployees).catch(() => {})
  useEffect(() => {
    load()
    const t = setInterval(load, 10000)
    return () => clearInterval(t)
  }, [])

  async function remove(e, ev) {
    ev.stopPropagation()
    if (!confirm(`删除数字员工「${e.name}」？其全部需求记录将一并删除（运行中的会先停止）。`)) return
    await fetch(`/api/employees/${e.id}`, { method: 'DELETE' })
    showToast('已删除员工', 'success')
    load()
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.2rem' }}>
        <div>
          <h1 className="page-title" style={{ margin: 0 }}>👔 数字员工</h1>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 4 }}>
            创建数字员工、绑定可操作的实例；下发需求后自动并行开工，随时查看进度与「实例 ↔ 需求」映射
          </div>
        </div>
        <button className="btn btn-primary" onClick={() => setCreating(true)}>+ 新建员工</button>
      </div>

      {employees.length === 0 && (
        <div className="card" style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
          还没有数字员工。点「+ 新建员工」招聘第一位虚拟同事。
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: 14 }}>
        {employees.map(e => (
          <div key={e.id} className="emp-card" onClick={() => onOpen(e.id)}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <div className="emp-avatar">{e.emoji || '👤'}</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 15, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 6 }}>
                  {e.name}
                  {(e.unreadCount || 0) > 0 && <span className="unread-dot" title={`${e.unreadCount} 条未读上报`}>{e.unreadCount}</span>}
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--text-dim)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  title={e.roleDesc}>{e.roleDesc || '未填职责描述'}</div>
              </div>
              <button className="btn" style={{ fontSize: 12, padding: '2px 10px', color: 'var(--error)' }}
                onClick={ev => remove(e, ev)}>删除</button>
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 5, marginTop: 10 }}>
              <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'center' }}>
                <span className="emp-row-label">实例</span>
                {(e.instances || []).map(i => <span key={i.id} className="hook-badge dim">📡 {i.name}</span>)}
                {(e.instances || []).length === 0 && <span className="hook-badge dim">⚠ 未绑定</span>}
              </div>
              <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'center' }}>
                <span className="emp-row-label">权限</span>
                {(e.permissions || []).length === 0 && <span className="hook-badge dim">无</span>}
                {(e.permissions || []).map(p => {
                  const pm = EMP_PERMS.find(x => x.key === p)
                  return <span key={p} className="hook-badge" title={`已授权：${pm?.label || p}`}>{pm?.icon || '🔓'} {pm ? pm.label.replace('创建 ', '') : p}</span>
                })}
                {!!e.workSpec && <span className="hook-badge" title={e.workSpec}>📜 SPEC</span>}
                {!!e.autoPr && <span className="hook-badge" title="E2E 完结后自动执行 git qpr">🚀 自动PR</span>}
              </div>
            </div>
            <div style={{ display: 'flex', gap: 6, marginTop: 10, flexWrap: 'wrap', alignItems: 'center' }}>
              <span className="emp-stat" style={{ color: '#2563eb', background: 'rgba(37,99,235,0.10)' }}>▶ 进行 {e.stats?.running || 0}</span>
              <span className="emp-stat" style={{ color: '#6b7280', background: 'rgba(107,114,128,0.10)' }}>⏳ 排队 {e.stats?.queued || 0}</span>
              <span className="emp-stat" style={{ color: '#16a34a', background: 'rgba(22,163,74,0.10)' }}>✓ 完成 {e.stats?.done || 0}</span>
              {(e.stats?.failed || 0) > 0 && <span className="emp-stat" style={{ color: '#dc2626', background: 'rgba(220,38,38,0.10)' }}>✗ 失败 {e.stats.failed}</span>}
            </div>
            {(e.runningSummary || []).length > 0 && (
              <div style={{ fontSize: 12.5, color: '#2563eb', marginTop: 8, lineHeight: 1.7 }}>
                {e.runningSummary.map(r => (
                  <div key={r.demandId}>🔨 {r.instanceName}: {r.title}</div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      {creating && <EmployeeForm initial={null} onCancel={() => setCreating(false)}
        onDone={() => { setCreating(false); load() }} showToast={showToast} />}
    </div>
  )
}

function EmployeeForm({ initial, onCancel, onDone, showToast }) {
  const [name, setName] = useState(initial?.name || '')
  const [emoji, setEmoji] = useState(initial?.emoji || '👤')
  const [roleDesc, setRoleDesc] = useState(initial?.roleDesc || '')
  const [bound, setBound] = useState((initial?.instanceIds || []).map(String))
  const [perms, setPerms] = useState(initial?.permissions || [])
  const [reportIntervalMin, setReportIntervalMin] = useState(initial?.reportIntervalMin || 0)
  const [workSpec, setWorkSpec] = useState(initial?.workSpec || '')
  const [autoPr, setAutoPr] = useState(!!initial?.autoPr)
  const [notifyInstanceId, setNotifyInstanceId] = useState(initial?.notifyInstanceId || 0)
  const [specEditing, setSpecEditing] = useState(false)   // SPEC 大白框（全屏编辑）
  const [instances, setInstances] = useState([])
  const [occupied, setOccupied] = useState({})   // String(instId) → 占用员工标签（编辑自己时排除自己）
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    fetch('/api/instances').then(r => r.json())
      .then(list => setInstances(Array.isArray(list) ? list.filter(i => i.enabled) : []))
      .catch(() => {})
    // 实例独占绑定：已被其他员工绑定的实例置灰（要先解绑才能分配）
    fetch('/api/employees').then(r => r.json()).then(emps => {
      const map = {}
      ;(emps || []).forEach(emp => {
        if (initial && emp.id === initial.id) return
        ;(emp.instances || []).forEach(i => { map[String(i.id)] = `${emp.emoji || '👔'} ${emp.name}` })
      })
      setOccupied(map)
    }).catch(() => {})
  }, [])

  function toggle(id) {
    setBound(b => b.includes(String(id)) ? b.filter(x => x !== String(id)) : [...b, String(id)])
  }
  function togglePerm(key) {
    setPerms(p => p.includes(key) ? p.filter(x => x !== key) : [...p, key])
  }

  async function save() {
    if (!name.trim()) { showToast('请填写员工名称', 'error'); return }
    setSaving(true)
    try {
      const res = await fetch(initial ? `/api/employees/${initial.id}` : '/api/employees', {
        method: initial ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name.trim(), emoji, roleDesc, instanceIds: bound.map(Number), permissions: perms, reportIntervalMin, workSpec, autoPr, notifyInstanceId: Number(notifyInstanceId) || 0 }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || '保存失败')
      showToast(initial ? '已更新员工信息' : '数字员工已入职 🎉', 'success')
      onDone(data.id)
    } catch (e) {
      showToast(e.message, 'error')
    } finally { setSaving(false) }
  }

  return (
    <div className="modal-mask" onClick={onCancel}>
      <div className="modal-box" style={{ maxWidth: 640 }} onClick={ev => ev.stopPropagation()}>
        <div className="modal-title">{initial ? `✏️ 编辑员工 · ${initial.emoji || ''} ${initial.name}` : '👔 新建数字员工'}</div>
        <div className="form-group">
          <label className="form-label">名称 *</label>
          <input className="form-input" value={name} onChange={e => setName(e.target.value)}
            placeholder="例如：小王 / 对接专员一号" />
        </div>
        <div className="form-group">
          <label className="form-label">头像</label>
          <div className="emoji-pick">
            {EMP_EMOJIS.map(em => (
              <span key={em} className={'emoji-opt' + (emoji === em ? ' active' : '')} onClick={() => setEmoji(em)}>{em}</span>
            ))}
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">职责描述 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>作为员工人设注入每次需求</span></label>
          <textarea className="form-input" rows={3} style={{ resize: 'vertical' }} value={roleDesc}
            onChange={e => setRoleDesc(e.target.value)}
            placeholder="例如：负责酒店供应商 API 对接——阅读接口文档、编写对接代码、自测并输出对接报告" />
        </div>
        <div className="form-group">
          <label className="form-label">可操作的实例 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>多选；需求会在这些实例上自动并行（每实例同一时刻一个）</span></label>
          {instances.length === 0 && <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>暂无可用实例（先到 📡 实例页创建并启用）</div>}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {instances.map(i => {
              const occ = occupied[String(i.id)]
              const checked = bound.includes(String(i.id))
              return (
                <label key={i.id} style={{
                  display: 'flex', alignItems: 'center', gap: 8, fontSize: 13,
                  cursor: occ && !checked ? 'not-allowed' : 'pointer', opacity: occ && !checked ? 0.55 : 1,
                }}>
                  <input type="checkbox" disabled={!!occ && !checked} checked={checked} onChange={() => toggle(i.id)} />
                  📡 {i.name || `#${i.id}`}
                  {occ && !checked
                    ? <span style={{ fontSize: 12, color: '#d97706' }}>👔 已被「{occ}」绑定，需先解绑</span>
                    : <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>{i.claudeWorkingDir || i.shellWorkingDir || ''}</span>}
                </label>
              )
            })}
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6, lineHeight: 1.6 }}>
            🔒 绑定时实例自动开启<b>严格沙箱</b>（白名单命令 + 文件圈禁在工作目录 + 忽略仓库自带提权配置），解绑自动恢复全权限；
            这是员工工作机的默认安全档，admin 可在 📡 实例页手动调整。
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">📣 飞书通知实例
            <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>
              员工的上报/聊天回复经哪个实例的飞书机器人推给你（从已绑定实例中选；该实例需配置「授权用户 Open ID」且保持连接）
            </span>
          </label>
          <select className="form-input" value={String(notifyInstanceId || 0)}
            onChange={e => setNotifyInstanceId(Number(e.target.value))}>
            <option value={0}>🔄 自动（优先需求执行实例，其次按绑定顺序）</option>
            {bound.map(id => {
              const i = instances.find(x => String(x.id) === id)
              return (
                <option key={id} value={id}>
                  📡 {i?.name || `#${id}`}{i && !i.authorizedOpenId ? '（⚠ 未配授权用户，选了也发不出）' : ''}
                </option>
              )
            })}
          </select>
        </div>
        <div className="form-group">
          <label className="form-label">系统权限 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>授权后员工执行需求时可在本系统创建对应对象（服务端强制校验，未授权会被拒绝）</span></label>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {EMP_PERMS.map(p => (
              <label key={p.key} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, cursor: 'pointer' }}>
                <input type="checkbox" checked={perms.includes(p.key)} onChange={() => togglePerm(p.key)} />
                {p.icon} {p.label}
              </label>
            ))}
          </div>
        </div>
        <div className="form-group">
          <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 13, cursor: 'pointer' }}>
            <input type="checkbox" style={{ marginTop: 3 }} checked={autoPr} onChange={e => setAutoPr(e.target.checked)} />
            <span>🚀 E2E 测试完结后自动提交 PR（<code>git qpr</code>）
              <span style={{ display: 'block', fontSize: 12, color: 'var(--text-dim)', lineHeight: 1.6 }}>
                该员工创建的 E2E 任务跑完后，系统自动在代码实例上执行 git qpr（你预置的全局 alias：自动带 reviewer、--no-edit、--assume-yes，全程无需交互；确定性执行、不经 Claude），
                等它跑完后提取终端输出里的 Code Review 链接，以该员工名义经聊天框 + 飞书发给你。
              </span>
            </span>
          </label>
        </div>
        <div className="form-group">
          <label className="form-label">定期汇报 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>有进行中/排队的工作时，按间隔主动汇报进度（飞书 + 面板红点）；任务完成/失败/卡住时始终会立即上报</span></label>
          <select className="form-input" value={reportIntervalMin} onChange={e => setReportIntervalMin(Number(e.target.value))}>
            <option value={0}>关闭（只在完成/失败/卡住时上报）</option>
            <option value={30}>每 30 分钟</option>
            <option value={60}>每 1 小时</option>
            <option value={120}>每 2 小时</option>
            <option value={240}>每 4 小时</option>
          </select>
        </div>
        <div className="form-group">
          <label className="form-label">📜 工作 SPEC
            <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>
              （写代码要有 spec，员工干活也要有"干活的 spec"——用这份文档规范该员工的行为：飞书链接怎么处理、代码/提交规范、流程要求、汇报格式…
              它会注入这个员工的每次任务与聊天，优先级高于系统默认流程）
            </span>
          </label>
          <div className="spec-preview" onClick={() => setSpecEditing(true)} title="点击打开大白框编辑">
            {workSpec || '（未填写——点击编辑，像写 txt 文档一样，把这个员工在你这里干活要遵守的规矩写下来）'}
          </div>
          <div style={{ marginTop: 6, display: 'flex', alignItems: 'center', gap: 8 }}>
            <button className="btn" style={{ fontSize: 12, padding: '3px 12px' }} onClick={() => setSpecEditing(true)}>✏️ 编辑 SPEC</button>
            {workSpec && <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>{workSpec.length} 字 · 每次任务/聊天自动注入</span>}
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn" onClick={onCancel}>取消</button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
        </div>
      </div>

      {/* SPEC 大白框：全屏级编辑弹层（嵌套在表单弹窗之上，像编辑一份 txt 工作规范） */}
      {specEditing && (
        <div className="modal-mask" onClick={e => { e.stopPropagation(); setSpecEditing(false) }}>
          <div className="modal-box spec-modal" onClick={e => e.stopPropagation()}>
            <div className="modal-title">📜 {name || '员工'} 的工作 SPEC</div>
            <div style={{ fontSize: 12.5, color: 'var(--text-dim)', marginBottom: 8, lineHeight: 1.7 }}>
              写代码要有 spec，员工干活也要有"干活的 spec"——这里写的每一条规范，它每次执行任务和聊天时都会严格遵守（优先级高于系统默认流程）。
              写完点「完成」回到表单，再点「保存」生效。
            </div>
            <textarea className="spec-editor" value={workSpec} onChange={e => setWorkSpec(e.target.value)}
              placeholder={'把这个员工在你这里干活要遵守的规矩写下来，例如：\n\n- 需求里的飞书链接（*.feishu.cn）必须用 lark-cli 解析，禁止直接 curl\n- 开发完成后必须创建 E2E 测试任务自测，并在群聊里发言说明测什么、怎么判定\n- 提交信息用 conventional commits 格式（feat: / fix: / ...）\n- 只允许修改 xx 模块的代码，跨模块改动先在汇报里说明\n- 汇报一律中文、分点、先结论后细节\n- 遇到不确定的业务规则，写进「需要老板支持」，不要自己拍板'} />
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
              <span style={{ flex: 1, fontSize: 12, color: 'var(--text-dim)', alignSelf: 'center' }}>{workSpec.length} 字</span>
              <button className="btn btn-primary" onClick={() => setSpecEditing(false)}>完成</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function EmployeeDetail({ empId, onBack, showToast }) {
  const [data, setData] = useState(null)         // {employee, demands, messages, chatProgress}
  const [editing, setEditing] = useState(false)
  const [dispatching, setDispatching] = useState(false)
  const [expanded, setExpanded] = useState({})
  const [replyTo, setReplyTo] = useState(null)   // 带着某条需求的上下文追问

  const load = () => fetch(`/api/employees/${empId}`)
    .then(async r => r.ok ? r.json() : null)
    .then(d => { if (d) setData(d) })
    .catch(() => {})

  // 实时执行流：SSE 推送每一步（思考/工具调用/输出/完成/错误）；状态变化时防抖刷新详情
  const { timelines, connected } = useEmployeeEvents(empId, load)

  useEffect(() => {
    load()
    // SSE 已覆盖实时性，轮询只做兜底（断线时 5s，连上后 20s）
    const t = setInterval(load, connected ? 20000 : 5000)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [empId, connected])

  // 打开了该员工（能看到聊天框）→ 未读全部置已读，侧边栏/卡片红点清零
  useEffect(() => {
    if (data?.unreadCount > 0) {
      fetch(`/api/employees/${empId}/messages/read`, { method: 'POST' }).then(load).catch(() => {})
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data?.unreadCount])

  async function removeEmployee() {
    if (!confirm(`删除数字员工「${data.employee.name}」？全部需求记录将一并删除（运行中的会先停止）。`)) return
    await fetch(`/api/employees/${empId}`, { method: 'DELETE' })
    showToast('已删除员工', 'success')
    onBack()
  }

  async function act(demandId, action) {
    try {
      const res = await fetch(`/api/employees/demands/${demandId}/${action}`, { method: 'POST' })
      const d = await res.json()
      if (!res.ok) throw new Error(d.error || '操作失败')
      showToast(action === 'cancel' ? '已请求取消' : '已重新排队', 'success')
      load()
    } catch (e) { showToast(e.message, 'error') }
  }

  if (!data) return <div className="card" style={{ color: 'var(--text-dim)', padding: '2rem', textAlign: 'center' }}>加载中…</div>

  const emp = data.employee
  const demands = data.demands || []
  const running = demands.filter(d => d.status === 'RUNNING')
  const queuedCount = demands.filter(d => d.status === 'QUEUED').length
  const sorted = [...demands].reverse()   // 后端按创建序 → 倒序最新在前
  // Shell 模式默认执行实例：最近执行过需求的实例 > 第一个绑定实例
  const defaultShellInstance = (() => {
    for (let i = demands.length - 1; i >= 0; i--) if (demands[i].instanceId) return demands[i].instanceId
    return emp.instances?.[0]?.id || ''
  })()

  return (
    <div className="emp-detail">
      {/* 头部 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 14, flexWrap: 'wrap' }}>
        <button className="btn" onClick={onBack}>← 返回</button>
        <div className="emp-avatar">{emp.emoji || '👤'}</div>
        <div>
          <div style={{ fontSize: 17, fontWeight: 700 }}>{emp.name}</div>
          <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>{emp.roleDesc || '未填职责描述'}</div>
        </div>
        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
          {(emp.instances || []).map(i => <span key={i.id} className="hook-badge dim">📡 {i.name}</span>)}
          {(emp.permissions || []).map(p => {
            const pm = EMP_PERMS.find(x => x.key === p)
            return <span key={p} className="hook-badge" title={`已授权：${pm?.label || p}`}>{pm?.icon || '🔓'} {pm ? pm.label.replace('创建 ', '') : p}</span>
          })}
          {!!emp.workSpec && <span className="hook-badge" title={emp.workSpec}>📜 SPEC {emp.workSpec.length}字</span>}
          {!!emp.autoPr && <span className="hook-badge" title="E2E 完结后自动执行 git qpr 并上报 Code Review 链接">🚀 自动PR</span>}
          {!!emp.notifyInstanceId && (
            <span className="hook-badge dim" title="飞书通知实例：员工的消息经它的机器人推给你">
              📣 {(emp.instances || []).find(i => i.id === emp.notifyInstanceId)?.name || `#${emp.notifyInstanceId}`}
            </span>
          )}
        </div>
        <div style={{ flex: 1 }} />
        <button className="btn" onClick={() => setEditing(true)}>✏️ 编辑</button>
        <button className="btn" style={{ color: 'var(--error)' }} onClick={removeEmployee}>删除</button>
        <button className="btn btn-primary" onClick={() => setDispatching(true)}>📮 下发需求</button>
      </div>

      <div className="emp-detail-grid">
      <div className="emp-col-left">
      {/* 💬 双向聊天框：员工上报 + 老板直接对话派活 + 🖥 Shell 直通命令（打开即已读） */}
      <EmpMsgPanel empId={empId} messages={data.messages} unreadCount={data.unreadCount}
        chatProgress={data.chatProgress} shellProgress={data.shellProgress} emp={emp}
        chatTimeline={timelines.chat}
        defaultShellInstance={defaultShellInstance} replyTo={replyTo}
        clearReplyTo={() => setReplyTo(null)} showToast={showToast} />
      </div>
      <div className="emp-col-right">

      {/* 实例工位：哪个实例正在做哪个需求 */}
      <div className="card" style={{ padding: '14px 16px' }}>
        <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 10 }}>
          🖥 实例工位
          <span className={'live-dot' + (connected ? ' on' : '')} title={connected ? '实时执行流已连接' : '实时流未连接，轮询兜底中'}>
            {connected ? '● 实时' : '○ 轮询'}
          </span>
          {queuedCount > 0 && <span style={{ fontWeight: 400, color: '#d97706', fontSize: 12.5, marginLeft: 8 }}>⏳ {queuedCount} 个需求排队中</span>}
          <span style={{ fontWeight: 400, color: 'var(--text-dim)', fontSize: 12.5, marginLeft: 8 }}>每实例同一时刻做一个需求</span>
        </div>
        {(emp.instances || []).length === 0 && (
          <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>⚠ 该员工还没绑定实例，点右上「✏️ 编辑」绑定。</div>
        )}
        {(emp.instances || []).map(inst => {
          const d = running.find(x => x.instanceId === inst.id)
          const waiting = demands.filter(x => x.status === 'QUEUED' && x.requestedInstanceId === inst.id)
          return (
            <div key={inst.id} className={'emp-station' + (d ? ' busy' : '')}>
              <div style={{ minWidth: 140 }}>
                <div style={{ fontSize: 12.5, fontWeight: 700 }}>📡 {inst.name}</div>
                <div style={{ fontSize: 12, color: d ? '#2563eb' : 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: 4, marginTop: 2 }}>
                  {d ? <><span className="spinner" style={{ width: 9, height: 9 }} /> 正在干活</> : '💤 等待需求'}
                </div>
              </div>
              {d ? (
                <div className="progress-box">
                  <div style={{ fontWeight: 600 }}>{d.title}</div>
                  {d.progress ? <ProgressView p={d.progress} />
                    : <div style={{ color: 'var(--text-dim)', fontSize: 12.5 }}>正在启动…</div>}
                  <LiveTimeline events={timelines['d' + d.id]} running />
                </div>
              ) : (
                <div style={{ fontSize: 12.5, color: 'var(--text-dim)', alignSelf: 'center' }}>
                  {waiting.length > 0 ? `⏳ 待派: ${waiting.map(w => w.title).join('、')}` : '—'}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* 需求列表（含实例映射） */}
      <div className="card" style={{ padding: '14px 16px' }}>
        <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 10 }}>📋 需求（{demands.length}）</div>
        {sorted.length === 0 && (
          <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>还没有需求。点「📮 下发需求」给员工派活。</div>
        )}
        {sorted.map(d => {
          const st = DEMAND_STATUS[d.status] || DEMAND_STATUS.QUEUED
          const open = expanded[d.id]
          return (
            <div key={d.id} className="demand-row">
              <div className="demand-head" onClick={() => setExpanded(x => ({ ...x, [d.id]: !x[d.id] }))}>
                <span className="demand-status" style={{ color: st.color, background: st.bg }}>
                  {d.status === 'RUNNING' && <span className="spinner" style={{ width: 9, height: 9 }} />}
                  {st.label}
                </span>
                <b style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.title}</b>
                {d.instanceName && <span className="hook-badge dim" title="执行实例（映射关系）">📡 {d.instanceName}</span>}
                <span style={{ fontSize: 12, color: 'var(--text-dim)', whiteSpace: 'nowrap' }}>
                  {fmtTime(d.createdAt)}{d.elapsedMs > 0 ? ` · ${fmtDur(d.elapsedMs)}` : ''}
                </span>
                <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>{open ? '▲' : '▼'}</span>
              </div>
              {open && (
                <div className="demand-body">
                  <div style={{ marginBottom: 8 }}>
                    <b style={{ fontSize: 12.5, color: 'var(--text-dim)' }}>需求内容</b>
                    <div style={{ whiteSpace: 'pre-wrap', marginTop: 2 }}>{d.requirement}</div>
                  </div>
                  {timelines['d' + d.id]?.length > 0 ? (
                    <div style={{ marginBottom: 8 }}>
                      <b style={{ fontSize: 12.5, color: 'var(--text-dim)' }}>执行过程</b>
                      <LiveTimeline events={timelines['d' + d.id]} running={d.status === 'RUNNING'} />
                    </div>
                  ) : ['DONE', 'FAILED'].includes(d.status) && <SavedTimeline demandId={d.id} />}
                  {d.errorMessage && (
                    <div style={{ color: 'var(--error)', marginBottom: 8, fontSize: 12 }}>⚠ {d.errorMessage}</div>
                  )}
                  {d.result && (
                    <div>
                      <b style={{ fontSize: 12.5, color: 'var(--text-dim)' }}>完成汇报</b>
                      <div className="md-body" style={{ marginTop: 2 }}>
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{d.result}</ReactMarkdown>
                      </div>
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: 8, marginTop: 10, justifyContent: 'flex-end' }}>
                    <button className="btn" style={{ fontSize: 12.5, padding: '3px 12px' }}
                      onClick={() => setReplyTo({ demandId: d.id, title: d.title })}>💬 追问</button>
                    {(d.status === 'QUEUED' || d.status === 'RUNNING') && (
                      <button className="btn" style={{ fontSize: 12.5, padding: '3px 12px', color: '#d97706' }}
                        onClick={() => act(d.id, 'cancel')}>
                        {d.status === 'RUNNING' ? '⏹ 取消（已请求停止）' : '取消'}
                      </button>
                    )}
                    {['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(d.status) && (
                      <button className="btn" style={{ fontSize: 12.5, padding: '3px 12px' }} onClick={() => act(d.id, 'retry')}>🔄 重试</button>
                    )}
                  </div>
                </div>
              )}
            </div>
          )
        })}
      </div>
      </div>
      </div>

      {editing && <EmployeeForm initial={emp} onCancel={() => setEditing(false)}
        onDone={() => { setEditing(false); load() }} showToast={showToast} />}
      {dispatching && <DemandForm emp={emp} onCancel={() => setDispatching(false)}
        onDone={() => { setDispatching(false); load() }} showToast={showToast} />}
    </div>
  )
}

/** 💬 员工主动上报聊天框（新消息在下，自动滚到底；打开详情页即全部已读） */
function EmpMsgPanel({ empId, messages, unreadCount, chatProgress, shellProgress, chatTimeline, emp, defaultShellInstance, replyTo, clearReplyTo, showToast }) {
  const boxRef = useRef(null)
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  const [mode, setMode] = useState('chat')            // chat | shell
  const [shellInstance, setShellInstance] = useState('')
  // 只在用户停留在底部附近时自动跟随；往上翻看历史时不被拽回去（自己发消息时强制回到底部）
  const stickRef = useRef(true)
  useEffect(() => {
    if (boxRef.current && stickRef.current) boxRef.current.scrollTop = boxRef.current.scrollHeight
  }, [messages?.length, chatProgress?.phase, chatProgress?.answerTail, shellProgress?.elapsedMs, chatTimeline?.length])

  async function send() {
    const t = text.trim()
    if (!t || sending) return
    setSending(true)
    try {
      const url = mode === 'shell' ? `/api/employees/${empId}/shell` : `/api/employees/${empId}/chat`
      const payload = mode === 'shell'
        ? { command: t, instanceId: Number(shellInstance || defaultShellInstance) || null }
        : { text: t, demandId: replyTo?.demandId || null }
      const res = await fetch(url, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      const d = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(d.error || '发送失败')
      setText('')
      stickRef.current = true
      if (mode === 'chat' && replyTo) clearReplyTo()
    } catch (e) { showToast(e.message, 'error') } finally { setSending(false) }
  }

  const busy = !!chatProgress || !!shellProgress
  return (
    <div className="card emp-chat-card" style={{ padding: '14px 16px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 13, fontWeight: 700 }}>💬 聊天框</span>
        {/* 模式切换：对话（经 Claude，可干活）/ Shell（直通命令行，确定性执行） */}
        <span className="mode-switch">
          <span className={'mode-opt' + (mode === 'chat' ? ' active' : '')} onClick={() => setMode('chat')}>💬 对话</span>
          <span className={'mode-opt' + (mode === 'shell' ? ' active' : '')} onClick={() => setMode('shell')}>🖥 Shell</span>
        </span>
        <span style={{ fontWeight: 400, color: 'var(--text-dim)', fontSize: 12.5 }}>
          {mode === 'shell'
            ? '命令在实例工作目录直接执行（不经 Claude），交互提问自动答 y，输出里的 URL 自动提取'
            : '可以直接对话派小活（正式大任务建议走 📮 下发需求，有状态跟踪）'}
          {(messages?.length || 0) > 0 && <> · {messages.length} 条{unreadCount > 0 ? ` · ${unreadCount} 条未读` : ''}</>}
        </span>
      </div>
      {(messages?.length > 0 || busy) && (
        <div className="emp-msgs" ref={boxRef}
          onScroll={e => { const b = e.currentTarget; stickRef.current = b.scrollHeight - b.scrollTop - b.clientHeight < 60 }}>
          {[...(messages || [])].reverse().map(m => m.sender === 'boss'
            ? <BossBubble key={m.id} m={m} />
            : <MsgBubble key={m.id} m={m} emp={emp} />)}
          {shellProgress && (
            <div className="emp-msg">
              <div className="emp-msg-avatar">🖥</div>
              <div className="emp-msg-bubble" style={{ borderLeftColor: '#475569' }}>
                <div className="emp-msg-head">
                  <b style={{ color: '#475569' }}>⚙️ 正在执行命令…</b>
                  {shellProgress.instanceName && <span>📡 {shellProgress.instanceName}</span>}
                  <span>{fmtDur(shellProgress.elapsedMs)}</span>
                </div>
                <div className="emp-msg-content" style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>$ {shellProgress.command}</div>
              </div>
            </div>
          )}
          {!shellProgress && chatProgress && (
            <div className="emp-msg">
              <div className="emp-msg-avatar">{emp?.emoji || '👤'}</div>
              <div className="emp-msg-bubble" style={{ borderLeftColor: '#2563eb' }}>
                <div className="emp-msg-head">
                  <b style={{ color: '#2563eb' }}>✍️ 正在处理你的消息…</b>
                  {chatProgress.instanceName && <span>📡 {chatProgress.instanceName}</span>}
                </div>
                <ProgressView p={chatProgress} />
                <LiveTimeline events={chatTimeline} running />
              </div>
            </div>
          )}
        </div>
      )}
      {!(messages?.length > 0) && !busy && (
        <div className="emp-msgs" style={{ fontSize: 12, color: 'var(--text-dim)', padding: '6px 0' }}>
          员工的上报（完成/失败/卡住/定期汇报）会出现在这里。
        </div>
      )}
      <div className="emp-chat-input">
        {mode === 'shell' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 12.5, color: 'var(--text-dim)' }}>执行实例：</span>
            <select className="form-input" style={{ width: 'auto', padding: '3px 8px', fontSize: 12 }}
              value={shellInstance || String(defaultShellInstance || '')}
              onChange={e => setShellInstance(e.target.value)}>
              {(emp?.instances || []).map(i => <option key={i.id} value={i.id}>📡 {i.name}</option>)}
            </select>
            <span style={{ fontSize: 12, color: '#b45309' }} title="Shell 是面板管理员的直通通道，以服务端进程权限执行，不受 Claude 沙箱档位约束">
              ⚠ admin 直通通道（不经沙箱）
            </span>
          </div>
        )}
        {replyTo && mode === 'chat' && (
          <div className="reply-chip">
            💬 追问需求「{replyTo.title}」
            <span onClick={clearReplyTo} style={{ cursor: 'pointer', marginLeft: 6, fontWeight: 700 }}>✕</span>
          </div>
        )}
        <div style={{ display: 'flex', gap: 8 }}>
          <input className="form-input" style={{ flex: 1, padding: '7px 10px', fontFamily: mode === 'shell' ? 'var(--font-mono)' : undefined }}
            placeholder={busy
              ? (shellProgress ? '命令执行中，稍候…' : '员工正在回复，稍候…')
              : (mode === 'shell'
                ? '输入命令直接执行，如 git qpr（Enter 发送，120s 超时）'
                : '对员工说点什么，可以直接安排小事（Enter 发送）')}
            value={text} disabled={busy || sending}
            onChange={e => setText(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }} />
          <button className="btn btn-primary" onClick={send} disabled={!text.trim() || busy || sending}>
            {sending ? '发送中…' : mode === 'shell' ? '▶ 执行' : '发送'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** 老板消息气泡（右侧蓝底） */
function BossBubble({ m }) {
  return (
    <div className="emp-msg boss">
      <div className="emp-msg-avatar" style={{ background: 'rgba(37,99,235,0.15)' }}>👑</div>
      <div className="emp-msg-bubble boss-bubble">
        <div className="emp-msg-head">
          <span>你</span><span>{fmtTime(m.createdAt)}</span>
        </div>
        <div className="emp-msg-content">{m.content}</div>
      </div>
    </div>
  )
}

function MsgBubble({ m, emp }) {
  const t = MSG_TYPE[m.type] || MSG_TYPE.REPORT
  return (
    <div className={'emp-msg' + (m.read ? '' : ' unread')}>
      <div className="emp-msg-avatar">{emp?.emoji || '👤'}</div>
      <div className="emp-msg-bubble" style={{ borderLeftColor: t.color }}>
        <div className="emp-msg-head">
          <b style={{ color: t.color }}>{t.icon} {t.label}</b>
          <span>{fmtTime(m.createdAt)}</span>
          <span title={m.feishuPushed ? '已推送到你的飞书' : '未推送飞书（实例未连接或未配置授权用户）——这里能看到就不漏事'}>
            {m.feishuPushed ? '📱✓ 飞书已推' : '📱✗'}
          </span>
        </div>
        <div className="emp-msg-content">{linkify(m.content)}</div>
      </div>
    </div>
  )
}

/** RUNNING 需求的实时进度盒（3s 轮询的内嵌快照） */
function ProgressView({ p }) {  const phaseIcon = { '排队中': '⏳', '思考中': '🧠', '工具执行中': '🛠️', '生成回复': '✍️', '已结束': '✅' }[p.phase] || '🔄'
  return (
    <div style={{ fontSize: 12.5, color: 'var(--text-dim)', lineHeight: 1.7 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <span>{phaseIcon} {p.phase}</span>
        {p.currentTool && <span>· 🛠 <code>{p.currentTool}</code>{p.currentToolDetail ? <code className="tl-detail">{p.currentToolDetail}</code> : null}</span>}
        {p.elapsedMs > 0 && <span>· {fmtDur(p.elapsedMs)}</span>}
        {p.queued && <b style={{ color: '#d97706' }}>· 实例忙，排队中（队列 {p.queueDepth}）</b>}
      </div>
      {(p.tools || []).length > 0 && (
        <div className="progress-tools">
          {p.tools.map((t, i) => (
            <span key={i} className="tool-badge">
              {t.status === 'running' && <span className="spinner" style={{ width: 9, height: 9, marginRight: 0 }} />}
              🛠 {t.name}
              <span style={{ color: t.status === 'error' ? '#dc2626' : t.status === 'success' ? '#16a34a' : '#0891b2' }}>
                {t.status === 'running' ? '执行中' : t.status === 'success' ? '成功' : '失败'}
                {t.elapsedMs > 0 && t.status !== 'running' ? ` · ${fmtDur(t.elapsedMs)}` : ''}
              </span>
            </span>
          ))}
        </div>
      )}
      {p.answerTail && <div className="progress-tail">…{p.answerTail}</div>}
    </div>
  )
}

/** 下发需求弹窗 */
function DemandForm({ emp, onCancel, onDone, showToast }) {
  const [requirement, setRequirement] = useState('')
  const [title, setTitle] = useState('')
  const [instanceId, setInstanceId] = useState('')   // '' = 自动分派
  const [saving, setSaving] = useState(false)

  async function submit() {
    if (!requirement.trim()) { showToast('请填写需求内容', 'error'); return }
    setSaving(true)
    try {
      const res = await fetch(`/api/employees/${emp.id}/demands`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          requirement: requirement.trim(),
          title: title.trim() || null,
          instanceId: instanceId ? Number(instanceId) : null,
        }),
      })
      const d = await res.json()
      if (!res.ok) throw new Error(d.error || '下发失败')
      showToast('📮 需求已下发，员工自动开工', 'success')
      onDone()
    } catch (e) { showToast(e.message, 'error') } finally { setSaving(false) }
  }

  return (
    <div className="modal-mask" onClick={onCancel}>
      <div className="modal-box" style={{ maxWidth: 640 }} onClick={ev => ev.stopPropagation()}>
        <div className="modal-title">📮 给 {emp.emoji || ''}「{emp.name}」下发需求</div>
        <div className="form-group">
          <label className="form-label">需求内容 *</label>
          <textarea className="form-input" rows={5} style={{ resize: 'vertical' }} value={requirement}
            onChange={e => setRequirement(e.target.value)}
            placeholder="描述清楚要做什么，例如：按 create-supplier skill 对接 XX 供应商的查价接口，完成自测并输出对接报告" />
        </div>
        <div className="form-group">
          <label className="form-label">标题 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>可选，缺省取需求前 30 字</span></label>
          <input className="form-input" value={title} onChange={e => setTitle(e.target.value)}
            placeholder="例如：对接 XX 供应商查价接口" />
        </div>
        <div className="form-group">
          <label className="form-label">执行实例</label>
          <select className="form-input" value={instanceId} onChange={e => setInstanceId(e.target.value)}>
            <option value="">🔄 自动分派（挑空闲的绑定实例）</option>
            {(emp.instances || []).map(i => <option key={i.id} value={i.id}>📡 指定: {i.name}</option>)}
          </select>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 4 }}>
            需求自动入队开工；绑定多个实例时可同时各做一个需求（每实例同一时刻一个）。
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn" onClick={onCancel}>取消</button>
          <button className="btn btn-primary" onClick={submit} disabled={saving}>{saving ? '下发中…' : '下发需求'}</button>
        </div>
      </div>
    </div>
  )
}

/**
 * 订阅 /api/employees/{id}/events（SSE）。返回按 key 分组的时间线：'d<demandId>' 或 'chat'。
 * text/thinking 增量合并进同一条目，避免逐 token 一行；status/message 事件触发防抖刷新详情。
 */
function useEmployeeEvents(empId, onChange) {
  const [timelines, setTimelines] = useState({})
  const [connected, setConnected] = useState(false)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange

  useEffect(() => {
    setTimelines({})
    let es, retry, debounce, closed = false
    const seen = new Set()
    const pending = []
    let raf = null
    const kick = () => {
      clearTimeout(debounce)
      debounce = setTimeout(() => onChangeRef.current && onChangeRef.current(), 300)
    }
    const flush = () => {
      raf = null
      const batch = pending.splice(0)
      if (!batch.length) return
      setTimelines(prev => {
        const next = { ...prev }
        for (const ev of batch) {
          const key = ev.scope === 'chat' && !ev.demandId ? 'chat' : (ev.demandId ? 'd' + ev.demandId : null)
          if (!key) continue
          // 新一轮聊天开始 → 清空旧聊天时间线
          let list = (ev.kind === 'turn' && key === 'chat') ? [] : (next[key] ? [...next[key]] : [])
          const last = list[list.length - 1]
          if ((ev.kind === 'text' || ev.kind === 'thinking') && last && last.kind === ev.kind) {
            const merged = (last.text || '') + (ev.delta || '')
            list[list.length - 1] = { ...last, text: merged.length > 4000 ? merged.slice(-4000) : merged, ts: ev.ts }
          } else if (ev.kind === 'tool_end') {
            const i = list.findIndex(x => x.kind === 'tool' && x.toolId === ev.toolId)
            const item = { kind: 'tool', toolId: ev.toolId, name: ev.name, detail: ev.detail, status: ev.status, elapsedMs: ev.elapsedMs, ts: i >= 0 ? list[i].ts : ev.ts }
            if (i >= 0) list[i] = item; else list.push(item)
          } else if (ev.kind === 'tool_start') {
            list.push({ kind: 'tool', toolId: ev.toolId, name: ev.name, detail: ev.detail, status: 'running', ts: ev.ts, startMs: ev.startMs })
          } else if (ev.kind === 'text' || ev.kind === 'thinking') {
            list.push({ kind: ev.kind, text: ev.delta || '', ts: ev.ts })
          } else if (ev.kind !== 'message') {
            list.push({ ...ev })
          }
          if (list.length > 300) list = list.slice(-300)
          next[key] = list
        }
        return next
      })
    }
    const connect = () => {
      if (closed) return
      es = new EventSource(`/api/employees/${empId}/events`)
      es.addEventListener('hello', () => setConnected(true))
      es.addEventListener('emp', e => {
        let ev
        try { ev = JSON.parse(e.data) } catch { return }
        if (seen.has(ev.id)) return   // 重连回放去重
        seen.add(ev.id)
        if (ev.kind === 'status' || ev.kind === 'message' || ev.kind === 'done' || ev.kind === 'error') kick()
        pending.push(ev)
        if (!raf) raf = setTimeout(flush, 120)
      })
      es.onerror = () => {
        setConnected(false)
        es.close()
        retry = setTimeout(connect, 3000)
      }
    }
    connect()
    return () => { closed = true; es && es.close(); clearTimeout(retry); clearTimeout(debounce); clearTimeout(raf) }
  }, [empId])

  return { timelines, connected }
}

const TL_META = {
  status: { icon: '📌', color: '#64748b' },
  queued: { icon: '⏳', color: '#d97706' },
  turn: { icon: '🚀', color: '#2563eb' },
  thinking: { icon: '🧠', color: '#7c3aed' },
  text: { icon: '✍️', color: '#0f766e' },
  done: { icon: '✅', color: '#16a34a' },
  error: { icon: '❌', color: '#dc2626' },
}
const STATUS_TEXT = { QUEUED: '进入队列', RUNNING: '开工', DONE: '已完成', FAILED: '失败', CANCELLED: '已取消', INTERRUPTED: '被中断' }

/** 实时执行时间线：每一步做了什么、工具参数、耗时、输出片段；运行中自动滚到底 */
function LiveTimeline({ events, running }) {
  const boxRef = useRef(null)
  const [, setTick] = useState(0)
  const stick = useRef(true)
  useEffect(() => {
    if (!running) return
    const t = setInterval(() => setTick(x => x + 1), 1000)
    return () => clearInterval(t)
  }, [running])
  useEffect(() => {
    const b = boxRef.current
    if (b && stick.current) b.scrollTop = b.scrollHeight
  }, [events])
  if (!events || events.length === 0) return null
  const t0 = events[0].ts
  const rel = ts => '+' + (fmtDur(Math.max(0, ts - t0)) || '0s')
  return (
    <div className="live-timeline" ref={boxRef}
      onScroll={e => { const b = e.currentTarget; stick.current = b.scrollHeight - b.scrollTop - b.clientHeight < 30 }}>
      {events.map((e, i) => {
        if (e.kind === 'tool') {
          const st = e.status
          const live = st === 'running' ? Date.now() - (e.startMs || e.ts) : e.elapsedMs
          return (
            <div key={i} className={'tl-item tl-tool ' + st}>
              <span className="tl-time">{rel(e.ts)}</span>
              <span className="tl-icon">{st === 'running' ? <span className="spinner" style={{ width: 9, height: 9, marginRight: 0 }} /> : st === 'error' ? '⚠️' : '🛠'}</span>
              <span className="tl-body">
                <b>{e.name}</b>
                {e.detail && <code className="tl-detail">{e.detail}</code>}
                <span className="tl-meta" style={{ color: st === 'error' ? '#dc2626' : st === 'success' ? '#16a34a' : '#0891b2' }}>
                  {st === 'running' ? '执行中' : st === 'success' ? '成功' : '失败'}{live > 0 ? ` · ${fmtDur(live)}` : ''}
                </span>
              </span>
            </div>
          )
        }
        const m = TL_META[e.kind] || { icon: '•', color: '#64748b' }
        let body
        switch (e.kind) {
          case 'status': body = <>需求{STATUS_TEXT[e.status] || e.status}{e.error ? `：${e.error}` : ''}</>; break
          case 'queued': body = <>实例忙，排队等待（队列 {e.queueDepth}）<span className="tl-meta">{e.reason}</span></>; break
          case 'turn': body = <>开始执行{e.waitedMs > 1500 ? <span className="tl-meta">排队等待了 {fmtDur(e.waitedMs)}</span> : null}</>; break
          case 'thinking': body = <span className="tl-text tl-think">{e.text}</span>; break
          case 'text': body = <span className="tl-text">{e.text}</span>; break
          case 'done': body = <>本轮结束 <span className="tl-meta">
            总 {fmtDur(e.durationMs)} · 工具 {e.toolCount} 次 / {fmtDur(e.toolElapsedMs)}
            {e.thinkingMs > 0 ? ` · 思考 ${fmtDur(e.thinkingMs)}` : ''}
            {e.inputTokens >= 0 ? ` · in ${e.inputTokens} / out ${e.outputTokens}` : ''}
            {e.cacheReadTokens > 0 ? ` · 缓存 ${e.cacheReadTokens}` : ''}
            {e.costUsd >= 0 ? ` · $${Number(e.costUsd).toFixed(4)}` : ''}
          </span></>; break
          case 'error': body = <>{e.aborted ? '已中止' : '出错'}：{e.message}</>; break
          default: body = e.kind
        }
        return (
          <div key={i} className={'tl-item tl-' + e.kind}>
            <span className="tl-time">{rel(e.ts)}</span>
            <span className="tl-icon" style={{ color: m.color }}>{m.icon}</span>
            <span className="tl-body">{body}</span>
          </div>
        )
      })}
    </div>
  )
}

/** 已结束需求的执行过程（从落库快照还原，重启/刷新后也能回看） */
function SavedTimeline({ demandId }) {
  const [events, setEvents] = useState(null)
  useEffect(() => {
    fetch(`/api/employees/demands/${demandId}/timeline`).then(r => r.ok ? r.json() : []).then(raw => {
      const list = []
      for (const ev of raw || []) {
        if (ev.kind === 'tool_start') list.push({ kind: 'tool', toolId: ev.toolId, name: ev.name, detail: ev.detail, status: 'running', ts: ev.ts })
        else if (ev.kind === 'tool_end') {
          const i = list.findIndex(x => x.kind === 'tool' && x.toolId === ev.toolId)
          const item = { kind: 'tool', toolId: ev.toolId, name: ev.name, detail: ev.detail, status: ev.status, elapsedMs: ev.elapsedMs, ts: i >= 0 ? list[i].ts : ev.ts }
          if (i >= 0) list[i] = item; else list.push(item)
        } else if (ev.kind === 'text' || ev.kind === 'thinking') list.push({ kind: ev.kind, text: ev.delta || '', ts: ev.ts })
        else list.push(ev)
      }
      setEvents(list)
    }).catch(() => setEvents([]))
  }, [demandId])
  if (!events || events.length === 0) return null
  return (
    <div style={{ marginBottom: 8 }}>
      <b style={{ fontSize: 12.5, color: 'var(--text-dim)' }}>执行过程（已保存）</b>
      <LiveTimeline events={events} running={false} />
    </div>
  )
}
