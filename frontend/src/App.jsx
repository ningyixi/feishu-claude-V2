import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import Config from './pages/Config'
import Test from './pages/Test'
import Logs from './pages/Logs'

const NAV = [
  { to: '/', label: '⚙️', title: '配置' },
  { to: '/test', label: '🔌', title: '测试' },
  { to: '/logs', label: '📋', title: '日志' },
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
            <Route path="/" element={<Config />} />
            <Route path="/test" element={<Test />} />
            <Route path="/logs" element={<Logs />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
