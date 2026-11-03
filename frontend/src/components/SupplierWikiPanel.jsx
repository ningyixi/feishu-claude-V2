import { Surface, DraftPage } from './ui/FloatingUI'
import { useCallback, useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

const LABEL = { EMPTY: '尚未编译', BUILDING: '正在编译', READY: '可使用', STALE: '来源已变化，需更新', FAILED: '编译失败' }
const KIND = { overview: '总览', constraints: '关键约束', api: '接口', business: '商务', concept: '概念', source: '来源', question: '冲突与问题' }
const PHASE = { all: '总览', analysis: '文档分析', probe: '接口实测', codegen: '代码开发' }
const number = n => n == null || n < 0 ? '—' : Number(n).toLocaleString()

export default function SupplierWikiPanel({ job, showToast }) {
  const [route, setRoute] = useSearchParams()
  const pageId = route.get('wiki'), revisionId = route.get('revision'), rawPage = route.get('raw') === '1'
  const [savedInstructions, setSavedInstructions] = useState('')
  const wiki = job.knowledgeMode === 'WIKI'
  const [state, setState] = useState(null)
  const [runs, setRuns] = useState([])
  const [view, setView] = useState(null)
  const [busy, setBusy] = useState(false)
  const [instructions, setInstructions] = useState('')
  const [contract, setContract] = useState('')
  const load = useCallback(async () => {
    try {
      const metrics = await fetch(`/api/supplier-jobs/${job.id}/knowledge-runs`)
      if (metrics.ok) setRuns(await metrics.json())
      if (wiki) {
        const response = await fetch(`/api/supplier-jobs/${job.id}/wiki`)
        if (response.ok) setState(await response.json())
      }
    } catch { /* keep last confirmed state on a transient connection failure */ }
  }, [job.id, wiki])
  useEffect(() => { load(); const timer = setInterval(load, 5000); return () => clearInterval(timer) }, [load])
  useEffect(() => {
    if (!wiki) return
    fetch(`/api/supplier-jobs/${job.id}/wiki/schema`).then(r => r.ok ? r.json() : null).then(body => {
      if (body) { setInstructions(body.instructions || ''); setSavedInstructions(body.instructions || ''); setContract(body.contract || '') }
    }).catch(() => {})
  }, [job.id, wiki])
  async function saveInstructions() {
    setBusy(true)
    try {
      const response = await fetch(`/api/supplier-jobs/${job.id}/wiki/schema`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ instructions }) })
      const body = await response.json()
      if (!response.ok) throw new Error(body.error || '规则保存失败')
      setSavedInstructions(instructions); showToast('Wiki 规则已保存，重新编译后生效', 'success'); await load()
    } catch (error) { showToast(error.message, 'error') } finally { setBusy(false) }
  }
  async function build() {
    setBusy(true)
    try {
      const response = await fetch(`/api/supplier-jobs/${job.id}/wiki/build`, { method: 'POST' })
      const body = await response.json()
      if (!response.ok) throw new Error(body.error || '编译未启动')
      showToast('Wiki 编译已启动，原始材料会保留', 'success')
      await load()
    } catch (error) { showToast(error.message, 'error') } finally { setBusy(false) }
  }
  function open(id, raw = false, revision = state?.revision) {
    const query = new URLSearchParams(route); query.set('job', String(job.id)); query.set('wiki', id); query.set('revision', String(revision)); if (raw) query.set('raw', '1'); else query.delete('raw'); setRoute(query)
  }
  function closeWiki() { const query = new URLSearchParams(route); for (const key of ['wiki', 'revision', 'raw']) query.delete(key); setRoute(query) }
  useEffect(() => {
    if (!pageId || !revisionId) { setView(null); return }
    let active = true
    setView({ id: pageId, raw: rawPage, revision: revisionId, content: '正在读取…' })
    fetch(`/api/supplier-jobs/${job.id}/wiki/revisions/${encodeURIComponent(revisionId)}/${rawPage ? 'sources' : 'pages'}/${encodeURIComponent(pageId)}`).then(async response => { const body = await response.json(); if (!response.ok) throw new Error(body.error || '读取失败'); if (active) setView({ ...body, raw: rawPage, revision: revisionId }) }).catch(error => { if (active) setView({ id: pageId, raw: rawPage, revision: revisionId, content: `读取失败：${error.message}` }) })
    return () => { active = false }
  }, [job.id, pageId, revisionId, rawPage])
  function link({ href, children }) {
    const match = /^(\.\.\/raw\/)?([a-z0-9-]+)\.md$/.exec(href || '')
    if (match) return <a href={href} onClick={event => { event.preventDefault(); open(match[2], !!match[1], view.revision) }}>{children}</a>
    return <a href={href} target="_blank" rel="noreferrer">{children}</a>
  }
  return <div className="card" style={{ marginTop: 14, padding: '14px 20px' }}>
    <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
      <b>{wiki ? '🕸️ LLM Wiki' : '📄 纯文本对照组'}</b>
      <span style={{ fontSize: 12, color: 'var(--text-dim)' }}>创建时固定为 {wiki ? 'WIKI' : 'TEXT'}；测试另一模式请新建同供应商、同模板工作</span>
      {wiki && <><span className="hook-badge">{LABEL[state?.status] || '读取状态中'}</span><div style={{ flex: 1 }} />
        <button className="btn btn-primary" disabled={busy || state?.status === 'BUILDING' || !job.instanceId} onClick={build}>{state?.revision ? '更新 / 重建 Wiki' : '编译 Wiki'}</button></>}
    </div>
    {wiki && <>
      <p style={{ fontSize: 12, color: 'var(--text-dim)' }}>原始材料保持可追溯；编译后的知识页包含交叉链接和证据。运行时固定交付关键约束与当前阶段入口，详情沿链接读取。来源变更后不会悄悄退回纯文本。</p>
      <DraftPage dirty={instructions !== savedInstructions}><details><summary>Wiki 编译规则（可维护）</summary>
        <p style={{ fontSize: 12 }}>可以指定接口、商务约定、冲突等知识如何组织。规则变更会使旧 Wiki 过期。</p>
        <textarea className="form-input" rows={4} maxLength={8000} value={instructions} onChange={event => setInstructions(event.target.value)} placeholder="例如：取消与退款分别建页；实测和文档矛盾时并列列出证据，未确认前不得覆盖商务决策。" />
        <button className="btn" disabled={busy || state?.status === 'BUILDING'} onClick={saveInstructions}>保存整理规则</button>
        <details><summary>固定的来源与发布规范</summary><pre style={{ whiteSpace: 'pre-wrap', fontSize: 11 }}>{contract}</pre></details>
      </details></DraftPage>
      {!job.instanceId && <p style={{ fontSize: 12 }}>先绑定实例，再编译 Wiki。</p>}
      {state?.error && <p style={{ fontSize: 12, color: 'var(--error)' }}>{state.error}</p>}
      {state?.revision && <p style={{ fontSize: 12 }}>版本 #{state.revision} · 来源 {state.completedSources}/{state.sourceCount} · 原文 {number(state.rawChars)} 字符 · 已观测编译输入 {number(state.inputTokens)} tokens · 输出 {number(state.outputTokens)} tokens</p>}
      {state?.status === 'READY' && <>
        <button className="btn" onClick={() => open('index')}>打开知识地图</button>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
          {Object.entries(state.entrypoints || {}).map(([stage, pages]) => <span key={stage} style={{ fontSize: 12 }}>{PHASE[stage] || stage}：{pages.map(id => <button className="btn" key={id} style={{ fontSize: 11, padding: '2px 6px', marginRight: 4 }} onClick={() => open(id)}>{id}</button>)}</span>)}
        </div>
        <div style={{ maxHeight: 280, overflow: 'auto', marginTop: 10 }}><table className="hook-table"><thead><tr><th>知识页</th><th>类型</th><th>适用场景</th></tr></thead>
          <tbody>{(state.pages || []).map(page => <tr key={page.id}><td><a href="#" onClick={event => { event.preventDefault(); open(page.id) }}>{page.title}</a>{state.critical?.includes(page.id) && ' · 每次必读'}</td><td>{KIND[page.kind] || page.kind}</td><td>{page.summary}</td></tr>)}</tbody></table></div>
      </>}
    </>}
    <details style={{ marginTop: 12 }}><summary>A/B 执行记录（最近 {runs.length} 次）</summary>
      <p style={{ fontSize: 11, color: 'var(--text-dim)' }}>字符数表示初始交付大小；模型 tokens 包含实际会话及工具读取，不能直接当成知识正文大小。请比较相同来源指纹、相同模板和阶段，并核对产物正确性。</p>
      <div style={{ overflowX: 'auto' }}><table className="hook-table"><thead><tr><th>节点 / 阶段</th><th>模式 / 版本</th><th>知识字符</th><th>完整请求字符</th><th>非缓存输入 / 输出 tokens</th><th>缓存读 / 写 tokens</th><th>状态</th><th>来源指纹</th></tr></thead>
        <tbody>{runs.map(run => <tr key={run.id}><td>{run.nodeId} / {run.stage}</td><td>{run.mode}{run.wikiRevision ? ` #${run.wikiRevision}` : ''}</td><td>{number(run.knowledgeChars)}</td><td>{number(run.promptChars)}</td><td>{number(run.inputTokens)} / {number(run.outputTokens)}</td><td>{number(run.cacheReadTokens)} / {number(run.cacheWriteTokens)}</td><td>{run.status}</td><td data-tooltip={run.sourceDigest} tabIndex={0}>{run.sourceDigest?.slice(0, 12) || '未取得完整版本'}</td></tr>)}</tbody></table></div>
    </details>
    {view && <Surface kind="page" onClose={closeWiki}><div className="modal-box" style={{ maxWidth: 940, width: 'min(940px,94vw)' }} onClick={event => event.stopPropagation()}>
      <div className="modal-title">{view.raw ? '原始来源' : 'Wiki 知识页'} · {view.id} · 版本 #{view.revision}</div>
      <div className="modal-body artifact-box md-body" style={{ maxHeight: '70vh', overflow: 'auto' }}><ReactMarkdown remarkPlugins={[remarkGfm]} components={{ a: link }}>{view.content}</ReactMarkdown></div>
      {!view.raw && Number(view.revision) === Number(state?.revision) && <div style={{ marginTop: 8, fontSize: 12 }}>反向链接：{(state?.pages || []).filter(page => page.links?.includes(view.id)).map(page => <button className="btn" key={page.id} onClick={() => open(page.id, false, view.revision)}>{page.title}</button>)}</div>}
      <div style={{ textAlign: 'right', marginTop: 10 }}><button className="btn" onClick={closeWiki}>关闭</button></div>
    </div></Surface>}
  </div>
}
