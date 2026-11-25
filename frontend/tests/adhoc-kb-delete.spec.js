import { test, expect } from '@playwright/test'
import { mockProduct } from './fixtures'

/**
 * 临时需求·知识库删除单条文档（11-24-adhoc-kb-delete-entry）页面级验证：
 * 1) KD1 细粒度权限：临时需求 KbPanel 每条有「×」、无补充表单（不出现两套添加入口）、无阶段下拉、重解析保留；
 * 2) KD2 删除确认：普通条目 popconfirm / 站点条目 dialog（页数影响），文案含
 *    「当前 Wiki 版本仍含该内容，需重编译才生效」，确认后 DELETE 打到现成条目接口；
 * 3) KD3（修正版）删除不自动重编译——页面只显示「已有 N 项材料变更（移除）尚未编译进 Wiki」横幅，
 *    移除绝不被混进"新增材料"横幅；
 * 4) KD5 删到 0 条：保留旧 Wiki 版本 + 「材料已全部移除」可读提示；
 * 5) KD4 删过的来源重新追加 → toast 明示「已恢复该来源」；
 * 6) AC6 零回归：工作台 KbPanel（未传新 props）与接口建档结果卡（readOnly 旧粗开关）行为逐字不变。
 * 全部走 page.route 夹具，不打真实后端、不访问外网。
 */

const taskRow = { id: 2, name: '删材料任务', kbCode: 'adhoc-2', instanceId: 2, status: 'READY', wikiStatus: 'READY', error: '', running: false, blocked: false, createdAt: '2026-11-24T09:00:00', updatedAt: '2026-11-24T09:30:00' }

function detailWith(overrides) {
  return {
    ...taskRow, instanceName: '接口分析 · Research', docSources: 'https://docs.acme.com/site\nhttps://docs.acme.com/cancel',
    steps: [{ key: 'INGEST', status: 'DONE' }, { key: 'WIKI', status: 'DONE' }],
    wiki: { status: 'READY', revision: 77, sourceCount: 2, pageCount: 3, phase: '', error: '' },
    kbEntries: [], pendingQuestion: '', notifyError: '', siteCrawl: false,
    pendingMaterialCount: 0, pendingMaterialTitles: [], pendingRemovalCount: 0,
    ...overrides,
  }
}

const kbEntries = [
  { id: 201, kbCode: 'adhoc-2', title: '供应商文档站', type: 'url', source: 'https://docs.acme.com/site', status: 'DONE', contextStage: 'all', metaJson: '{"site":true,"found":120,"loaded":118,"failed":2,"pending":0}' },
  { id: 202, kbCode: 'adhoc-2', title: '取消政策单页', type: 'url', source: 'https://docs.acme.com/cancel', status: 'DONE', contextStage: 'all', metaJson: '{}' },
]

async function mockAdHoc(page, detailOverrides = {}, entries = kbEntries) {
  const writes = await mockProduct(page)
  await page.route('**/api/**', async route => {
    const req = route.request()
    // 写请求（DELETE /api/supplier-kb/entries/{id}、POST append-sources…）落到 mockProduct 记录进 writes
    if (req.method() !== 'GET') return route.fallback()
    const p = new URL(req.url()).pathname
    if (p === '/api/ad-hoc-tasks') return route.fulfill({ json: [taskRow] })
    if (p === '/api/ad-hoc-tasks/2') return route.fulfill({ json: detailWith(detailOverrides) })
    if (p === '/api/ad-hoc-tasks/2/chat') return route.fulfill({ json: [] })
    if (p === '/api/supplier-kb/adhoc-2/entries') return route.fulfill({ json: entries })
    if (p.startsWith('/api/supplier-kb/')) return route.fulfill({ json: [] })
    return route.fallback()
  })
  return writes
}

const kbPanel = page => page.locator('.card', { hasText: '📚 供应商知识库' }).first()

// ===== 1) KD1：临时需求知识库细粒度权限 =====

test('临时需求知识库：每条文档都有「×」，无补充表单/阶段下拉，重解析保留（KD1/AC1）', async ({ page }) => {
  await mockAdHoc(page)
  await page.goto('/ad-hoc?task=2')
  const panel = kbPanel(page)
  await expect(panel).toBeVisible()
  await expect(panel.locator('button[aria-label="删除该条目"]')).toHaveCount(2)
  // canAdd=false：补充表单不出现（添加入口只有上方「继续补充材料」面板，不出现两套）
  await expect(panel.locator('button', { hasText: '＋ 补充入库' })).toHaveCount(0)
  await expect(panel.locator('textarea')).toHaveCount(0)
  // canStage=false：阶段下拉不放开
  await expect(panel.locator('select[aria-label="材料使用阶段"]')).toHaveCount(0)
  // canReparse=true：重解析/产物校正保留
  await expect(panel.locator('button', { hasText: '🔄 重解析' })).toHaveCount(2)
  await expect(panel.locator('button', { hasText: '👁 产物/校正' })).toHaveCount(2)
  await expect(panel).toContainText('本任务材料：可删除 / 重解析')
})

// ===== 2) KD2：普通条目 popconfirm / 站点条目 dialog，文案说清后果 =====

test('普通条目删除：popconfirm 含「当前 Wiki 版本仍含该内容，需重编译才生效」，确认后 DELETE 打条目接口（KD2/AC1）', async ({ page }) => {
  const writes = await mockAdHoc(page)
  await page.goto('/ad-hoc?task=2')
  const row = page.locator('.anim-in', { hasText: '取消政策单页' }).first()
  await row.locator('button[aria-label="删除该条目"]').click()
  const pop = page.locator('.popconfirm-box')
  await expect(pop).toBeVisible()
  await expect(pop).toContainText('删除「取消政策单页」')
  await expect(pop).toContainText('解析产物（.md 文件）')
  await expect(pop).toContainText('当前 Wiki 版本仍含该内容，需重编译才生效')
  await pop.locator('.btn-danger', { hasText: '删除条目' }).click()
  await page.waitForTimeout(500)
  const del = writes.find(w => w.path === '/api/supplier-kb/entries/202' && w.method === 'DELETE')
  expect(del, '确认后必须打现成 DELETE /api/supplier-kb/entries/{id}').toBeTruthy()
  console.log('[KD2-普通] 删除请求 =', del.method, del.path)
})

test('站点条目删除：确认框是 dialog 且写清 site-<id>/ 目录与 200 页影响；取消不删（KD2/AC2）', async ({ page }) => {
  const writes = await mockAdHoc(page)
  await page.goto('/ad-hoc?task=2')
  const row = page.locator('.anim-in', { hasText: '供应商文档站' }).first()
  await row.locator('button[aria-label="删除该条目"]').click()
  const box = page.locator('.confirmation-box')
  await expect(box).toBeVisible()
  await expect(page.locator('.popconfirm-box')).toHaveCount(0)   // 站点条目必须是 dialog，不是 popconfirm
  await expect(box).toContainText('site-201/')
  await expect(box).toContainText('200 页')
  await expect(box).toContainText('当前 Wiki 版本仍含该内容，需重编译才生效')
  await box.locator('button', { hasText: '取消' }).click()
  await page.waitForTimeout(300)
  expect(writes.find(w => w.path === '/api/supplier-kb/entries/201'), '取消后不得发出删除请求').toBeFalsy()
})
// ===== 3) KD3（修正版）：删除只提示"待重编译"，不自动编译；移除不算成"新增" =====

test('删除后横幅显示「N 项材料变更（移除）尚未编译进 Wiki」，且不出现新增材料横幅（KD3）', async ({ page }) => {
  await mockAdHoc(page, { pendingRemovalCount: 1, pendingMaterialCount: 0 })
  await page.goto('/ad-hoc?task=2')
  const removal = page.locator('[data-removal-pending]')
  await expect(removal).toBeVisible()
  await expect(removal).toContainText('1 项材料变更（移除）尚未编译进 Wiki')
  await expect(removal).toContainText('编译 LLM Wiki')
  // 移除绝不能被算成"新增材料"——新增横幅不出现
  await expect(page.locator('[data-wiki-rebuilding]')).toHaveCount(0)
})

test('移除计数为 0 时不显示移除横幅（未删材料时无噪声）', async ({ page }) => {
  await mockAdHoc(page, { pendingRemovalCount: 0 })
  await page.goto('/ad-hoc?task=2')
  await expect(page.locator('[data-removal-pending]')).toHaveCount(0)
})

// ===== 4) KD5：删到 0 条来源 → 旧 Wiki 保留 + 可读提示 =====

test('删到 0 条来源：保留旧 Wiki 版本并提示「材料已全部移除」（KD5/AC5）', async ({ page }) => {
  await mockAdHoc(page, {
    pendingRemovalCount: 2,
    wiki: { status: 'READY', revision: 77, sourceCount: 2, pageCount: 3, phase: '', error: '' },
  }, [])
  await page.goto('/ad-hoc?task=2')
  const empty = page.locator('[data-kb-empty]')
  await expect(empty).toBeVisible()
  await expect(empty).toContainText('材料已全部移除')
  await expect(empty).toContainText('建议先通过「继续补充材料」补充材料')
  // 旧 Wiki 版本仍在（状态卡显示已就绪 + 版本号），没有被清空
  await expect(page.locator('text=版本 #77')).toBeVisible()
  await expect(page.locator('text=已就绪')).toBeVisible()
})

// ===== 5) KD4：删过的来源重新追加 → toast「已恢复该来源」 =====

test('删过的来源重新追加：restored 回传触发「已恢复该来源」提示（KD4/AC4）', async ({ page }) => {
  await mockAdHoc(page)
  // 覆盖 append-sources：模拟"这条 URL 之前被删过"→ 后端 restored 命中（本路由优先于 mockProduct，直接 fulfill）
  const reqs = []
  await page.route('**/api/ad-hoc-tasks/2/append-sources', route => {
    reqs.push(route.request().postData())
    return route.fulfill({ json: { added: ['https://docs.acme.com/cancel'], duplicated: [], restored: ['https://docs.acme.com/cancel'], wikiRebuildQueued: true } })
  })
  await page.goto('/ad-hoc?task=2')
  await page.locator('[data-append-docs]').fill('https://docs.acme.com/cancel')
  await page.locator('[data-append-submit]').click()
  await expect(page.locator('.toast')).toContainText('已恢复该来源', { timeout: 5000 })
  expect(reqs.length, '追加请求应已发出').toBeGreaterThan(0)
  expect(reqs[0]).toContain('https://docs.acme.com/cancel')
})

// ===== 6) AC6：工作台 KbPanel（未传新 props）零回归 =====

test('工作台 KbPanel 零回归：默认全开——补充表单/阶段下拉/删除/重解析/产物校正全在（AC6）', async ({ page }) => {
  const writes = await mockProduct(page)
  // 工作台复用 fixtures 的 acme 库（entries 里含 url DONE 与 chat DONE 两条）
  await page.goto('/supplier?job=41')
  const panel = kbPanel(page)
  await expect(panel).toBeVisible()
  // 未传任何 can* / readOnly → 逐字等价于今天的 readOnly=false（全开）
  await expect(panel.locator('button', { hasText: '＋ 补充入库' })).toHaveCount(1)
  await expect(panel.locator('select[aria-label="材料使用阶段"]')).toHaveCount(2)
  await expect(panel.locator('button[aria-label="删除该条目"]')).toHaveCount(2)
  // 顶部说明文案保持"统一补充入口"（非只读文案）
  await expect(panel).toContainText('统一补充入口')
})

// ===== 7) AC6：接口建档结果卡（readOnly 粗开关）仍全只读 =====

test('接口建档结果卡零回归：readOnly 仍隐藏全部写操作（AC6）', async ({ page }) => {
  await mockProduct(page)
  // QuickProbe 详情页复用 fixtures 的 acme 库；readOnly 粗开关必须继续隐藏补充表单/阶段/删除/重解析
  await page.route('**/api/quick-probes/**', route => {
    const p = new URL(route.request().url()).pathname
    if (/\/api\/quick-probes\/\d+$/.test(p)) {
      return route.fulfill({ json: { id: 9, name: '接口建档-演示', supplierCode: 'acme', kbCode: 'acme', phase: 'DONE', running: false, error: '', steps: [], wiki: { status: 'READY', revision: 3, sourceCount: 2, pages: [] } } })
    }
    return route.fallback()
  })
  await page.goto('/quick-probe?task=9')
  const panel = kbPanel(page)
  await expect(panel).toBeVisible()
  await expect(panel.locator('button', { hasText: '＋ 补充入库' })).toHaveCount(0)
  await expect(panel.locator('select[aria-label="材料使用阶段"]')).toHaveCount(0)
  await expect(panel.locator('button[aria-label="删除该条目"]')).toHaveCount(0)
  await expect(panel.locator('button', { hasText: '🔄 重解析' })).toHaveCount(0)
  // 只读文案：查看（非"产物/校正"）
  await expect(panel).toContainText('只读')
  await expect(panel.locator('button', { hasText: '👁 查看' })).toHaveCount(2)
})

