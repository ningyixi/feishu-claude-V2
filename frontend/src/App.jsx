import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import Instances from './pages/Instances'
import E2ETest from './pages/E2ETest'

const NAV = [
  { to: '/', label: '📡', title: '实例' },
  { to: '/e2e', label: '🧪', title: 'E2E 测试' },
]

export default function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <nav className="sidebar">
          <div className="sidebar-brand">🤖</div>
          {NAV.map(n => (
            <NavLink key={n.to} to={n.to} end={n.to === '/'}
              className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')}
              title={n.title}>
              <span className="nav-icon">{n.label}</span>
              <span className="nav-label">{n.title}</span>
            </NavLink>
          ))}
        </nav>
        <main className="main">
          <Routes>
            <Route path="/" element={<Instances />} />
            <Route path="/e2e" element={<E2ETest />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
