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
    <main className="login-wrap">
      <form className="card login-card" onSubmit={submit} aria-labelledby="login-title">
        <div className="login-mark" aria-hidden="true">🤖</div>
        <h1 id="login-title" className="login-title">飞书 Claude 控制台</h1>
        <p className="login-sub">输入管理员密码后继续</p>
        <label className="form-label" htmlFor="panel-pw">管理员密码</label>
        <input id="panel-pw" className="form-input" type="password" autoFocus autoComplete="current-password"
          value={pw} onChange={e => setPw(e.target.value)} aria-invalid={!!err} aria-describedby={err ? 'pw-err' : undefined} />
        {err && <div id="pw-err" role="alert" className="field-error">{err}</div>}
        <button className="btn btn-primary" type="submit" disabled={!pw || busy}
          style={{ width: '100%', marginTop: 16, minHeight: 40 }}>
          {busy ? '正在登录…' : '登录'}
        </button>
        <p className="login-hint">
          密码由环境变量 <code>PANEL_PASSWORD</code> 设置；未设置时，启动日志和 <code>data/panel-password.txt</code> 里有自动生成的密码。
        </p>
      </form>
    </main>
  )
}
