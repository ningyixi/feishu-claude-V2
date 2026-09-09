import { useState, useEffect } from 'react'

// ===== 常量 =====

const CRON_PRESETS = [
  { value: '0 0 3 * * ?', label: '每天凌晨 3:00' },
  { value: '0 0 9 * * ?', label: '每天早上 9:00' },
  { value: '0 0 * * * ?', label: '每小时整点' },
  { value: '0 0 9 ? * MON', label: '每周一 9:00' },
  { value: '0 0 12 * * ?', label: '每天中午 12:00' },
]

const RUN_STATUS = {
  SUCCESS: { icon: '✅', label: '成功', color: '#16a34a' },
  FAILED: { icon: '❌', label: '失败', color: '#dc2626' },
  JUDGED_SKIP: { icon: '💤', label: '判定无需触发', color: '#6b7280' },
  NO_MSG: { icon: '⚪', label: '窗口无对话', color: '#6b7280' },
  RUNNING: { icon: '🔄', label: '执行中', color: '#0891b2' },
}

const TRIGGER_LABEL = { AFTER_CHAT: '💬 对话后·LLM判定', CRON: '⏰ 定时', MANUAL: '👋 手动' }

function fmtTime(s) {
  if (!s) return '—'
  return String(s).replace('T', ' ').substring(5, 16)
}
function fmtDur(ms) {
  if (ms == null) return '—'
  const s = ms / 1000
  if (s < 60) return s.toFixed(1) + 's'
  return Math.floor(s / 60) + 'm' + Math.round(s % 60) + 's'
}
function cronHuman(expr) {
  const p = CRON_PRESETS.find(p => p.value === (expr || '').trim())
  return p ? p.label : expr
}

// ===== 主页面 =====

export default function Hooks() {
  const [hooks, setHooks] = useState([])
  const [instances, setInstances] = useState([])
  const [changes, setChanges] = useState([])
  const [editing, setEditing] = useState(null)   // null | 'new' | hook对象
  const [expanded, setExpanded] = useState({})   // hookId -> runs
  const [toast, setToast] = useState(null)

  const load = () => {
    fetch('/api/hooks').then(r => r.json()).then(setHooks).catch(() => {})
    fetch('/api/hooks/changes').then(r => r.json()).then(setChanges).catch(() => {})
  }
  useEffect(() => {
    load()
    fetch('/api/instances').then(r => r.json()).then(setInstances).catch(() => {})
    const t = setInterval(load, 30000)   // 30s 轮询
    return () => clearInterval(t)
  }, [])

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3500)
  }

  const instName = id => instances.find(i => String(i.id) === String(id))?.name || `#${id}`

  async function toggle(h) {
    await fetch(`/api/hooks/${h.id}/toggle`, { method: 'POST' })
    load()
  }
  async function runNow(h) {
    const res = await fetch(`/api/hooks/${h.id}/run`, { method: 'POST' })
    const data = await res.json().catch(() => ({}))
    showToast(res.ok ? `▶ 已触发「${h.name}」` : ('触发失败: ' + (data.error || '')), res.ok ? 'success' : 'error')
    setTimeout(load, 1500)
  }
  async function remove(h) {
    if (!confirm(`删除 Hook「${h.name}」？其执行记录和 skill 修改记录一并删除（skill 文件本身不受影响）。`)) return
    await fetch(`/api/hooks/${h.id}`, { method: 'DELETE' })
    showToast('已删除', 'success')
    load()
  }
  async function toggleRuns(h) {
    if (expanded[h.id]) {
      setExpanded(p => ({ ...p, [h.id]: null }))
      return
    }
    const runs = await fetch(`/api/hooks/${h.id}/runs`).then(r => r.json()).catch(() => [])
    setExpanded(p => ({ ...p, [h.id]: runs }))
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1rem' }}>
        <div>
          <h1 className="page-title" style={{ margin: 0 }}>🪝 Skill 自进化 Hook</h1>
          <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 4 }}>
            监控实例对话 → LLM 判定/定时触发 → 从实战中提炼经验 → 自动维护 skill 文件
          </div>
        </div>
        <button className="btn btn-primary" onClick={() => setEditing('new')}>+ 新建 Hook</button>
      </div>

      {/* Hook 卡片列表 */}
      {hooks.length === 0 && (
        <div className="card" style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
          暂无 Hook。新建一个：比如监控「实例2」的对话，自动维护 D:\feishu-claude-data\create-supplier
        </div>
      )}
      {hooks.map(h => {
        const last = h.lastRun
        const st = last ? RUN_STATUS[last.status] : null
        return (
          <div className="card" key={h.id} style={{ opacity: h.enabled ? 1 : 0.55, padding: '16px 20px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
              <div style={{ flex: 1, minWidth: 260 }}>
                <div style={{ fontSize: 15, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  {h.name}
                  <span className="hook-badge">{TRIGGER_LABEL[h.triggerType] || h.triggerType}</span>
                  {h.triggerType === 'CRON' && <span className="hook-badge dim">🕐 {cronHuman(h.cronExpr)}</span>}
                  {!h.enabled && <span className="hook-badge dim">已停用</span>}
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-dim)', marginTop: 6, lineHeight: 1.8 }}>
                  <div>👁 监控: {(h.instanceIds || '').split(',').filter(Boolean).map(id => (
                    <span key={id} className="hook-badge dim" style={{ marginRight: 4 }}>{instName(id)}</span>))}
                    <span style={{ marginLeft: 8 }}>🤖 维护模型来自: {instName(h.maintainerInstanceId)}</span>
                  </div>
                  <div>📁 skill: <code>{h.skillDir}</code></div>
                  <div>
                    {last
                      ? <>最近运行: <b style={{ color: st?.color }}>{st?.icon} {st?.label}</b> · {fmtTime(last.startedAt)} · {fmtDur(last.durationMs)}
                          {last.summary && <span> · {String(last.summary).substring(0, 80)}{last.summary.length > 80 ? '…' : ''}</span>}</>
                      : '尚未运行过'}
                    {h.lastRunAt && <span style={{ marginLeft: 8 }}>（窗口起点 {fmtTime(h.lastRunAt)}）</span>}
                  </div>
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                <button className="btn" style={{ fontSize: 12, padding: '4px 10px' }} onClick={() => runNow(h)}>▶ 立即运行</button>
                <button className="btn" style={{ fontSize: 12, padding: '4px 10px' }} onClick={() => toggle(h)}>{h.enabled ? '⏸ 停用' : '▶️ 启用'}</button>
                <button className="btn" style={{ fontSize: 12, padding: '4px 10px' }} onClick={() => setEditing(h)}>编辑</button>
                <button className="btn" style={{ fontSize: 12, padding: '4px 10px', color: 'var(--error)' }} onClick={() => remove(h)}>删除</button>
              </div>
            </div>

            {/* 执行记录（展开） */}
            <div style={{ marginTop: 10 }}>
              <span className="think-toggle" onClick={() => toggleRuns(h)}>
                📜 执行记录 {expanded[h.id] ? '▲' : '▼'}
              </span>
              {expanded[h.id] && (
                <table className="hook-table">
                  <thead>
                    <tr><th>时间</th><th>触发</th><th>状态</th><th>窗口</th><th>消息</th><th>耗时</th><th>判定理由 / 摘要</th></tr>
                  </thead>
                  <tbody>
                    {expanded[h.id].length === 0 && <tr><td colSpan={7} style={{ color: 'var(--text-dim)' }}>暂无记录</td></tr>}
                    {expanded[h.id].map(r => {
                      const rs = RUN_STATUS[r.status] || { icon: '?', label: r.status, color: '#6b7280' }
                      return (
                        <tr key={r.id}>
                          <td style={{ whiteSpace: 'nowrap' }}>{fmtTime(r.startedAt)}</td>
                          <td>{TRIGGER_LABEL[r.triggerType] || r.triggerType}</td>
                          <td style={{ color: rs.color, whiteSpace: 'nowrap' }}>{r.status === 'RUNNING' ? <span className="spinner" style={{ width: 10, height: 10 }} /> : rs.icon} {rs.label}</td>
                          <td style={{ whiteSpace: 'nowrap', fontSize: 11 }}>{fmtTime(r.windowFrom)} → {fmtTime(r.windowTo)}</td>
                          <td>{r.messageCount ?? '—'}</td>
                          <td>{fmtDur(r.durationMs)}</td>
                          <td className="hook-summary" title={(r.judgeReason || '') + '\n' + (r.summary || '')}>
                            {r.judgeReason && <span style={{ color: 'var(--text-dim)' }}>[判定] {r.judgeReason}<br /></span>}
                            {r.summary}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        )
      })}

      {/* Skill 修改流水 */}
      <div className="card" style={{ padding: '16px 20px' }}>
        <div style={{ fontSize: 14, fontWeight: 700, marginBottom: 10 }}>📖 Skill 维护修改记录</div>
        {changes.length === 0 ? (
          <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>暂无修改记录 —— Hook 触发并实际改动 skill 文件后会在这里入账</div>
        ) : (
          <table className="hook-table">
            <thead>
              <tr><th>时间</th><th>Hook</th><th>文件</th><th>大小变化</th><th>变更摘要</th></tr>
            </thead>
            <tbody>
              {changes.map(c => (
                <tr key={c.id}>
                  <td style={{ whiteSpace: 'nowrap' }}>{fmtTime(c.at)}</td>
                  <td>{hooks.find(h => h.id === c.hookId)?.name || `#${c.hookId}`}</td>
                  <td><code>{c.filePath}</code></td>
                  <td style={{ whiteSpace: 'nowrap', fontSize: 11 }}>
                    {(c.sizeBefore ?? 0) === 0 && (c.sizeAfter ?? 0) > 0 ? '🆕 新增'
                      : (c.sizeAfter ?? 0) === 0 ? '🗑 删除'
                      : `${(c.sizeBefore / 1024).toFixed(1)}K → ${(c.sizeAfter / 1024).toFixed(1)}K`}
                  </td>
                  <td className="hook-summary">{c.changeSummary}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 创建/编辑弹窗 */}
      {editing && (
        <HookForm
          hook={editing === 'new' ? null : editing}
          instances={instances}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); load(); showToast('已保存', 'success') }}
          showToast={showToast}
        />
      )}

      {toast && <div className={`toast toast-${toast.type}`}>{toast.msg}</div>}
    </div>
  )
}

// ===== 创建/编辑表单 =====

function HookForm({ hook, instances, onClose, onSaved, showToast }) {
  const [form, setForm] = useState(() => ({
    name: hook?.name || '',
    instanceIds: hook ? (hook.instanceIds || '').split(',').filter(Boolean).map(String) : [],
    maintainerInstanceId: hook?.maintainerInstanceId || '',
    skillDir: hook?.skillDir || '',
    triggerType: hook?.triggerType || 'AFTER_CHAT',
    cronExpr: hook?.cronExpr || '0 0 3 * * ?',
    enabled: hook ? hook.enabled : true,
  }))
  const [saving, setSaving] = useState(false)

  function toggleInst(id) {
    setForm(f => ({
      ...f,
      instanceIds: f.instanceIds.includes(String(id))
        ? f.instanceIds.filter(x => x !== String(id))
        : [...f.instanceIds, String(id)],
    }))
  }

  async function save() {
    if (!form.name.trim()) return showToast('请填写名称', 'error')
    if (form.instanceIds.length === 0) return showToast('请至少选择一个监控实例', 'error')
    if (!form.maintainerInstanceId) return showToast('请选择维护模型来源实例', 'error')
    if (!form.skillDir.trim()) return showToast('请填写 skill 目录', 'error')
    setSaving(true)
    try {
      const body = {
        name: form.name.trim(),
        instanceIds: form.instanceIds.join(','),
        maintainerInstanceId: Number(form.maintainerInstanceId),
        skillDir: form.skillDir.trim(),
        triggerType: form.triggerType,
        cronExpr: form.triggerType === 'CRON' ? form.cronExpr.trim() : '',
        enabled: form.enabled,
      }
      const res = await fetch(hook ? `/api/hooks/${hook.id}` : '/api/hooks', {
        method: hook ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.error || '保存失败')
      onSaved()
    } catch (e) {
      showToast(e.message, 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="modal-mask" onClick={onClose}>
      <div className="modal-box" style={{ maxWidth: 860, width: 'min(860px, 94vw)' }} onClick={e => e.stopPropagation()}>
        <div className="modal-title">{hook ? `编辑 Hook「${hook.name}」` : '🪝 新建 Skill 自进化 Hook'}</div>
        <div className="modal-body">
          <div className="form-group">
            <label className="form-label">Hook 名称 *</label>
            <input className="form-input" value={form.name} placeholder="如：供应商 skill 进化"
              onChange={e => setForm(f => ({ ...f, name: e.target.value }))} />
          </div>

          <div className="form-group">
            <label className="form-label">监控的实例 *（这些实例的飞书对话会被分析）</label>
            <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
              {instances.filter(i => i.enabled).map(i => (
                <label key={i.id} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer' }}>
                  <input type="checkbox" checked={form.instanceIds.includes(String(i.id))} onChange={() => toggleInst(i.id)} />
                  {i.name || `#${i.id}`}
                </label>
              ))}
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div className="form-group">
              <label className="form-label">维护模型来源实例 *（用它的模型跑判定和维护）</label>
              <select className="form-input" value={form.maintainerInstanceId}
                onChange={e => setForm(f => ({ ...f, maintainerInstanceId: e.target.value }))}>
                <option value="">选择实例…</option>
                {instances.filter(i => i.enabled).map(i => (
                  <option key={i.id} value={i.id}>{i.name || `#${i.id}`}{i.claudeModel ? ` (${i.claudeModel})` : ''}</option>
                ))}
              </select>
            </div>
            <div className="form-group">
              <label className="form-label">Skill 目录 *（维护者会话的工作目录，直接编辑其中文件）</label>
              <input className="form-input" value={form.skillDir} placeholder="D:\feishu-claude-data\create-supplier"
                onChange={e => setForm(f => ({ ...f, skillDir: e.target.value }))} />
            </div>
          </div>

          <div className="form-group">
            <label className="form-label">触发方式 *</label>
            <div style={{ display: 'flex', gap: 18, fontSize: 13, flexWrap: 'wrap' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                <input type="radio" checked={form.triggerType === 'AFTER_CHAT'}
                  onChange={() => setForm(f => ({ ...f, triggerType: 'AFTER_CHAT' }))} />
                💬 每次对话后（LLM 先判定本轮是否值得沉淀，值得才执行维护）
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
                <input type="radio" checked={form.triggerType === 'CRON'}
                  onChange={() => setForm(f => ({ ...f, triggerType: 'CRON' }))} />
                ⏰ 定时
              </label>
            </div>
            {form.triggerType === 'CRON' && (
              <div style={{ display: 'flex', gap: 8, marginTop: 10, alignItems: 'center', flexWrap: 'wrap' }}>
                <select className="form-input" style={{ width: 220, padding: '6px 8px' }}
                  value={CRON_PRESETS.some(p => p.value === form.cronExpr) ? form.cronExpr : ''}
                  onChange={e => e.target.value && setForm(f => ({ ...f, cronExpr: e.target.value }))}>
                  <option value="">预设…</option>
                  {CRON_PRESETS.map(p => <option key={p.value} value={p.value}>{p.label}</option>)}
                </select>
                <input className="form-input" style={{ flex: 1, padding: '6px 8px' }} value={form.cronExpr}
                  placeholder="cron 表达式（6位，如 0 0 3 * * ? = 每天凌晨3点）"
                  onChange={e => setForm(f => ({ ...f, cronExpr: e.target.value }))} />
              </div>
            )}
          </div>

          <label style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13 }}>
            <input type="checkbox" checked={form.enabled} onChange={e => setForm(f => ({ ...f, enabled: e.target.checked }))} />
            创建后立即启用
          </label>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
          <button className="btn" onClick={onClose}>取消</button>
          <button className="btn btn-primary" onClick={save} disabled={saving}>{saving ? '保存中…' : '保存'}</button>
        </div>
      </div>
    </div>
  )
}
