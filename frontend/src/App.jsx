import { useState, useEffect } from 'react'
import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import Instances from './pages/Instances'
import E2ETest from './pages/E2ETest'
import Hooks from './pages/Hooks'
import Supplier from './pages/Supplier'
import Employees from './pages/Employees'

const NAV = [
  { to: '/', label: '📡', title: '实例' },
  { to: '/e2e', label: '🧪', title: 'E2E 测试' },
  { to: '/hooks', label: '🪝', title: 'Hook' },
  { to: '/supplier', label: '🏭', title: '供应商工作台', short: '供应商' },
  { to: '/employees', label: '👔', title: '数字员工' },
]

export default function App() {
  return <Panel />
}

function Panel() {
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
        <nav className="sidebar" aria-label="主导航">
          <div className="sidebar-brand" aria-hidden="true">🤖</div>
          {NAV.map(n => (
            <NavLink key={n.to} to={n.to} end={n.to === '/'}
              className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
              aria-label={n.to === '/employees' && empUnread > 0 ? `${n.title}，${empUnread} 条未读` : n.title}
              title={n.to === '/employees' && empUnread > 0
                ? `${n.title} · ${empUnread} 条未读上报` : n.title}>
              <span className="nav-icon" aria-hidden="true">{n.label}
                {n.to === '/employees' && empUnread > 0 && (
                  <span className="nav-badge">{empUnread > 99 ? '99+' : empUnread}</span>
                )}
              </span>
              <span className="nav-label">{n.short || n.title}</span>
            </NavLink>
          ))}
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
