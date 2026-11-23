import { test, expect } from '@playwright/test'
import { job, mockProduct } from './fixtures'

/**
 * 全站输入框键盘约定的浏览器级验证（2026-11-23 用户诉求：「所有的输入框都要支持 Ctrl + Enter 能在框里换行而不是发送」）。
 *
 * 判定与动作只有一处：`frontend/src/utils/composerKeys.js`（`isSendKey` 判定 + `handleComposerKey` 执行）。
 * 本文件验的是它在真实浏览器里的后果：
 * - `Shift+Enter` 走**浏览器原生**换行（我们不 preventDefault）；
 * - `Ctrl+Enter` 浏览器**没有**默认动作（实测 value 原样不动），故由 helper 走 `execCommand('insertText')`
 *   在光标处插入换行——光标位置、原生撤销栈（Ctrl+Z 能退掉）、React 受控值同步都必须保住；
 * - 两种"换行"都**零请求发出**；输入法组字期间的回车同样零请求。
 * 单测级真值表 + 静态扫描（调用点不得内联 Enter 判定）见 `frontend/tests/composer-keys.test.js`。
 *
 * 全部 API 走 mock（fixtures.mockProduct 记录所有非 GET 请求），不打后端、不写库。
 * 跑法（无需任何环境变量——baseURL 默认 http://localhost:3000，webServer 自动起/复用 vite，浏览器自动探测本机 Chrome/Edge）：
 *   cd frontend && npx playwright test tests/composer-keys.spec.js
 */

/** mockProduct 记录的非 GET 请求（发送与否的唯一权威证据） */
const posts = writes => writes.map(w => `${w.method} ${w.path}`)

test.describe('多行 composer（textarea）', () => {
  test('AC1/AC2 供应商工作台·节点补充指示：Ctrl+Enter 与 Shift+Enter 都换行且零请求，裸 Enter 才发送', async ({ page }) => {
    const writes = await mockProduct(page)
    await page.goto('/supplier?job=41')

    const box = page.locator('textarea[placeholder^="补充指示"]')
    await expect(box).toBeVisible({ timeout: 30_000 })
    // 提示文案统一（单一来源 COMPOSER_HINT），不叠加第二份提示
    await expect(box).toHaveAttribute('placeholder', '补充指示（可选），Enter 发送 · Shift/Ctrl+Enter 换行')

    await box.click()
    await box.pressSequentially('line-1')

    await box.press('Control+Enter')
    await expect(box, 'Ctrl+Enter 必须换行（浏览器无默认动作，由 helper 走 execCommand 插入）').toHaveValue('line-1\n')
    expect(writes, `Ctrl+Enter 不得发出任何请求，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)

    await box.pressSequentially('X')
    await expect(box, '光标必须落在插入的换行之后（不能丢光标位置）').toHaveValue('line-1\nX')

    await box.press('Shift+Enter')
    await expect(box, 'Shift+Enter 走浏览器原生换行').toHaveValue('line-1\nX\n')
    expect(writes, `Shift+Enter 不得发出任何请求，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)

    await box.press('Alt+Enter')
    await expect(box, 'Alt+Enter 不承诺换行，也不发送——原样不动').toHaveValue('line-1\nX\n')
    expect(writes, `Alt+Enter 不得发出任何请求，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)

    await box.press('Enter')
    await expect.poll(() => posts(writes), { timeout: 10_000, message: '裸 Enter 应触发节点执行' })
      .toContain('POST /api/supplier-jobs/41/nodes/n1/run')
    const body = JSON.parse(writes.find(w => w.path.endsWith('/run')).body)
    console.log('[实测·多行] Ctrl+Enter / Shift+Enter 各插入一个换行、Alt+Enter 不动，三次共零请求；裸 Enter 发送 instruction=', JSON.stringify(body.instruction))
    expect(body.instruction, '发送内容必须与框内所见完全一致（含两个换行）').toBe('line-1\nX\n')
  })

  test('AC2 DevChat·原始诉求：Ctrl+Enter 换行可被 Ctrl+Z 撤销，且受控值与撤销同步', async ({ page }) => {
    const writes = await mockProduct(page)
    const devChatJob = { ...job, nodes: job.nodes.map(n => n.nodeId === 'n1' ? { ...n, devChat: true } : n) }
    await page.route('**/api/supplier-jobs/41', route => route.fulfill({ json: devChatJob }))
    await page.goto('/supplier?job=41')

    const box = page.locator('textarea[placeholder^="原始诉求"]')
    await expect(box).toBeVisible({ timeout: 30_000 })
    await expect(box).toHaveAttribute('placeholder', '原始诉求（Enter 发送 · Shift/Ctrl+Enter 换行）')

    await box.click()
    await box.pressSequentially('abc')
    await box.press('Control+Enter')
    await expect(box).toHaveValue('abc\n')
    expect(writes, `DevChat 里 Ctrl+Enter 不得发送，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)

    // 原生撤销栈必须保住：这是"禁止整串重写 value"的理由，直接钉住
    await box.press('Control+z')
    await expect(box, 'Ctrl+Z 应能退掉刚插入的换行（撤销栈未被破坏）').toHaveValue('abc')
    expect(writes, '撤销过程不得触发发送').toHaveLength(0)

    await box.press('Enter')
    await expect.poll(() => posts(writes), { timeout: 10_000 }).toContain('POST /api/supplier-jobs/41/nodes/n1/devchat')
    const body = JSON.parse(writes.find(w => w.path.endsWith('/devchat')).body)
    console.log('[实测·DevChat] Ctrl+Enter 插入换行 → Ctrl+Z 撤销 → Enter 发送 text=', JSON.stringify(body.text))
    expect(body.text, 'React 受控值必须跟上原生撤销（发送的是撤销后的内容）').toBe('abc')
  })
})

test.describe('输入法组字（AC3）', () => {
  test('AC3 组字期间的回车绝不发送：合成 isComposing/keyCode=229 事件为正证据，真实 IME 通道做诊断', async ({ page }) => {
    const writes = await mockProduct(page)
    await page.goto('/supplier?job=41')
    const box = page.locator('textarea[placeholder^="补充指示"]')
    await expect(box).toBeVisible({ timeout: 30_000 })
    await box.click()
    await box.fill('半成品')

    // 正证据：带 isComposing / keyCode=229 的 keydown 走 React 同一条 onKeyDown → isSendKey 路径
    const dispatch = init => box.evaluate((el, i) => el.dispatchEvent(
      new KeyboardEvent('keydown', { bubbles: true, cancelable: true, ...i })), init)
    await dispatch({ key: 'Enter', isComposing: true, keyCode: 229 })
    await dispatch({ key: 'Enter', isComposing: true, keyCode: 229, ctrlKey: true })
    await dispatch({ key: 'Process', isComposing: true, keyCode: 229 })
    await dispatch({ key: 'Enter', keyCode: 229 })                       // 只给 229 不给 isComposing（老浏览器兜底）
    await page.waitForTimeout(300)
    expect(writes, `组字期间的回车一次也不得发送，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)
    await expect(box, '组字回车不得清空/改动输入框内容').toHaveValue('半成品')

    // 反证：同一通道下发非组字 Enter 必须立刻发送——证明上面的"零请求"不是因为事件根本到不了 handler
    await dispatch({ key: 'Enter', isComposing: false })
    await expect.poll(() => posts(writes), { timeout: 10_000, message: '合成事件通道有效性反证' })
      .toContain('POST /api/supplier-jobs/41/nodes/n1/run')
    console.log('[实测·AC3 合成事件] 组字回车 ×4（含 ctrlKey / key=Process / 仅 keyCode=229）→ 零请求、内容未变；非组字 Enter → 立即发送')

    // 诊断 + 正证据：真开一段 IME 组字再按**真实**（isTrusted）Enter，看浏览器是否标组字态、以及是否发送。
    // 断言放在 try 外面——CDP 不可用只影响"能否复现"，不能把断言失败吞成"未验证"。
    const ime = { attempted: false, available: false, logged: [], valueBefore: null, valueAfter: null, postsBefore: 0, error: '' }
    try {
      const cdp = await page.context().newCDPSession(page)
      await page.evaluate(() => {
        window.__keyLog = []
        document.addEventListener('keydown', e => window.__keyLog.push({ key: e.key, isComposing: e.isComposing, keyCode: e.keyCode }), true)
      })
      await box.fill('')
      await box.click()
      ime.attempted = true
      await cdp.send('Input.imeSetComposition', { text: '你好世界', selectionStart: 4, selectionEnd: 4 })
      ime.valueBefore = await box.inputValue()
      ime.postsBefore = posts(writes).length
      await page.keyboard.press('Enter')
      await page.waitForTimeout(400)
      ime.logged = await page.evaluate(() => window.__keyLog)
      ime.valueAfter = await box.inputValue()
      ime.available = true
      await cdp.detach()
    } catch (err) {
      ime.error = String(err).slice(0, 200)
    }
    const composingSeen = ime.logged.some(k => k.key === 'Enter' && (k.isComposing === true || k.keyCode === 229))
    console.log('[实测·AC3 真实 IME] CDP可用=', ime.available, 'keydown记录=', JSON.stringify(ime.logged),
      '组字前value=', JSON.stringify(ime.valueBefore), '按Enter后value=', JSON.stringify(ime.valueAfter),
      '按Enter前请求数=', ime.postsBefore, '按Enter后请求=', JSON.stringify(posts(writes)), ime.error ? `err=${ime.error}` : '')
    if (!ime.available) {
      console.log('[实测·AC3 真实 IME] 该子项记「未验证」（CDP 通道不可用），以上方合成事件 + 单测真值表为准')
    } else if (!composingSeen) {
      console.log('[实测·AC3 真实 IME] 该子项记「未验证」：Playwright 合成键盘绕过 IME，浏览器未把这次 Enter 标成组字态')
    } else {
      // 浏览器确实标了组字态 → 这次真实 Enter 绝不能产生新的发送
      expect(posts(writes).length, `真实组字态的 Enter 不得发送，实际=${JSON.stringify(posts(writes))}`).toBe(ime.postsBefore)
      console.log('[实测·AC3 真实 IME] ✅ 浏览器标组字态（isComposing=true）时真实 Enter 未发送，请求数保持', ime.postsBefore)
    }
  })
})

test.describe('原单行 4 处 → 多行 textarea（X1：Ctrl+Enter 真换行）', () => {
  test('X1 临时需求·对话框与待确认卡答复：Ctrl+Enter 真换行且零请求，Enter 仍发送、请求体字段不变', async ({ page }) => {
    const writes = await mockProduct(page)
    const taskRow = { id: 2, name: '改历史接口字段', kbCode: 'adhoc-2', instanceId: 1, status: 'READY', wikiStatus: 'READY', error: '', running: false, blocked: true, createdAt: '2026-11-23T09:00:00', updatedAt: '2026-11-23T09:30:00' }
    const detail = {
      ...taskRow, instanceName: '供应商联调 · Sandbox', docSources: 'https://example.com/docs/api',
      steps: [{ key: 'INGEST', status: 'DONE' }], wiki: { status: 'READY', revision: 7, sourceCount: 1, pageCount: 1, phase: '', error: '' },
      kbEntries: [], pendingQuestion: '【卡点】多酒店查价上限多少？', notifyError: '', notifiedAt: '2026-11-23T09:31:00',
    }
    // 只接管 GET；非 GET 一律 fallback 给 mockProduct，发送才会被记录进 writes
    await page.route('**/api/**', async route => {
      if (route.request().method() !== 'GET') return route.fallback()
      const p = new URL(route.request().url()).pathname
      if (p === '/api/ad-hoc-tasks') return route.fulfill({ json: [taskRow] })
      if (p === '/api/ad-hoc-tasks/2') return route.fulfill({ json: detail })
      if (p === '/api/ad-hoc-tasks/2/chat') return route.fulfill({ json: [] })
      if (p.startsWith('/api/supplier-kb/')) return route.fulfill({ json: [] })
      return route.fallback()
    })
    const errors = []
    page.on('pageerror', e => errors.push(e.message))
    await page.goto('/ad-hoc?task=2')

    // 1) 对话框（原单行 input → 多行 textarea，data-chat-input）
    const chat = page.locator('.card', { hasText: '💬 对话框' }).locator('textarea[data-chat-input]')
    await expect(chat).toBeVisible({ timeout: 30_000 })
    await chat.click()
    await chat.pressSequentially('把历史接口字段改一下')
    await chat.press('Control+Enter')
    await expect(chat, 'Ctrl+Enter 必须真换行（textarea 多行框）').toHaveValue('把历史接口字段改一下\n')
    expect(writes, `Ctrl+Enter 不得发出任何请求，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)
    await chat.pressSequentially('第二行补充')
    await chat.press('Alt+Enter')
    await page.waitForTimeout(200)
    await expect(chat, 'Alt+Enter 不承诺换行也不发送——原样不动').toHaveValue('把历史接口字段改一下\n第二行补充')
    expect(writes, `Alt+Enter 零请求，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)
    expect(errors, '不得报错').toEqual([])

    await chat.press('Enter')
    await expect.poll(() => posts(writes), { timeout: 10_000, message: '裸 Enter 应正常发送' })
      .toContain('POST /api/ad-hoc-tasks/2/chat')
    const body = JSON.parse(writes.find(w => w.path === '/api/ad-hoc-tasks/2/chat').body)
    expect(body.question, '请求体字段不变（question），内容=框内所见（含换行）').toBe('把历史接口字段改一下\n第二行补充')
    await expect(chat, '发送后清空并回到单行高度').toHaveValue('')

    // 2) 待确认卡答复（data-pending-answer，现为 textarea）
    const answer = page.locator('textarea[data-pending-answer]')
    await expect(answer).toBeVisible({ timeout: 30_000 })
    const before = writes.length
    await answer.click()
    await answer.pressSequentially('上限按 20 家')
    await answer.press('Control+Enter')
    await expect(answer, '待确认卡答复框 Ctrl+Enter 必须真换行').toHaveValue('上限按 20 家\n')
    expect(writes.length, `Ctrl+Enter 零请求，实际=${JSON.stringify(posts(writes))}`).toBe(before)
    expect(errors, '不得报错').toEqual([])
    await answer.pressSequentially('先按 20 家灰度')
    await answer.press('Enter')
    await expect.poll(() => writes.length, { timeout: 10_000 }).toBeGreaterThan(before)
    const answerBody = JSON.parse(writes.filter(w => w.path === '/api/ad-hoc-tasks/2/chat').pop().body)
    expect(answerBody.question, '答复框同一接口同一字段，内容含换行').toBe('上限按 20 家\n先按 20 家灰度')
    console.log('[实测·X1 临时需求] 对话框+待确认卡两处 textarea：Ctrl+Enter 真换行零请求，Enter 发送 →', JSON.stringify(posts(writes)))
  })

  test('X1 供应商工作台对话区（textarea）：Ctrl+Enter 换行零请求，Enter 才提问', async ({ page }) => {
    const writes = await mockProduct(page)
    await page.goto('/supplier?job=41')
    const chat = page.locator('textarea[data-workbench-chat-input]')
    await expect(chat).toBeVisible({ timeout: 30_000 })
    await chat.click()
    await chat.pressSequentially('现在卡在哪个节点')
    await chat.press('Control+Enter')
    await expect(chat, 'Ctrl+Enter 必须真换行').toHaveValue('现在卡在哪个节点\n')
    expect(writes, `工作台对话区 Ctrl+Enter 零请求，实际=${JSON.stringify(posts(writes))}`).toHaveLength(0)
    await chat.pressSequentially('为什么')
    await chat.press('Enter')
    await expect.poll(() => writes.length, { timeout: 10_000, message: '裸 Enter 应发出提问' }).toBe(1)
    expect(JSON.parse(writes[0].body).question, '同一接口同一字段，内容含换行').toBe('现在卡在哪个节点\n为什么')
    console.log('[实测·X1 工作台] 对话区 textarea →', JSON.stringify(posts(writes)))
  })

  test('X1 员工对话（textarea）：Ctrl+Enter 换行零请求，Enter 仍发送、请求体字段不变', async ({ page }) => {
    const writes = await mockProduct(page)
    await page.goto('/employees?employee=7')
    const box = page.locator('textarea[data-emp-chat-input]')
    await expect(box).toBeVisible({ timeout: 30_000 })
    const before = writes.length   // 页面加载期可能有回执类 POST，基线扣除
    await box.click()
    await box.pressSequentially('帮我看下接口文档')
    await box.press('Control+Enter')
    await expect(box, 'Ctrl+Enter 必须真换行').toHaveValue('帮我看下接口文档\n')
    expect(writes.length, `员工对话 Ctrl+Enter 零请求，实际=${JSON.stringify(posts(writes))}`).toBe(before)
    await box.pressSequentially('重点看取消字段')
    await box.press('Enter')
    await expect.poll(() => posts(writes).filter(p => p === 'POST /api/employees/7/chat'), { timeout: 10_000 })
      .toContain('POST /api/employees/7/chat')
    const w = writes.find(x => x.path === '/api/employees/7/chat')
    expect(JSON.parse(w.body).text, '同一接口同一字段（text），内容含换行').toBe('帮我看下接口文档\n重点看取消字段')
    await expect(box, '发送后清空').toHaveValue('')
    console.log('[实测·X1 员工对话] textarea →', JSON.stringify(posts(writes)))
  })
})
