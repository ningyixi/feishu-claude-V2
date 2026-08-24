import { useState } from 'react'

export default function Test() {
  const [feishu, setFeishu] = useState({ status: 'idle' })
  const [claude, setClaude] = useState({ status: 'idle' })

  async function runTest(type, setter) {
    setter({ status: 'loading' })
    try {
      const res = await fetch(`/api/test/${type}`, { method: 'POST' })
      const data = await res.json()
      setter({
        status: data.success ? 'success' : 'error',
        message: data.message,
        detail: data.detail,
      })
    } catch (e) {
      setter({ status: 'error', message: '请求失败: ' + e.message })
    }
  }

  return (
    <div>
      <h1 className="page-title">连通性测试</h1>

      <div className="test-grid">
        <div className="test-card">
          <div className="test-card-icon">🐦</div>
          <div className="test-card-title">飞书 API</div>
          <div className="test-card-desc">
            验证飞书 APP ID 和 Secret 是否有效，Client 能否创建成功。
          </div>
          <button className="btn" onClick={() => runTest('feishu', setFeishu)}
            disabled={feishu.status === 'loading'}>
            {feishu.status === 'loading' && <span className="spinner" />}
            测试飞书连接
          </button>
          {feishu.status === 'success' && (
            <div className="test-result success">
              ✅ {feishu.message}
              {feishu.detail && <div style={{ marginTop: 4 }}>{feishu.detail}</div>}
            </div>
          )}
          {feishu.status === 'error' && (
            <div className="test-result error">
              ❌ {feishu.message}
              {feishu.detail && <div style={{ marginTop: 4 }}>{feishu.detail}</div>}
            </div>
          )}
        </div>

        <div className="test-card">
          <div className="test-card-icon">🤖</div>
          <div className="test-card-title">Claude Code</div>
          <div className="test-card-desc">
            执行 claude -p &quot;hi&quot; 验证 CLI 是否可用。可能需要 ~10 秒。
          </div>
          <button className="btn" onClick={() => runTest('claude', setClaude)}
            disabled={claude.status === 'loading'}>
            {claude.status === 'loading' && <span className="spinner" />}
            测试 Claude Code
          </button>
          {claude.status === 'success' && (
            <div className="test-result success">
              ✅ {claude.message}
              {claude.detail && <div style={{ marginTop: 4 }}>{claude.detail}</div>}
            </div>
          )}
          {claude.status === 'error' && (
            <div className="test-result error">
              ❌ {claude.message}
              {claude.detail && <div style={{ marginTop: 4 }}>{claude.detail}</div>}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
