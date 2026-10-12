import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './App.css'

// 全局拦截：任何 /api 请求返回 401（未登录/过期）→ 通知 App 切到登录页
const _fetch = window.fetch.bind(window)
window.fetch = async (input, init) => {
  const res = await _fetch(input, init)
  const url = typeof input === 'string' ? input : input?.url || ''
  if (res.status === 401 && url.includes('/api/') && !url.includes('/api/auth/')) {
    window.dispatchEvent(new Event('panel-unauthorized'))
  }
  return res
}

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
)
