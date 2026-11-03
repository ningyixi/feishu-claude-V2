import { useState, useEffect } from 'react'
import { createBrowserRouter, RouterProvider, Routes, Route, NavLink, useLocation } from 'react-router-dom'
import Instances from './pages/Instances'
import E2ETest from './pages/E2ETest'
import Hooks from './pages/Hooks'
import Supplier from './pages/Supplier'
import Employees from './pages/Employees'
import Icon from './components/ui/Icon'
import { NavigationGuard } from './components/ui/FloatingUI'

const NAV = [
  { to: '/', icon: 'instances', title: '实例管理', subtitle: '连接与运行状态' },
  { to: '/e2e', icon: 'e2e', title: '协同测试', subtitle: '多角色验证与反馈' },
  { to: '/supplier', icon: 'supplier', title: '供应商工作台', subtitle: '从知识到持续交付' },
  { to: '/employees', icon: 'employees', title: '数字员工', subtitle: '分派需求，协作推进' },
  { to: '/hooks', icon: 'hooks', title: 'Skill 维护', subtitle: '沉淀每一次工作经验' },
]
const router = createBrowserRouter([{ path: '*', element: <Panel />, errorElement: <WorkspaceError /> }])
function WorkspaceError() { return <main className="workspace-error"><div className="brand-mark"><Icon name="logo"/></div><h1>页面暂时无法打开</h1><p>请重新加载页面，或返回工作空间继续操作。</p><div className="surface-actions"><a className="btn" href="/">返回工作空间</a><button className="btn btn-primary" onClick={() => window.location.reload()}>重新加载</button></div></main> }
export default function App() { return <RouterProvider router={router} /> }

function Panel() {
  const [empUnread, setEmpUnread] = useState(0)
  const location = useLocation()
  const current = NAV.find(item => item.to === location.pathname) || NAV[0]
  useEffect(() => {
    const load = () => fetch('/api/employees/messages/unread').then(r => r.ok ? r.json() : { total: 0 }).then(d => setEmpUnread(d.total || 0)).catch(() => {})
    load(); const timer = setInterval(load, 15000); return () => clearInterval(timer)
  }, [])
  return <div className="app">
    <NavigationGuard />
    <aside className="sidebar">
      <NavLink to="/" className="workspace-brand" aria-label="协作工作台首页"><span className="brand-mark"><Icon name="logo" size={23}/></span><span>协作工作台<small>AGENT WORKSPACE</small></span></NavLink>
      <div className="nav-section-label">工作空间</div>
      <nav className="workspace-nav" aria-label="主导航">{NAV.map(item => <NavLink key={item.to} to={item.to} end={item.to === '/'} className={({ isActive }) => 'nav-item' + (isActive ? ' active' : '')} aria-label={item.to === '/employees' && empUnread ? `${item.title}，${empUnread} 条未读上报` : item.title}>
        <span className="nav-icon"><Icon name={item.icon}/></span><span className="nav-label">{item.title}</span>{item.to === '/employees' && empUnread > 0 && <span className="workspace-unread">{empUnread}</span>}
      </NavLink>)}</nav>
      <div className="workspace-footer"><span className="workspace-status-dot"/><span>本地工作空间<small>OrbStack · MySQL</small></span><span className="workspace-key">LOCAL</span></div>
    </aside>
    <div className="workspace-content">
      <header className="workspace-header"><div className="workspace-breadcrumb">工作空间<span>/</span><strong>{current.title}</strong></div><div className="workspace-header-note">{current.subtitle}</div></header>
      <main className="main"><Routes><Route path="/" element={<Instances/>}/><Route path="/e2e" element={<E2ETest/>}/><Route path="/hooks" element={<Hooks/>}/><Route path="/supplier" element={<Supplier/>}/><Route path="/employees" element={<Employees/>}/></Routes></main>
    </div>
  </div>
}
