import { useState, useEffect } from 'react'
import ChatHistory from './ChatHistory'

const EMPTY = { name: '', feishuAppId: '', feishuAppSecret: '', authorizedOpenId: '', workingDir: '', claudeModel: '', enabled: true, sandboxLevel: 0 }

// 沙箱档位（与后端 SandboxPolicy 对齐；旗标组合经真实 CLI 实测验证）
const SANDBOX_LEVELS = [
  {
    value: 0, label: '🔓 全权限（默认）',
    desc: 'bypassPermissions：所有工具直接放行（现状行为）。适合 admin 自己日常使用的信任实例。',
  },
  {
    value: 1, label: '🛡 宽松策略沙箱',
    desc: '白名单命令（git/mvn/npm/node/java/curl/python 及常用文件命令）放行，工作目录内文件编辑自动通过，白名单外一律自动拒绝（不卡死、留拒绝记录）。防误操作越权，不防白名单内工具的任意代码执行。',
  },
  {
    value: 2, label: '🔒 严格策略沙箱（员工工作机推荐）',
    desc: '在宽松档之上启用 CLI --restricted：文件工具被结构性圈禁在工作目录内（跨目录读写直接拒绝）、忽略仓库自带 .claude 配置（防供应链自我提权）、摘除 WebFetch/WebSearch 网络工具。数字员工绑定实例时自动开启此档，解绑自动恢复全权限。',
  },
]

const FIELDS = [
  { key: 'name', label: '实例名称', placeholder: '如：SupplierV2 机器人' },
  { key: 'feishuAppId', label: '飞书 APP ID', placeholder: 'cli_xxxxx' },
  { key: 'feishuAppSecret', label: '飞书 APP Secret', placeholder: '应用密钥', password: true },
  { key: 'authorizedOpenId', label: '授权用户 Open ID', placeholder: 'ou_xxxxx' },
  { key: 'workingDir', label: '工作目录', placeholder: 'D:\\projects\\xxx（shell 与 Claude 命令共用同一目录）' },
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
  // 实例独占绑定：instId(String) → 占用它的数字员工标签
  const [occupied, setOccupied] = useState({})

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
    try {
      const emps = await (await fetch('/api/employees')).json()
      const map = {}
      ;(emps || []).forEach(emp => (emp.instances || []).forEach(i => {
        map[String(i.id)] = `${emp.emoji || '👔'} ${emp.name}`
      }))
      setOccupied(map)
    } catch (e) { /* 静默 */ }
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
    // 单一工作目录：读取时取有效值（claude 优先、shell 兜底），保存时写回两列保持恒等
    setForm({ ...inst, workingDir: inst.claudeWorkingDir || inst.shellWorkingDir || '' })
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
      // 合并后的单一工作目录 → 同时写入 claudeWorkingDir/shellWorkingDir 两列（后端所有读取点自然一致）
      const { workingDir, ...rest } = form
      const body = { ...rest, claudeWorkingDir: workingDir || '', shellWorkingDir: workingDir || '' }
      const res = await fetch(isEdit ? `/api/instances/${editing}` : '/api/instances', {
        method: isEdit ? 'PUT' : 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
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
        <button className="btn btn-primary" onClick={startCreate}>+ 新增实例</button>
      </div>

      {/* 实例列表 */}
      {list.length === 0 && (
        <div style={{ textAlign: 'center', color: 'var(--text-dim)', padding: '3rem' }}>
          暂无实例，点击"新增实例"开始配置
        </div>
      )}

      {/* 实例列表：卡片网格（一行 3~4 个），meta 纵向全量展示，悬停抬升 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '12px', alignItems: 'start' }}>
        {list.map(inst => {
          const feishuTest = tests[`${inst.id}-feishu`]
          const claudeTest = tests[`${inst.id}-claude`]
          const st = statuses[inst.id]
          const dir = inst.claudeWorkingDir || inst.shellWorkingDir || '默认目录'
          return (
            <div key={inst.id} className="card inst-card" style={{ opacity: inst.enabled ? 1 : 0.6, padding: '12px 14px', marginBottom: 0 }}>
              {/* 名称 + 实时状态 */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                <span style={{ fontSize: '14.5px', fontWeight: 700 }}>{inst.name || '(未命名)'}</span>
                {!inst.enabled && !st && <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>已禁用</span>}
                <StatusBadge st={st} />
              </div>
              {/* 徽章行（独占/沙箱，有才渲染） */}
              {(occupied[String(inst.id)] || inst.sandboxLevel > 0) && (
                <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginTop: 6 }}>
                  {occupied[String(inst.id)] && (
                    <span className="hook-badge" title={`已被数字员工「${occupied[String(inst.id)]}」独占绑定，不能再分配给其他人（包括 admin）；要先在该员工处解绑`}>
                      👔 {occupied[String(inst.id)]}
                    </span>
                  )}
                  {inst.sandboxLevel > 0 && (
                    <span className="hook-badge" style={{ color: inst.sandboxLevel === 2 ? '#9333ea' : '#0891b2' }}
                      title={SANDBOX_LEVELS.find(s => s.value === inst.sandboxLevel)?.desc}>
                      {inst.sandboxLevel === 2 ? '🔒 严格沙箱' : '🛡 宽松沙箱'}
                    </span>
                  )}
                </div>
              )}
              {/* meta 纵向：每项一行，截断 + 悬停看全量 */}
              <div className="inst-rows">
                <div className="inst-row" title={inst.authorizedOpenId || '未配置'}>
                  <span className="inst-k">👤 OpenID</span>
                  <span className="inst-v">{inst.authorizedOpenId || '未配置'}</span>
                </div>
                <div className="inst-row" title={dir}>
                  <span className="inst-k">📁 目录</span>
                  <span className="inst-v">{dir}</span>
                </div>
                <div className="inst-row" title={inst.claudeModel || '跟随网关默认模型'}>
                  <span className="inst-k">🧠 模型</span>
                  <span className="inst-v">{inst.claudeModel || '网关默认'}</span>
                </div>
                <div className="inst-row" title={inst.feishuAppId || '未配置'}>
                  <span className="inst-k">🆔 APP</span>
                  <span className="inst-v">{inst.feishuAppId || '未配置'}</span>
                </div>
              </div>
              {st?.detail && <div className="status-detail inst-detail" title={st.detail}>💬 {st.detail}</div>}
              {/* 操作行：图标 + 文字 */}
              <div style={{ display: 'flex', gap: 6, marginTop: 9, flexWrap: 'wrap' }}>
                <button className="btn btn-primary" onClick={() => setChatInstance(inst)} style={{ fontSize: 12.5, padding: '3px 9px' }}>💬 对话</button>
                <button className="btn" onClick={() => runTest(inst.id, 'feishu')} disabled={feishuTest?.status === 'loading'}
                  style={{ fontSize: 12.5, padding: '3px 9px' }} title="测试飞书连接">
                  {feishuTest?.status === 'loading' ? '⏳ 测试中' : '🐦 测飞书'}
                </button>
                <button className="btn" onClick={() => runTest(inst.id, 'claude')} disabled={claudeTest?.status === 'loading'}
                  style={{ fontSize: 12.5, padding: '3px 9px' }} title="测试 Claude">
                  {claudeTest?.status === 'loading' ? '⏳ 测试中' : '🤖 测 Claude'}
                </button>
                <button className="btn" onClick={() => startEdit(inst)} style={{ fontSize: 12.5, padding: '3px 9px', marginLeft: 'auto' }}>✏️ 编辑</button>
                <button className="btn" onClick={() => handleDelete(inst.id)} style={{ fontSize: 12.5, padding: '3px 9px', color: 'var(--error)' }}>🗑 删除</button>
              </div>
              {/* 测试结果：点过才显示，各一行小字 */}
              {feishuTest && feishuTest.status !== 'loading' && (
                <div className={`test-result ${feishuTest.status}`} style={{ marginTop: 6, fontSize: 12, padding: '4px 8px' }}>
                  🐦 {feishuTest.status === 'success' ? '✅' : '❌'} {feishuTest.message}
                  {feishuTest.detail && <span style={{ opacity: 0.75 }}> · {feishuTest.detail.substring(0, 60)}</span>}
                </div>
              )}
              {claudeTest && claudeTest.status !== 'loading' && (
                <div className={`test-result ${claudeTest.status}`} style={{ marginTop: 6, fontSize: 12, padding: '4px 8px' }}>
                  🤖 {claudeTest.status === 'success' ? '✅' : '❌'} {claudeTest.message}
                  {claudeTest.detail && <span style={{ opacity: 0.75 }}> · {claudeTest.detail.substring(0, 60)}</span>}
                </div>
              )}
            </div>
          )
        })}
      </div>

      {/* 新增/编辑实例弹窗 */}
      {showForm && (
        <div className="modal-mask" onClick={cancelEdit}>
          <div className="modal-box" style={{ maxWidth: 720, width: 'min(720px, 94vw)' }} onClick={e => e.stopPropagation()}>
            <div className="modal-title">{editing ? '✏️ 编辑实例' : '➕ 新增实例'}</div>
            <div className="modal-body">
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

              {/* 沙箱档位 */}
              <div className="form-group">
                <label className="form-label">沙箱档位（L1 策略沙箱，基于 Claude CLI 原生权限系统）</label>
                <select
                  className="form-input"
                  value={form.sandboxLevel ?? 0}
                  onChange={e => setForm(prev => ({ ...prev, sandboxLevel: Number(e.target.value) }))}
                  style={{ cursor: 'pointer' }}
                >
                  {SANDBOX_LEVELS.map(s => (
                    <option key={s.value} value={s.value}>{s.label}</option>
                  ))}
                </select>
                <div style={{ fontSize: '12px', color: 'var(--text-dim)', marginTop: '6px', lineHeight: 1.6 }}>
                  {SANDBOX_LEVELS.find(s => s.value === (form.sandboxLevel ?? 0))?.desc}
                  <div style={{ marginTop: '4px', opacity: 0.75 }}>
                    档位变更在该实例下次会话使用时生效（重建终端进程）。沙箱限制失误/失控的爆炸半径，不防 prompt injection 本身；详见 docs/沙箱方案与威胁模型.md。
                  </div>
                </div>
              </div>

              <div className="form-group" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                <input type="checkbox" checked={form.enabled} onChange={e => setForm(prev => ({ ...prev, enabled: e.target.checked }))} />
                <label className="form-label" style={{ margin: 0 }}>启用此实例</label>
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 16 }}>
              <button className="btn" onClick={cancelEdit}>取消</button>
              <button className="btn btn-primary" onClick={handleSave}>{editing ? '保存' : '创建'}</button>
            </div>
          </div>
        </div>
      )}

      {toast && <div className={`toast toast-${toast.type}`}>{toast.msg}</div>}
    </div>
  )
}
