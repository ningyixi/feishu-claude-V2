import { test, expect } from '@playwright/test'
import { mockProduct } from './fixtures'

/**
 * 「临时需求」暴露同站点 BFS 递归抓取（11-24-adhoc-site-crawl SC1/SC2）页面级验证：
 * 1) 新建表单有「URL 按同站点递归抓取（BFS，≤200 页）」总开关，文案写清作用于本次提交的全部 URL 行，
 *    勾选/不勾选随创建请求提交 siteCrawl:true/false；
 * 2) 「📎 继续补充材料」面板同一开关，勾选后 append-sources 请求带 siteCrawl:true；
 * 3) 站点条目行如实显示「站点抓取中：已入 N 页 / 共发现 M 页」+「取消抓取」打到现成 cancelCrawl 端点，
 *    完成后显示总页数与产物目录；失败/跳过页列出前 3 条原因（共 5 页时明示总量，不静默丢页）。
 * 全部走 page.route 夹具，不打真实后端、不访问外网。
 */

const taskRow = { id: 2, name: '整站抓取任务', kbCode: 'adhoc-2', instanceId: 2, status: 'READY', wikiStatus: 'READY', error: '', running: false, blocked: false, createdAt: '2026-11-24T09:00:00', updatedAt: '2026-11-24T09:30:00' }

function detailWith(overrides) {
  return {
    ...taskRow, instanceName: '接口分析 · Research', docSources: 'http://127.0.0.1:9/docs/start',
    steps: [{ key: 'INGEST', status: 'DONE' }, { key: 'WIKI', status: 'DONE' }],
    wiki: { status: 'READY', revision: 77, sourceCount: 1, pageCount: 1, phase: '', error: '' },
    kbEntries: [], pendingQuestion: '', notifyError: '', pendingMaterialCount: 0, pendingMaterialTitles: [],
    ...overrides,
  }
}

// ===== 1) 新建表单总开关 =====

test('新建表单：站点递归总开关写清作用范围，勾选随创建请求提交 siteCrawl:true（不勾选为 false）', async ({ page }) => {
  const writes = await mockProduct(page)

  // 第一轮：不勾选 → siteCrawl:false（单页语义，AC2 的页面口径）
  await page.goto('/ad-hoc?view=create')
  const cb = page.locator('[data-site-crawl]')
  await expect(cb).toBeVisible()
  await expect(cb).not.toBeChecked()
  await expect(page.locator('label', { has: cb })).toContainText('URL 按同站点递归抓取（BFS，≤200 页）')
  // 作用范围必须写清：作用于全部 URL 行，别让人以为只勾了某一行；本地文件不受影响
  const createCard = page.locator('.card', { has: cb })
  await expect(createCard).toContainText('全部 URL 行')
  await expect(createCard).toContainText('本地文件 / PDF / 笔记不受影响')

  await page.locator('textarea').fill('http://127.0.0.1:9/docs/start')
  await page.selectOption('select', '2')   // 1 号实例在 fixtures 里被员工独占（禁用项）
  await page.locator('button', { hasText: '创建并启动' }).click()
  await page.waitForTimeout(600)
  let create = writes.find(w => w.path === '/api/ad-hoc-tasks' && w.method === 'POST')
  expect(create, '创建请求应已发出').toBeTruthy()
  expect(JSON.parse(create.body).siteCrawl, '不勾选 → siteCrawl:false').toBe(false)

  // 第二轮：勾选 → siteCrawl:true
  await page.goto('/ad-hoc?view=create')
  await page.locator('[data-site-crawl]').check()
  await page.locator('textarea').fill('http://127.0.0.1:9/docs/start')
  await page.selectOption('select', '2')
  await page.locator('button', { hasText: '创建并启动' }).click()
  await page.waitForTimeout(600)
  const siteCreates = writes.filter(w => w.path === '/api/ad-hoc-tasks' && w.method === 'POST')
  expect(siteCreates.length).toBe(2)
  const body = JSON.parse(siteCreates[1].body)
  expect(body.siteCrawl, '勾选 → siteCrawl:true').toBe(true)
  expect(body.docSources).toContain('http://127.0.0.1:9/docs/start')
  console.log('[SC1-新建] 第二次创建请求体 =', siteCreates[1].body)
})

// ===== 2) 「📎 继续补充材料」面板同一开关 =====

test('追加面板：勾选后 append-sources 请求带 siteCrawl:true，未填 URL 时不携带', async ({ page }) => {
  const writes = await mockProduct(page)
  await page.route('**/api/**', async route => {
    const p = new URL(route.request().url()).pathname
    if (p === '/api/ad-hoc-tasks') return route.fulfill({ json: [taskRow] })
    if (p === '/api/ad-hoc-tasks/2') return route.fulfill({ json: detailWith({}) })
    if (p === '/api/ad-hoc-tasks/2/chat') return route.fulfill({ json: [] })
    if (p.startsWith('/api/supplier-kb/')) return route.fulfill({ json: [] })
    return route.fallback()
  })

  await page.goto('/ad-hoc?task=2')
  const panel = page.locator('[data-append-sources]')
  await expect(panel).toBeVisible()
  const cb = page.locator('[data-append-site-crawl]')
  await expect(cb).toBeVisible()
  await expect(panel).toContainText('URL 按同站点递归抓取（BFS，≤200 页）')
  await expect(panel).toContainText('全部 URL 行')

  await page.locator('[data-append-docs]').fill('http://127.0.0.1:9/docs/api-v2')
  await cb.check()
  await page.locator('[data-append-submit]').click()
  await page.waitForTimeout(600)
  const req = writes.find(w => w.path === '/api/ad-hoc-tasks/2/append-sources')
  expect(req, '追加请求应已发出').toBeTruthy()
  const body = JSON.parse(req.body)
  expect(body.siteCrawl).toBe(true)
  expect(body.docSources).toContain('http://127.0.0.1:9/docs/api-v2')
  console.log('[SC1-追加] 请求体 =', req.body)
})

// ===== 3) 站点条目行：进度 / 取消 / 完成总页数与产物目录 / 失败页前 3 条原因 =====

const failedPages = [
  { id: 31, url: 'http://127.0.0.1:9/docs/broken', title: 'b1', status: 'failed', error: 'HTTP 404: http://127.0.0.1:9/docs/broken' },
  { id: 32, url: 'http://127.0.0.1:9/docs/slow500', title: 'b2', status: 'failed', error: 'HTTP 500: http://127.0.0.1:9/docs/slow500' },
  { id: 33, url: 'http://127.0.0.1:9/docs/spa', title: 'b3', status: 'failed', error: '正文不可读取（疑似 JS 动态渲染页面，静态抓取只能拿到空壳）' },
  { id: 34, url: 'http://127.0.0.1:9/docs/b4', title: 'b4', status: 'failed', error: 'HTTP 404: b4' },
  { id: 35, url: 'http://127.0.0.1:9/docs/b5', title: 'b5', status: 'failed', error: 'HTTP 404: b5' },
  { id: 36, url: 'http://127.0.0.1:9/docs/ok1', title: 'ok', status: 'loaded', error: '' },
]

test('站点条目行：抓取中显示已入/共发现页数并可取消；完成显示总页数与产物目录；失败页列前 3 条原因', async ({ page }) => {
  const writes = await mockProduct(page)
  const crawling = detailWith({
    status: 'INGEST', wikiStatus: 'EMPTY', running: true, siteCrawl: true,
    steps: [{ key: 'INGEST', status: 'RUNNING' }, { key: 'WIKI', status: 'PENDING' }],
    wiki: { status: 'EMPTY', revision: null, sourceCount: 0, pageCount: 0, phase: '', error: '' },
    kbEntries: [
      { id: 9, title: '供应商文档站', type: 'url', status: 'PARSING', source: 'http://127.0.0.1:9/docs/start', error: '', mdPath: '', site: true, found: 8, loaded: 2, failed: 5, pending: 1 },
      { id: 10, title: '已抓完的站', type: 'url', status: 'DONE', source: 'http://127.0.0.1:9/done', error: '', mdPath: 'D:\\kb\\adhoc-2\\site-10\\index.md', site: true, found: 42, loaded: 40, failed: 2, pending: 0, partial: true },
    ],
  })
  await page.route('**/api/**', async route => {
    const p = new URL(route.request().url()).pathname
    if (p === '/api/ad-hoc-tasks') return route.fulfill({ json: [{ ...taskRow, status: 'INGEST', running: true }] })
    if (p === '/api/ad-hoc-tasks/2') return route.fulfill({ json: crawling })
    if (p === '/api/ad-hoc-tasks/2/chat') return route.fulfill({ json: [] })
    if (p === '/api/supplier-kb/adhoc-2/entries/9/pages') return route.fulfill({ json: failedPages })
    // 只拦 GET 查询；取消抓取等写操作放给 mockProduct（落入 writes 供断言"复用现成端点"）
    if (p.startsWith('/api/supplier-kb/') && route.request().method() === 'GET') return route.fulfill({ json: [] })
    return route.fallback()
  })

  await page.goto('/ad-hoc?task=2')
  const panel = page.locator('[data-site-crawl-panel]')
  await expect(panel).toBeVisible({ timeout: 30_000 })

  // 抓取中：已入 N 页 / 共发现 M 页（SC2 指定文案）+ 取消按钮
  const rows = panel.locator('[data-site-progress]')
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0).locator('[data-site-progress-text]')).toContainText('站点抓取中：已入 2 页 / 共发现 8 页')
  await expect(rows.nth(0).locator('[data-site-cancel]')).toBeVisible()

  // 失败/跳过页前 3 条原因（共 5 页 → 明示总量，不静默丢页）
  const reasons = rows.nth(0).locator('[data-site-fail-reasons]')
  await expect(reasons).toBeVisible()
  await expect(reasons).toContainText('共 5 页')
  await expect(reasons).toContainText('HTTP 404')
  await expect(reasons).toContainText('JS 动态渲染')
  const items = reasons.locator('div')
  await expect(items, '只列前 3 条原因').toHaveCount(3)

  // 取消 → 打到现成 cancelCrawl 端点（不新造后端）
  await rows.nth(0).locator('[data-site-cancel]').click()
  await page.waitForTimeout(500)
  const cancel = writes.find(w => w.path === '/api/supplier-kb/entries/9/cancel' && w.method === 'POST')
  expect(cancel, '取消抓取必须复用现成 /api/supplier-kb/entries/{id}/cancel').toBeTruthy()

  // 完成的站点条目：总页数 + 产物目录 + 部分导入如实标注
  await expect(rows.nth(1).locator('[data-site-progress-text]')).toContainText('共 42 页（载入 40，失败 2）')
  await expect(rows.nth(1).locator('[data-site-progress-text]')).toContainText('D:\\kb\\adhoc-2\\site-10')
  await expect(rows.nth(1).locator('[data-site-progress-text]')).toContainText('部分导入')
  console.log('[SC2-页面] 抓取中文案 =', (await rows.nth(0).locator('[data-site-progress-text]').textContent()).trim())
  console.log('[SC2-页面] 完成文案 =', (await rows.nth(1).locator('[data-site-progress-text]').textContent()).trim())
})
