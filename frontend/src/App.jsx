import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import Config from './pages/Config'
import Test from './pages/Test'
import Terminal from './pages/Terminal'

const NAV = [
  { to: '/', label: '⚙️', title: '配置' },
  { to: '/test', label: '🔌', title: '测试' },
  { to: '/terminal', label: '🖥️', title: '终端' },
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
            <Route path="/terminal" element={<Terminal />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
