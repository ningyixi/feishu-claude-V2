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
                <div style={{ fontSize: 11.5, color: 'var(--text-dim)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                  title={e.roleDesc}>{e.roleDesc || '未填职责描述'}</div>
              </div>
              <button className="btn" style={{ fontSize: 11, padding: '2px 10px', color: 'var(--error)' }}
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
              </div>
            </div>
            <div style={{ display: 'flex', gap: 6, marginTop: 10, flexWrap: 'wrap', alignItems: 'center' }}>
              <span className="emp-stat" style={{ color: '#2563eb', background: 'rgba(37,99,235,0.10)' }}>▶ 进行 {e.stats?.running || 0}</span>
              <span className="emp-stat" style={{ color: '#6b7280', background: 'rgba(107,114,128,0.10)' }}>⏳ 排队 {e.stats?.queued || 0}</span>
              <span className="emp-stat" style={{ color: '#16a34a', background: 'rgba(22,163,74,0.10)' }}>✓ 完成 {e.stats?.done || 0}</span>
              {(e.stats?.failed || 0) > 0 && <span className="emp-stat" style={{ color: '#dc2626', background: 'rgba(220,38,38,0.10)' }}>✗ 失败 {e.stats.failed}</span>}
            </div>
            {(e.runningSummary || []).length > 0 && (
              <div style={{ fontSize: 11.5, color: '#2563eb', marginTop: 8, lineHeight: 1.7 }}>
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
        body: JSON.stringify({ name: name.trim(), emoji, roleDesc, instanceIds: bound.map(Number), permissions: perms, reportIntervalMin }),
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
          <label className="form-label">职责描述 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 11 }}>作为员工人设注入每次需求</span></label>
          <textarea className="form-input" rows={3} style={{ resize: 'vertical' }} value={roleDesc}
            onChange={e => setRoleDesc(e.target.value)}
            placeholder="例如：负责酒店供应商 API 对接——阅读接口文档、编写对接代码、自测并输出对接报告" />
        </div>
        <div className="form-group">
          <label className="form-label">可操作的实例 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 11 }}>多选；需求会在这些实例上自动并行（每实例同一时刻一个）</span></label>
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
                    ? <span style={{ fontSize: 11, color: '#d97706' }}>👔 已被「{occ}」绑定，需先解绑</span>
                    : <span style={{ fontSize: 11, color: 'var(--text-dim)' }}>{i.claudeWorkingDir || i.shellWorkingDir || ''}</span>}
                </label>
              )
            })}
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 6, lineHeight: 1.6 }}>
            🔒 绑定时实例自动开启<b>严格沙箱</b>（白名单命令 + 文件圈禁在工作目录 + 忽略仓库自带提权配置），解绑自动恢复全权限；
            这是员工工作机的默认安全档，admin 可在 📡 实例页手动调整。
          </div>
        </div>
        <div className="form-group">
          <label className="form-label">系统权限 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 11 }}>授权后员工执行需求时可在本系统创建对应对象（服务端强制校验，未授权会被拒绝）</span></label>
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
          <label className="form-label">定期汇报 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 11 }}>有进行中/排队的工作时，按间隔主动汇报进度（飞书 + 面板红点）；任务完成/失败/卡住时始终会立即上报</span></label>
          <select className="form-input" value={reportIntervalMin} onChange={e => setReportIntervalMin(Number(e.target.value))}>
            <option value={0}>关闭（只在完成/失败/卡住时上报）</option>
            <option value={30}>每 30 分钟</option>
            <option value={60}>每 1 小时</option>
            <option value={120}>每 2 小时</option>
            <option value={240}>每 4 小时</option>
          </select>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn" onClick={onCancel}>取消</button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
        </div>
      </div>
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

  useEffect(() => {
    load()
    const t = setInterval(load, 3000)   // 实时进度：3s 轮询（进度快照内嵌在详情响应里）
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [empId])

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

  return (
    <div>
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
        </div>
        <div style={{ flex: 1 }} />
        <button className="btn" onClick={() => setEditing(true)}>✏️ 编辑</button>
        <button className="btn" style={{ color: 'var(--error)' }} onClick={removeEmployee}>删除</button>
        <button className="btn btn-primary" onClick={() => setDispatching(true)}>📮 下发需求</button>
      </div>

      {/* 💬 双向聊天框：员工上报 + 老板直接对话派活（打开即已读） */}
      <EmpMsgPanel empId={empId} messages={data.messages} unreadCount={data.unreadCount}
        chatProgress={data.chatProgress} emp={emp} replyTo={replyTo}
        clearReplyTo={() => setReplyTo(null)} showToast={showToast} />

      {/* 实例工位：哪个实例正在做哪个需求 */}
      <div className="card" style={{ padding: '14px 16px' }}>
        <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 10 }}>
          🖥 实例工位
          {queuedCount > 0 && <span style={{ fontWeight: 400, color: '#d97706', fontSize: 11.5, marginLeft: 8 }}>⏳ {queuedCount} 个需求排队中</span>}
          <span style={{ fontWeight: 400, color: 'var(--text-dim)', fontSize: 11.5, marginLeft: 8 }}>每实例同一时刻做一个需求</span>
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
                <div style={{ fontSize: 10.5, color: d ? '#2563eb' : 'var(--text-dim)', display: 'flex', alignItems: 'center', gap: 4, marginTop: 2 }}>
                  {d ? <><span className="spinner" style={{ width: 9, height: 9 }} /> 正在干活</> : '💤 等待需求'}
                </div>
              </div>
              {d ? (
                <div className="progress-box">
                  <div style={{ fontWeight: 600 }}>{d.title}</div>
                  {d.progress ? <ProgressView p={d.progress} />
                    : <div style={{ color: 'var(--text-dim)', fontSize: 11.5 }}>正在启动…</div>}
                </div>
              ) : (
                <div style={{ fontSize: 11.5, color: 'var(--text-dim)', alignSelf: 'center' }}>
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
                <span style={{ fontSize: 11, color: 'var(--text-dim)', whiteSpace: 'nowrap' }}>
                  {fmtTime(d.createdAt)}{d.elapsedMs > 0 ? ` · ${fmtDur(d.elapsedMs)}` : ''}
                </span>
                <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>{open ? '▲' : '▼'}</span>
              </div>
              {open && (
                <div className="demand-body">
                  <div style={{ marginBottom: 8 }}>
                    <b style={{ fontSize: 11.5, color: 'var(--text-dim)' }}>需求内容</b>
                    <div style={{ whiteSpace: 'pre-wrap', marginTop: 2 }}>{d.requirement}</div>
                  </div>
                  {d.errorMessage && (
                    <div style={{ color: 'var(--error)', marginBottom: 8, fontSize: 12 }}>⚠ {d.errorMessage}</div>
                  )}
                  {d.result && (
                    <div>
                      <b style={{ fontSize: 11.5, color: 'var(--text-dim)' }}>完成汇报</b>
                      <div className="md-body" style={{ marginTop: 2 }}>
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>{d.result}</ReactMarkdown>
                      </div>
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: 8, marginTop: 10, justifyContent: 'flex-end' }}>
                    <button className="btn" style={{ fontSize: 11.5, padding: '3px 12px' }}
                      onClick={() => setReplyTo({ demandId: d.id, title: d.title })}>💬 追问</button>
                    {(d.status === 'QUEUED' || d.status === 'RUNNING') && (
                      <button className="btn" style={{ fontSize: 11.5, padding: '3px 12px', color: '#d97706' }}
                        onClick={() => act(d.id, 'cancel')}>
                        {d.status === 'RUNNING' ? '⏹ 取消（已请求停止）' : '取消'}
                      </button>
                    )}
                    {['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(d.status) && (
                      <button className="btn" style={{ fontSize: 11.5, padding: '3px 12px' }} onClick={() => act(d.id, 'retry')}>🔄 重试</button>
                    )}
                  </div>
                </div>
              )}
            </div>
          )
        })}
      </div>

      {editing && <EmployeeForm initial={emp} onCancel={() => setEditing(false)}
        onDone={() => { setEditing(false); load() }} showToast={showToast} />}
      {dispatching && <DemandForm emp={emp} onCancel={() => setDispatching(false)}
        onDone={() => { setDispatching(false); load() }} showToast={showToast} />}
    </div>
  )
}

/** 💬 员工主动上报聊天框（新消息在下，自动滚到底；打开详情页即全部已读） */
function EmpMsgPanel({ empId, messages, unreadCount, chatProgress, emp, replyTo, clearReplyTo, showToast }) {
  const boxRef = useRef(null)
  const [text, setText] = useState('')
  const [sending, setSending] = useState(false)
  useEffect(() => {
    if (boxRef.current) boxRef.current.scrollTop = boxRef.current.scrollHeight
  }, [messages?.length, chatProgress?.phase, chatProgress?.answerTail])

  async function send() {
    const t = text.trim()
    if (!t || sending) return
    setSending(true)
    try {
      const res = await fetch(`/api/employees/${empId}/chat`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ text: t, demandId: replyTo?.demandId || null }),
      })
      const d = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(d.error || '发送失败')
      setText('')
      if (replyTo) clearReplyTo()
    } catch (e) { showToast(e.message, 'error') } finally { setSending(false) }
  }

  const busy = !!chatProgress
  return (
    <div className="card" style={{ padding: '14px 16px' }}>
      <div style={{ fontSize: 13, fontWeight: 700, marginBottom: 10 }}>
        💬 聊天框
        <span style={{ fontWeight: 400, color: 'var(--text-dim)', fontSize: 11.5, marginLeft: 8 }}>
          可以直接对话派小活（正式大任务建议走 📮 下发需求，有状态跟踪）
          {(messages?.length || 0) > 0 && <> · {messages.length} 条{unreadCount > 0 ? ` · ${unreadCount} 条未读` : ''}</>}
        </span>
      </div>
      {(messages?.length > 0 || busy) && (
        <div className="emp-msgs" ref={boxRef}>
          {[...(messages || [])].reverse().map(m => m.sender === 'boss'
            ? <BossBubble key={m.id} m={m} />
            : <MsgBubble key={m.id} m={m} emp={emp} />)}
          {busy && (
            <div className="emp-msg">
              <div className="emp-msg-avatar">{emp?.emoji || '👤'}</div>
              <div className="emp-msg-bubble" style={{ borderLeftColor: '#2563eb' }}>
                <div className="emp-msg-head">
                  <b style={{ color: '#2563eb' }}>✍️ 正在处理你的消息…</b>
                  {chatProgress.instanceName && <span>📡 {chatProgress.instanceName}</span>}
                </div>
                <ProgressView p={chatProgress} />
              </div>
            </div>
          )}
        </div>
      )}
      {!(messages?.length > 0) && !busy && (
        <div style={{ fontSize: 12, color: 'var(--text-dim)', padding: '6px 0' }}>
          员工的上报（完成/失败/卡住/定期汇报）会出现在这里。
        </div>
      )}
      <div className="emp-chat-input">
        {replyTo && (
          <div className="reply-chip">
            💬 追问需求「{replyTo.title}」
            <span onClick={clearReplyTo} style={{ cursor: 'pointer', marginLeft: 6, fontWeight: 700 }}>✕</span>
          </div>
        )}
        <div style={{ display: 'flex', gap: 8 }}>
          <input className="form-input" style={{ flex: 1, padding: '7px 10px' }}
            placeholder={busy ? '员工正在回复，稍候…' : '对员工说点什么，可以直接安排小事（Enter 发送）'}
            value={text} disabled={busy || sending}
            onChange={e => setText(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }} />
          <button className="btn btn-primary" onClick={send} disabled={!text.trim() || busy || sending}>
            {sending ? '发送中…' : '发送'}
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
        <div className="emp-msg-content">{m.content}</div>
      </div>
    </div>
  )
}

/** RUNNING 需求的实时进度盒（3s 轮询的内嵌快照） */
function ProgressView({ p }) {  const phaseIcon = { '排队中': '⏳', '思考中': '🧠', '工具执行中': '🛠️', '生成回复': '✍️', '已结束': '✅' }[p.phase] || '🔄'
  return (
    <div style={{ fontSize: 11.5, color: 'var(--text-dim)', lineHeight: 1.7 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
        <span>{phaseIcon} {p.phase}</span>
        {p.currentTool && <span>· 🛠 <code>{p.currentTool}</code></span>}
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
          <label className="form-label">标题 <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 11 }}>可选，缺省取需求前 30 字</span></label>
          <input className="form-input" value={title} onChange={e => setTitle(e.target.value)}
            placeholder="例如：对接 XX 供应商查价接口" />
        </div>
        <div className="form-group">
          <label className="form-label">执行实例</label>
          <select className="form-input" value={instanceId} onChange={e => setInstanceId(e.target.value)}>
            <option value="">🔄 自动分派（挑空闲的绑定实例）</option>
            {(emp.instances || []).map(i => <option key={i.id} value={i.id}>📡 指定: {i.name}</option>)}
          </select>
          <div style={{ fontSize: 11, color: 'var(--text-dim)', marginTop: 4 }}>
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
