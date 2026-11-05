import { useEffect, useState } from 'react'
import { useConfirm, DraftPage } from './ui/FloatingUI'
import ReactMarkdown from 'react-markdown'

const labels = { NOT_STARTED: '尚未启动', ACTIVE: '运行中', PAUSED: '已暂停', CANCELLED: '已取消', COMPLETED: '已完成' }
export default function SupplierWorkflowPanel({ job, showToast, onManaged }) {
  const confirmAction = useConfirm()
  const [state, setState] = useState(null)
  const [recipient, setRecipient] = useState('')
  const [notify, setNotify] = useState(true)
  const [answers, setAnswers] = useState({})
  const [busy, setBusy] = useState(false)
  const api = `/api/supplier-jobs/${job.id}/workflow`
  const load = async () => {
    try {
      const response = await fetch(api); const data = await response.json()
      if (!response.ok) throw new Error(data.error || '流程加载失败')
      setState(data); onManaged?.(data.status !== 'NOT_STARTED')
    } catch (error) { showToast(error.message, 'error') }
  }
  useEffect(() => { load(); const timer = setInterval(load, 3000); return () => clearInterval(timer) }, [job.id])
  const post = async (path, body, multipart = false) => {
    if (busy) return
    setBusy(true)
    try {
      const response = await fetch(api + path, { method: 'POST', ...(multipart ? { body } : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }) })
      const data = await response.json()
      if (!response.ok) throw new Error(data.error || '操作失败')
      const completed = /^\/tasks\/([^/]+)\/respond$/.exec(path); if (completed) setAnswers(previous => { const next = { ...previous }; delete next[completed[1]]; return next })
      await load(); showToast('已保存，流程将按当前状态继续', 'success')
    } catch (error) { showToast(error.message, 'error') } finally { setBusy(false) }
  }
  if (!state) return <div className="card">正在读取流程状态…</div>
  const active = state.status === 'ACTIVE'
  return <section className="card" style={{ padding: 20, marginBottom: 20 }}>
    <h3>持续工作流 · {labels[state.status] || state.status}</h3>
    {state.status === 'NOT_STARTED' ? <>
      <p>启动后由 Flowable 依次推进：人工节点提交材料 → 实例执行 → 审批；缺信息时生成待办，答复后继续。启动后请通过待办操作节点。</p>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
        <label><input type="checkbox" checked={notify} onChange={e => setNotify(e.target.checked)} /> 飞书通知</label>
        <input className="form-input" aria-label="飞书通知接收人" style={{ minWidth: 0, flex: '1 1 260px' }} placeholder="接收人 open_id，留空用实例授权用户" value={recipient} onChange={e => setRecipient(e.target.value)} />
        <button className="btn btn-primary" disabled={busy} onClick={() => post('/start', { recipient, notifyFeishu: notify })}>启动持续工作流</button>
      </div>
      <p style={{ fontSize: 12, opacity: .7 }}>适用于尚未执行的工作。消息链接默认指向本机；跨设备访问请配置 FEISHU_WORKFLOW_PUBLIC_URL。</p>
    </> : <>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {active && <button className="btn" disabled={busy} onClick={() => post('/control', { action: 'PAUSE' })}>暂停流转</button>}
        {state.status === 'PAUSED' && <button className="btn btn-primary" disabled={busy} onClick={() => post('/control', { action: 'RESUME' })}>恢复流转</button>}
        {['ACTIVE', 'PAUSED'].includes(state.status) && <button className="btn" disabled={busy} onClick={async () => { if (await confirmAction({ title: '取消流程？', body: '取消后不再推进。已经发出的外部操作不会撤销，确定取消？取消后无法恢复此流程。', confirmLabel: '取消流程' })) post('/control', { action: 'CANCEL' }) }}>取消流程</button>}
        <span>通知：{state.run?.notifyFeishu ? `飞书 ${state.run.recipient}` : '仅工作台待办'}</span>
      </div>
      <p style={{ fontSize: 12, opacity: .7 }}>暂停会阻止后续派发；已经开始的实例执行可继续产出结果。审批不能跳过接口实测、公司 spec / Skill 或 Wiki 就绪检查。</p>
      {state.tasks?.map(task => <DraftPage key={task.id} dirty={!!answers[task.id]?.trim()}><article id={`task-${task.id}`} style={{ border: '1px solid var(--accent)', borderRadius: 8, padding: 16, marginTop: 12 }}>
        <h4>{task.name}</h4>
        <p>{task.description}</p>
        {task.question && <div style={{ whiteSpace: 'pre-wrap', background: 'var(--bg)', padding: 12 }}>{task.question}</div>}
        {task.artifact && <details open={task.kind === 'review'}><summary>本节点产物</summary><ReactMarkdown>{task.artifact}</ReactMarkdown></details>}
        {task.prepStatus && <div style={{ marginTop: 10, fontSize: 12, display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
          <span style={{ color: task.prepStatus.docDone ? '#16a34a' : '#d97706' }}>{task.prepStatus.docDone ? '✅' : '☐'} 三方会议文档{task.prepStatus.docDone ? '' : '（粘贴飞书链接或上传材料即可）'}</span>
          <span style={{ opacity: .5 }}>·</span>
          <span style={{ color: task.prepStatus.kvDone ? '#16a34a' : '#dc2626' }}>{task.prepStatus.kvDone ? '✅' : '☐'} 配置 key/value（在下方节点卡「写代码准备」保存≥1组）</span>
        </div>}
        <textarea className="form-input" aria-label={`答复 ${task.name}`} rows={4} style={{ width: '100%', marginTop: 10 }} placeholder={task.kind === 'review' ? '审批备注；驳回时请说明需要怎样修改' : task.prepStatus ? '填写答复…；三方会议文档可直接粘贴飞书云文档链接，点「提交并继续」自动收录' : '填写答案、补充信息，或上传所缺材料后提交'} value={answers[task.id] || ''} onChange={e => setAnswers(a => ({ ...a, [task.id]: e.target.value }))} />
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          <label>补充文件 <input type="file" disabled={!active || busy} accept=".pdf,.txt,.md,.markdown,.json,.csv,.yaml,.yml,.xml,.log" onChange={e => { const file = e.target.files?.[0]; if (!file) return; if (file.size > 10 * 1024 * 1024) { showToast('文件不能超过 10MB', 'error'); return } const data = new FormData(); data.append('file', file); post(`/tasks/${task.id}/files`, data, true); e.target.value = '' }} /></label>
          {(task.kind === 'review' ? ['APPROVE', 'REJECT'] : ['SUBMIT']).map(action => <button className={action === 'REJECT' ? 'btn' : 'btn btn-primary'} key={action} disabled={!active || busy} onClick={() => post(`/tasks/${task.id}/respond`, { action, answer: answers[task.id] || '' })}>{action === 'APPROVE' ? '同意并继续' : action === 'REJECT' ? '驳回并重做' : '提交并继续'}</button>)}
        </div>
        {task.attachments?.map(file => <div key={file.id}>📎 {file.filename} · 已入供应商知识库 #{file.entryId}</div>)}
      </article></DraftPage>)}
      {state.activity && active && <div style={{ marginTop: 12, padding: '10px 14px', borderRadius: 8, border: `1px solid ${state.activity.stalled ? 'rgba(220,38,38,.4)' : 'rgba(59,130,246,.3)'}`, background: state.activity.stalled ? 'rgba(220,38,38,.06)' : 'rgba(59,130,246,.06)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, fontWeight: 700, color: state.activity.stalled ? 'var(--error)' : '#2563eb' }}>
          {!state.activity.stalled && <span className="spinner" style={{ width: 10, height: 10 }} />}
          {state.activity.stalled ? '⚠️ 疑似卡住' : '▶'} {state.activity.phase} · {state.activity.text}
          <span style={{ marginLeft: 'auto', fontWeight: 400, fontSize: 11, color: 'var(--text-dim)' }}>最后活动 {state.activity.idleSeconds}s 前</span>
        </div>
        {state.activity.detail && <div style={{ marginTop: 6, fontSize: 12, color: 'var(--text-dim)', whiteSpace: 'pre-wrap', maxHeight: 96, overflowY: 'auto', wordBreak: 'break-all' }}>{state.activity.detail}</div>}
        {state.activity.stalled && <div style={{ marginTop: 6, fontSize: 11.5, color: 'var(--error)' }}>已 {Math.round(state.activity.idleSeconds / 60)} 分钟无进展——可能模型卡住/超长输出截断/等待外部。可"暂停"后"恢复"，或展开下方通知查看错误重试。</div>}
      </div>}
      {active && !state.tasks?.length && !state.activity && <p>流程正在派发或执行当前节点，结果就绪后会生成审批待办。</p>}
      <details style={{ marginTop: 16 }}><summary>通知及发送状态</summary>{state.notices?.map(notice => <div key={notice.id} style={{ padding: 8, borderBottom: '1px solid var(--border)' }}>
        <span>{notice.kind} · {notice.status} · 已尝试 {notice.attempts} 次</span>
        <div style={{ whiteSpace: 'pre-wrap' }}>{notice.message}</div>
        {notice.error && <p style={{ color: 'var(--red)' }}>{notice.error}</p>}
        {notice.status === 'FAILED' && <button className="btn" disabled={busy} onClick={() => post(`/notices/${notice.id}/retry`, {})}>重试通知</button>}
      </div>)}</details>
      <details style={{ marginTop: 12 }}><summary>人工处理记录（最近 100 条）</summary>{state.history?.map(item => <div key={item.id} style={{ padding: 8 }}>{item.name} · {item.response?.action || '已结束'}<pre style={{ whiteSpace: 'pre-wrap' }}>{item.response?.answer}</pre></div>)}</details>
    </>}
  </section>
}
