import { useState, useEffect } from 'react'

const FIELDS = [
  { key: 'feishuAppId', label: '飞书 APP ID', placeholder: 'cli_xxxxx' },
  { key: 'feishuAppSecret', label: '飞书 APP Secret', placeholder: '应用密钥', password: true },
  { key: 'authorizedOpenId', label: '授权用户 Open ID', placeholder: 'ou_xxxxx' },
  { key: 'shellWorkingDir', label: 'Shell 工作目录', placeholder: 'C:\\Users\\xxx 或留空使用默认' },
  { key: 'claudeCmdPath', label: 'Claude 命令路径', placeholder: 'claude 或完整路径如 C:\\...\\claude.cmd' },
  { key: 'claudeWorkingDir', label: 'Claude 工作目录', placeholder: '执行 Claude 命令的项目目录，如 D:\\projects\\my-app' },
]

export default function Config() {
  const [form, setForm] = useState({})
  const [saving, setSaving] = useState(false)
  const [toast, setToast] = useState(null)

  useEffect(() => {
    fetch('/api/config')
      .then(r => r.json())
      .then(setForm)
      .catch(e => showToast('加载配置失败: ' + e.message, 'error'))
  }, [])

  function showToast(msg, type) {
    setToast({ msg, type })
    setTimeout(() => setToast(null), 3000)
  }

  async function handleSave() {
    setSaving(true)
    try {
      const res = await fetch('/api/config', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(form),
      })
      const data = await res.json()
      if (!res.ok) {
        showToast(data.error || '保存失败', 'error')
        return
      }
      setForm(data)
      showToast('配置已保存', 'success')
    } catch (e) {
      showToast('保存失败: ' + e.message, 'error')
    } finally {
      setSaving(false)
    }
  }

  function handleChange(key, value) {
    setForm(prev => ({ ...prev, [key]: value }))
  }

  return (
    <div>
      <h1 className="page-title">配置管理</h1>

      <div className="card">
        <div className="card-title">飞书应用</div>
        {FIELDS.slice(0, 3).map(f => (
          <div className="form-group" key={f.key}>
            <label className="form-label">{f.label}</label>
            <input
              className="form-input"
              type={f.password ? 'password' : 'text'}
              value={form[f.key] || ''}
              placeholder={f.placeholder}
              onChange={e => handleChange(f.key, e.target.value)}
            />
          </div>
        ))}
      </div>

      <div className="card">
        <div className="card-title">终端 & Claude</div>
        {FIELDS.slice(3).map(f => (
          <div className="form-group" key={f.key}>
            <label className="form-label">{f.label}</label>
            <input
              className="form-input"
              value={form[f.key] || ''}
              placeholder={f.placeholder}
              onChange={e => handleChange(f.key, e.target.value)}
            />
          </div>
        ))}
      </div>

      <button className="btn btn-primary" onClick={handleSave} disabled={saving}>
        {saving ? '保存中...' : '保存配置'}
      </button>

      {toast && (
        <div className={`toast toast-${toast.type}`}>{toast.msg}</div>
      )}
    </div>
  )
}
