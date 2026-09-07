import { useState, useEffect } from 'react'
import ChatHistory from './ChatHistory'

const EMPTY = { name: '', feishuAppId: '', feishuAppSecret: '', authorizedOpenId: '', shellWorkingDir: '', claudeWorkingDir: '', claudeModel: '', enabled: true }

const FIELDS = [
  { key: 'name', label: '实例名称', placeholder: '如：SupplierV2 机器人' },
  { key: 'feishuAppId', label: '飞书 APP ID', placeholder: 'cli_xxxxx' },
  { key: 'feishuAppSecret', label: '飞书 APP Secret', placeholder: '应用密钥', password: true },
  { key: 'authorizedOpenId', label: '授权用户 Open ID', placeholder: 'ou_xxxxx' },
  { key: 'shellWorkingDir', label: 'Shell 工作目录', placeholder: 'D:\\projects\\xxx' },
  { key: 'claudeWorkingDir', label: 'Claude 项目目录', placeholder: 'D:\\dida\\SupplierV2' },
]

const MODELS = [
  { value: '', label: '默认模型（跟随网关配置）', desc: '' },
  {
    value: 'qwen3.8-max-anthropic',
    label: 'Qwen3.8 Max（旗舰 · 多模态 · 推荐）',
    desc: '千问旗舰多模态模型，综合能力对标 Claude Opus 4.8、Claude Sonnet 5 和 Gemini 3.7 Flash，适合复杂推理、编程、视觉理解及长程 Agent 任务。支持图片/表情包理解。',
  },
  {
    value: 'glm-5.3-anthropic',
    label: 'GLM 5.3（编程旗舰 · 不支持图片）',
    desc: 'GLM 文本编程旗舰，编程和 Agent 能力对标 Qwen3.8-Max、Kimi K3 和 Claude Opus 4.8，适合复杂软件工程与深度推理。暂不支持多模态（发图片/表情包将无法理解）。',
  },
  {
    value: 'glm-5.3-flash-anthropic',
    label: 'GLM 5.3 Flash（多模态 · 高性价比）',
    desc: 'GLM 原生多模态高性价比模型，能力对标 Gemini 3.7 Flash 和 DeepSeek V4 Pro，部分任务接近 Claude Opus 4.8，响应快、成本低。支持图片识别（实测色彩判断偶有波动，重要图片任务建议用 Qwen3.8 Max）。',
  },
  {
    value: 'qwen3.8-flash-anthropic',
    label: 'Qwen3.8 Flash（轻量快速 · 多模态）',
    desc: '轻量快速模型，响应快、成本低，适合简单任务。实测支持图片/表情包识别。',
  },
]

const STATUS_COLORS = {
  THINKING: '#3b82f6',     // 蓝 · 思考中
  WORKING: '#f59e0b',      // 橙 · 工具执行
  GENERATING: '#8b5cf6',   // 紫 · 奋笔疾书
  DONE: '#16a34a',         // 绿 · 任务完成
  IDLE: '#16a34a',         // 绿 · 空闲
  FISHING: '#f59e0b',      // 橙 · 摸鱼
  SLEEPING: '#6b7280',     // 灰 · 睡大觉
  CRASHED: '#dc2626',      // 红 · 进程挂了
  DISCONNECTED: '#dc2626', // 红 · 未连接
  DISABLED: '#6b7280',     // 灰 · 停用
}

function StatusBadge({ st }) {
  if (!st) return null
  const color = STATUS_COLORS[st.code] || '#6b7280'
  return (
    <span
      className={'status-badge' + (st.busy ? ' busy' : '')}
      style={{ color, background: color + '14', borderColor: color + '40' }}
      title={st.detail}
    >
      <span className="status-dot" />
      {st.emoji} {st.label}
    </span>
  )
}

export default function Instances() {
  const [list, setList] = useState([])
  const [editing, setEditing] = useState(null)
  const [form, setForm] = useState({ ...EMPTY })
  const [tests, setTests] = useState({})
  const [toast, setToast] = useState(null)
  const [chatInstance, setChatInstance] = useState(null)
  const [showForm, setShowForm] = useState(false)
  const [statuses, setStatuses] = useState({})

  useEffect(() => { load() }, [])

  // 实时状态轮询（4 秒一次）
  useEffect(() => {
    let alive = true
    async function poll() {
      try {
        const res = await fetch('/api/instances/status')
        if (!alive) return
        const arr = await res.json()
        setStatuses(Object.fromEntries(arr.map(s => [s.instanceId, s])))
      } catch (e) { /* 静默，下一轮重试 */ }
    }
    poll()
    const timer = setInterval(poll, 4000)
    return () => { alive = false; clearInterval(timer) }
  }, [])

  async function load() {
    try {
      const res = await fetch('/api/instances')
      setList(await res.json())
    } catch (e) { showToast('加载失败: ' + e.message, 'error') }
  }

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3000)
  }

  function startCreate() {
    setEditing(null)
    setForm({ ...EMPTY })
    setShowForm(true)
  }

  function startEdit(inst) {
    setEditing(inst.id)
    setForm({ ...inst })
    setShowForm(true)
  }

  function cancelEdit() {
    setEditing(null)
    setForm({ ...EMPTY })
    setShowForm(false)
  }

  async function handleSave() {
    try {
      const isEdit = editing !== null
      const res = await fetch(isEdit ? `/api/instances/${editing}` : '/api/instances', {
        method: isEdit ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
      })
      if (!res.ok) throw new Error('保存失败')
      showToast(isEdit ? '实例已更新' : '实例已创建', 'success')
      cancelEdit()
      load()
    } catch (e) { showToast(e.message, 'error') }
  }

  async function handleDelete(id) {
    if (!confirm('确定删除此实例？')) return
    try {
      await fetch(`/api/instances/${id}`, { method: 'DELETE' })
      showToast('已删除', 'success')
      load()
    } catch (e) { showToast('删除失败', 'error') }
  }

  async function runTest(id, type) {
    setTests(prev => ({ ...prev, [`${id}-${type}`]: { status: 'loading' } }))
    try {
      const res = await fetch(`/api/instances/${id}/test-${type}`, { method: 'POST' })
      const data = await res.json()
      setTests(prev => ({ ...prev, [`${id}-${type}`]: { status: data.success ? 'success' : 'error', message: data.message, detail: data.detail } }))
    } catch (e) {
      setTests(prev => ({ ...prev, [`${id}-${type}`]: { status: 'error', message: '请求失败: ' + e.message } }))
    }
  }

  // 如果选中了对话实例，显示对话历史
  if (chatInstance) {
    return (
      <ChatHistory
        instanceId={chatInstance.id}
        instanceName={chatInstance.name}
        onBack={() => setChatInstance(null)}
      />
    )
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
        <h1 className="page-title" style={{ margin: 0 }}>📡 实例管理</h1>
        {!showForm && (
          <button className="btn btn-primary" onClick={startCreate}>+ 新增实例</button>
        )}
      </div>

      {/* 新增/编辑表单 */}
      {showForm && (
        <div className="card" style={{ borderLeft: '3px solid var(--accent)' }}>
          <div className="card-title">{editing ? '编辑实例' : '新增实例'}</div>
          {FIELDS.map(f => (
            <div className="form-group" key={f.key}>
              <label className="form-label">{f.label}</label>
              <input
                className="form-input"
                type={f.password ? 'password' : 'text'}
                value={form[f.key] || ''}
                placeholder={f.placeholder}
                onChange={e => setForm(prev => ({ ...prev, [f.key]: e.target.value }))}
              />
            </div>
          ))}
          {/* 模型选择 */}
          <div className="form-group">
            <label className="form-label">Claude 模型</label>
            <select
              className="form-input"
              value={form.claudeModel || ''}
              onChange={e => setForm(prev => ({ ...prev, claudeModel: e.target.value }))}
              style={{ cursor: 'pointer' }}
            >
              {/* 兜底：实例存的旧模型已不在列表时仍可显示，避免静默变回默认 */}
              {form.claudeModel && !MODELS.some(m => m.value === form.claudeModel) && (
                <option value={form.claudeModel}>{form.claudeModel}（旧配置，网关可能已下线）</option>
              )}
              {MODELS.map(m => (
                <option key={m.value} value={m.value}>{m.label}</option>
              ))}
            </select>
            {(() => {
              const cur = MODELS.find(m => m.value === (form.claudeModel || ''))
              return (
                <div style={{ fontSize: '12px', color: 'var(--text-dim)', marginTop: '6px', lineHeight: 1.6 }}>
                  {cur?.desc || '未识别的模型名，将原样传给 CLI 的 --model 参数。'}
                  <div style={{ marginTop: '4px', opacity: 0.75 }}>⚠️ 网关已下线 Claude 模型，请从上方列表选择。</div>
                </div>
              )
            })()}
          </div>

          <div className="form-group" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <input type="checkbox" checked={form.enabled} onChange={e => setForm(prev => ({ ...prev, enabled: e.target.checked }))} />
            <label className="form-label" style={{ margin: 0 }}>启用此实例</label>
          </div>
          <div style={{ display: 'flex', gap: '8px' }}>
            <button className="btn btn-primary" onClick={handleSave}>{editing ? '保存' : '创建'}</button>
            <button className="btn" onClick={cancelEdit}>取消</button>
          </div>
        </div>
      )}

      {/* 实例列表 */}
      {list.length === 0 && !showForm && (
        <div style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
          暂无实例，点击"新增实例"开始配置
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
        {list.map(inst => {
          const feishuTest = tests[`${inst.id}-feishu`]
          const claudeTest = tests[`${inst.id}-claude`]
          const st = statuses[inst.id]
          return (
            <div key={inst.id} className="card" style={{ opacity: inst.enabled ? 1 : 0.6 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '12px' }}>
                <div>
                  <div style={{ fontSize: '16px', fontWeight: 600, marginBottom: '4px', display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                    {inst.name || '(未命名)'}
                    {!inst.enabled && !st && <span style={{ fontSize: '11px', color: 'var(--text-dim)' }}>已禁用</span>}
                    <StatusBadge st={st} />
                  </div>
                  <div style={{ fontSize: '12px', color: 'var(--text-dim)', fontFamily: 'var(--font-mono)' }}>
                    APP: {inst.feishuAppId || '未配置'} · Claude: {inst.claudeWorkingDir || inst.shellWorkingDir || '默认目录'}
                    {inst.claudeModel && <span> · 模型: {inst.claudeModel}</span>}
                  </div>
                  {st?.detail && <div className="status-detail">💬 {st.detail}</div>}
                </div>
                <div style={{ display: 'flex', gap: '6px' }}>
                  <button className="btn btn-primary" onClick={() => setChatInstance(inst)} style={{ fontSize: '12px', padding: '4px 10px' }}>💬 对话</button>
                  <button className="btn" onClick={() => startEdit(inst)} style={{ fontSize: '12px', padding: '4px 10px' }}>编辑</button>
                  <button className="btn" onClick={() => handleDelete(inst.id)} style={{ fontSize: '12px', padding: '4px 10px', color: 'var(--error)' }}>删除</button>
                </div>
              </div>

              {/* 测试按钮 */}
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                <div>
                  <button className="btn" onClick={() => runTest(inst.id, 'feishu')} disabled={feishuTest?.status === 'loading'} style={{ width: '100%', fontSize: '12px' }}>
                    {feishuTest?.status === 'loading' ? '测试中...' : '🐦 测试飞书连接'}
                  </button>
                  {feishuTest && feishuTest.status !== 'loading' && (
                    <div className={`test-result ${feishuTest.status}`} style={{ marginTop: '6px' }}>
                      <div>{feishuTest.status === 'success' ? '✅' : '❌'} {feishuTest.message}</div>
                      {feishuTest.detail && <div style={{ fontSize: '11px', marginTop: '2px', opacity: 0.8 }}>{feishuTest.detail.substring(0, 100)}</div>}
                    </div>
                  )}
                </div>
                <div>
                  <button className="btn" onClick={() => runTest(inst.id, 'claude')} disabled={claudeTest?.status === 'loading'} style={{ width: '100%', fontSize: '12px' }}>
                    {claudeTest?.status === 'loading' ? '测试中...' : '🤖 测试 Claude'}
                  </button>
                  {claudeTest && claudeTest.status !== 'loading' && (
                    <div className={`test-result ${claudeTest.status}`} style={{ marginTop: '6px' }}>
                      <div>{claudeTest.status === 'success' ? '✅' : '❌'} {claudeTest.message}</div>
                      {claudeTest.detail && <div style={{ fontSize: '11px', marginTop: '2px', opacity: 0.8 }}>{claudeTest.detail.substring(0, 100)}</div>}
                    </div>
                  )}
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {toast && <div className={`toast toast-${toast.type}`}>{toast.msg}</div>}
    </div>
  )
}
