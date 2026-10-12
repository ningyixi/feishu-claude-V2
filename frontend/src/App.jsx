import { useState, useEffect } from 'react'
import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import Instances from './pages/Instances'
import E2ETest from './pages/E2ETest'
import Hooks from './pages/Hooks'
import Supplier from './pages/Supplier'
import Employees from './pages/Employees'
import Login from './pages/Login'

const NAV = [
  { to: '/', label: '📡', title: '实例' },
  { to: '/e2e', label: '🧪', title: 'E2E 测试' },
  { to: '/hooks', label: '🪝', title: 'Hook' },
  { to: '/supplier', label: '🏭', title: '供应商工作台' },
  { to: '/employees', label: '👔', title: '数字员工' },
]

export default function App() {
  const [authed, setAuthed] = useState(null)   // null=检查中
  useEffect(() => {
    fetch('/api/auth/me').then(r => r.json()).then(d => setAuthed(!!d.authenticated)).catch(() => setAuthed(false))
    const onUnauth = () => setAuthed(false)
    window.addEventListener('panel-unauthorized', onUnauth)
    return () => window.removeEventListener('panel-unauthorized', onUnauth)
  }, [])
  if (authed === null) return null
  if (!authed) return <Login onDone={() => setAuthed(true)} />
  return <Panel onLogout={async () => {
    await fetch('/api/auth/logout', { method: 'POST' }).catch(() => {})
    setAuthed(false)
  }} />
}

function Panel({ onLogout }) {
  // 数字员工未读上报数 → 👔 图标右上角红点（仿飞书消息角标），15s 轮询
  const [empUnread, setEmpUnread] = useState(0)

  useEffect(() => {
    const load = () => fetch('/api/employees/messages/unread')
      .then(r => r.ok ? r.json() : { total: 0 })
      .then(d => setEmpUnread(d.total || 0))
      .catch(() => {})
    load()
    const t = setInterval(load, 15000)
    return () => clearInterval(t)
  }, [])

  return (
    <BrowserRouter>
      <div className="app">
        <nav className="sidebar">
          <div className="sidebar-brand">🤖</div>
          {NAV.map(n => (
            <NavLink key={n.to} to={n.to} end={n.to === '/'}
              className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
              title={n.to === '/employees' && empUnread > 0
                ? `${n.title} · ${empUnread} 条未读上报` : n.title}>
              <span className="nav-icon">{n.label}
                {n.to === '/employees' && empUnread > 0 && (
                  <span className="nav-badge">{empUnread > 99 ? '99+' : empUnread}</span>
                )}
              </span>
              <span className="nav-label">{n.title}</span>
            </NavLink>
          ))}
          <button className="nav-item nav-logout" onClick={onLogout} title="退出登录">
            <span className="nav-icon">🚪</span>
            <span className="nav-label">退出</span>
          </button>
        </nav>
        <main className="main">
          <Routes>
            <Route path="/" element={<Instances />} />
            <Route path="/e2e" element={<E2ETest />} />
            <Route path="/hooks" element={<Hooks />} />
            <Route path="/supplier" element={<Supplier />} />
            <Route path="/employees" element={<Employees />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
