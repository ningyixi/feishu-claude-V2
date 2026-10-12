import { useState } from 'react'

/** 面板登录页（单管理员密码） */
export default function Login({ onDone }) {
  const [pw, setPw] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit(e) {
    e.preventDefault()
    if (!pw || busy) return
    setBusy(true); setErr('')
    try {
      const r = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: pw }),
      })
      const d = await r.json().catch(() => ({}))
      if (!r.ok) throw new Error(d.error || '登录失败')
      onDone()
    } catch (e2) { setErr(e2.message); setPw('') } finally { setBusy(false) }
  }

  return (
    <div className="login-wrap">
      <form className="card login-card" onSubmit={submit}>
        <div style={{ fontSize: 40, textAlign: 'center' }}>🤖</div>
        <div style={{ fontSize: 18, fontWeight: 700, textAlign: 'center', margin: '6px 0 4px' }}>管理面板登录</div>
        <div style={{ fontSize: 12, color: 'var(--text-dim)', textAlign: 'center', marginBottom: 18 }}>
          请输入管理员密码（未设置 PANEL_PASSWORD 时见启动日志 / data/panel-password.txt）
        </div>
        <input className="form-input" type="password" autoFocus placeholder="密码"
          value={pw} onChange={e => setPw(e.target.value)} />
        {err && <div style={{ color: 'var(--error)', fontSize: 12, marginTop: 8 }}>⚠ {err}</div>}
        <button className="btn btn-primary" type="submit" disabled={!pw || busy}
          style={{ width: '100%', marginTop: 14, justifyContent: 'center' }}>
          {busy ? '登录中…' : '登录'}
        </button>
      </form>
    </div>
  )
}
