import { test, expect } from '@playwright/test'
import { mockProduct } from './fixtures'

/**
 * 「临时需求」对话文字可拖选复制（2026-11-17 用户诉求："对话文字要做到鼠标能拖拽选中，我有时候要复制"）。
 *
 * 真实鼠标轨迹 + 真实轮询，不打桩 React。根因（已修，本测试钉住）：TaskDetail 的 useEffect 把
 * `detail && detail.status === 'READY'` 放进依赖、又在 effect 里 setDetail(null) —— 每次取回详情都翻转依赖
 * → effect 重跑 → 再取详情，形成自触发循环（修复前 Playwright 实测 8 秒内 /api/ad-hoc-tasks/{id} 被请求
 * 10992 次，页面长期停在「加载中…」、对话面板反复卸载重挂，用户拖到一半选区就被冲掉）。
 * 修复后：详情 10s / 对话 8s 一档，轮询只在数据真变化时 setState，气泡 React.memo 化。
 */

const AGENT_TEXT = '取消接口必须携带供应商订单号，否则返回 400。字段 orderNo 为字符串，最长 64 位。'
const QUESTION_TEXT = '【卡点】多酒店查价的数量上限定多少？现有文档只写了"支持批量"，没给上限。\nA. 10 家\nB. 20 家\nC. 其他（请给数值）'

const taskRow = { id: 2, name: '改历史接口字段', kbCode: 'adhoc-2', instanceId: 1, status: 'READY', wikiStatus: 'READY', error: '', running: false, blocked: true, createdAt: '2026-11-17T09:00:00', updatedAt: '2026-11-17T09:30:00' }

const detail = {
  ...taskRow, instanceName: '供应商联调 · Sandbox', docSources: 'https://example.com/docs/api',
  steps: [{ key: 'INGEST', status: 'DONE' }, { key: 'WIKI', status: 'DONE' }],
  wiki: { status: 'READY', revision: 77, sourceCount: 1, pageCount: 1, phase: '', error: '' },
  kbEntries: [],
  // 待确认卡（11-17-adhoc-blocker-notify 决策 3/4）：问题原文 + 飞书通知结果
  pendingQuestion: QUESTION_TEXT, notifyError: '', notifiedAt: '2026-11-17T09:31:00',
}

const chats = [
  { id: 1, taskId: 2, role: 'user', status: 'DONE', text: '给多酒店查价加上数量上限', createdAt: '2026-11-17T09:30:00' },
  { id: 2, taskId: 2, role: 'agent', status: 'BLOCKED', text: '【卡点】' + AGENT_TEXT, createdAt: '2026-11-17T09:31:00' },
]

/** pending=true 时附带一条 PENDING 行，进度文字每轮变化（模拟后端 2 秒节流回写 → 2.5s 轮询档位） */
async function mockAdHoc(page, { pending = false } = {}) {
  await mockProduct(page)
  let polls = 0
  let detailHits = 0
  await page.route('**/api/**', async route => {
    const p = new URL(route.request().url()).pathname
    if (p === '/api/ad-hoc-tasks') return route.fulfill({ json: [taskRow] })
    if (p === '/api/ad-hoc-tasks/2') { detailHits += 1; return route.fulfill({ json: detail }) }
    if (p === '/api/ad-hoc-tasks/2/chat') {
      polls += 1
      const rows = pending
        ? [...chats, { id: 3, taskId: 2, role: 'agent', status: 'PENDING', text: `⏳ 思考中（已 ${polls * 2} 秒）`, createdAt: '2026-11-17T09:32:00' }]
        : chats
      return route.fulfill({ json: rows })
    }
    if (p.startsWith('/api/supplier-kb/')) return route.fulfill({ json: [] })
    return route.fallback()
  })
  return { polls: () => polls, detailHits: () => detailHits }
}

/**
 * 真实鼠标拖选：沿**首行**从左拖到右侧。
 * 注意不要按元素垂直中缝拖——多行元素的行间空隙拖不出选区（实测：中缝拖 → getSelection() 为空）。
 */
async function dragSelect(page, locator) {
  await locator.scrollIntoViewIfNeeded()
  const box = await locator.boundingBox()
  const y = box.y + Math.min(8, box.height / 4)
  const x1 = box.x + 6
  const x2 = box.x + Math.min(box.width * 0.6, 320)
  await page.mouse.move(x1, y)
  await page.mouse.down()
  await page.mouse.move(x1 + (x2 - x1) / 3, y)
  await page.mouse.move(x1 + (2 * (x2 - x1)) / 3, y)
  await page.mouse.move(x2, y)
  await page.mouse.up()
}

/** 选区快照：文本 + 锚点是否还挂在文档上（节点被替换即为 false） */
async function selectionProbe(page) {
  return page.evaluate(() => {
    const sel = window.getSelection()
    const node = sel && sel.anchorNode ? (sel.anchorNode.nodeType === 1 ? sel.anchorNode : sel.anchorNode.parentElement) : null
    return { text: sel ? sel.toString() : '', rangeCount: sel ? sel.rangeCount : 0, anchorConnected: node ? node.isConnected : false }
  })
}

test('空闲轮询下拖选 agent 回复，选区跨过一个轮询周期仍在', async ({ page }) => {
  test.setTimeout(90_000)
  const counter = await mockAdHoc(page)
  await page.goto('/ad-hoc?task=2')

  const bubble = page.locator('.md-body', { hasText: '取消接口必须携带' }).first()
  await expect(bubble).toBeVisible({ timeout: 30_000 })
  // 对话区 DOM 变更计数：轮询若把消息重新渲染成新子树，这里就会跳
  await page.evaluate(() => {
    window.__mutations = 0
    const body = document.querySelector('[data-chat-body]') || document.body
    new MutationObserver(rs => { for (const r of rs) window.__mutations += r.addedNodes.length + r.removedNodes.length })
      .observe(body, { childList: true, subtree: true, characterData: true })
  })

  await dragSelect(page, bubble)
  const first = await selectionProbe(page)
  console.log('[实测·拖选后立即] ', JSON.stringify(first), 'chat轮询=', counter.polls(), 'detail请求=', counter.detailHits())

  expect(first.text.length, '拖选后 window.getSelection() 应有内容').toBeGreaterThan(0)
  expect(AGENT_TEXT.startsWith(first.text.trim()) || first.text.includes('取消接口'), `选中的应是回复正文，实际="${first.text}"`).toBeTruthy()
  expect(first.anchorConnected, '选区锚点应挂在文档上').toBeTruthy()

  await page.waitForTimeout(9_500)   // 静置超过一个 8s 空闲轮询周期
  const second = await selectionProbe(page)
  const mutations = await page.evaluate(() => window.__mutations)
  console.log('[实测·静置一个轮询周期后] ', JSON.stringify(second), 'chat轮询=', counter.polls(), 'detail请求=', counter.detailHits(), '对话区DOM增删节点数=', mutations)

  expect(second.text, '跨轮询后选区必须原样保留（用户要复制）').toBe(first.text)
  expect(second.anchorConnected, '承载选区的 DOM 节点不得被轮询替换').toBeTruthy()
  expect(mutations, '空闲轮询不应改动对话区 DOM').toBe(0)
  // 轮询不得失控（修复前：8 秒 10992 次详情请求）
  expect(counter.detailHits(), '详情接口被无限重渲染刷爆（TaskDetail 自触发循环）').toBeLessThan(12)
})

test('有 PENDING 行（2.5s 轮询 + 进度文字每轮变化）时仍能拖选已完成的回复', async ({ page }) => {
  test.setTimeout(60_000)
  const counter = await mockAdHoc(page, { pending: true })
  await page.goto('/ad-hoc?task=2')

  const bubble = page.locator('.md-body', { hasText: '取消接口必须携带' }).first()
  await expect(bubble).toBeVisible({ timeout: 30_000 })
  await dragSelect(page, bubble)
  const first = await selectionProbe(page)
  console.log('[实测·PENDING 拖选后] ', JSON.stringify(first), 'chat轮询=', counter.polls())
  expect(first.text.length).toBeGreaterThan(0)

  await page.waitForTimeout(6_000)   // 跨过 ≥2 个 2.5s 轮询周期（进度文字每轮都在变）
  const second = await selectionProbe(page)
  console.log('[实测·PENDING 静置 6s 后] ', JSON.stringify(second), 'chat轮询=', counter.polls())
  expect(second.text, '进度行每轮变化不得冲掉已完成回复上的选区').toBe(first.text)
  expect(second.anchorConnected).toBeTruthy()
})

test('待确认卡的问题原文可拖选，且跨轮询保持选中', async ({ page }) => {
  test.setTimeout(60_000)
  await mockAdHoc(page)
  await page.goto('/ad-hoc?task=2')

  const card = page.locator('[data-pending-question]')
  await expect(card).toBeVisible({ timeout: 30_000 })
  await expect(card).toContainText('多酒店查价的数量上限定多少')
  await expect(card).toContainText('飞书已通知')

  const questionText = page.locator('[data-pending-question-text]')
  await dragSelect(page, questionText)
  const first = await selectionProbe(page)
  console.log('[实测·待确认卡拖选后] ', JSON.stringify(first))
  expect(first.text.length, '待确认卡问题原文必须可拖选（用户最需要复制的文本）').toBeGreaterThan(0)
  expect(QUESTION_TEXT.startsWith(first.text.trim()), `选中的应是问题原文，实际="${first.text}"`).toBeTruthy()

  await page.waitForTimeout(11_000)   // 跨过一个 10s 详情轮询周期（READY 档）
  const second = await selectionProbe(page)
  console.log('[实测·待确认卡静置一个轮询周期后] ', JSON.stringify(second))
  expect(second.text, '待确认卡选区跨轮询必须保持').toBe(first.text)
})

test('飞书通知失败时待确认卡明示原因，不静默', async ({ page }) => {
  await mockProduct(page)
  await page.route('**/api/**', async route => {
    const p = new URL(route.request().url()).pathname
    if (p === '/api/ad-hoc-tasks') return route.fulfill({ json: [taskRow] })
    if (p === '/api/ad-hoc-tasks/2') return route.fulfill({ json: { ...detail, notifyError: '飞书实例未连接（长连接未建立或已断开）', notifiedAt: null } })
    if (p === '/api/ad-hoc-tasks/2/chat') return route.fulfill({ json: chats })
    if (p.startsWith('/api/supplier-kb/')) return route.fulfill({ json: [] })
    return route.fallback()
  })
  await page.goto('/ad-hoc?task=2')
  const card = page.locator('[data-pending-question]')
  await expect(card).toBeVisible({ timeout: 30_000 })
  await expect(card).toContainText('飞书通知失败：飞书实例未连接')
  await expect(card).not.toContainText('飞书已通知')
})
