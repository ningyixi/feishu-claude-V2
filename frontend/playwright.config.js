import { defineConfig } from '@playwright/test'
import { existsSync } from 'node:fs'

/**
 * 可复现的本地跑法（11-23-adhoc-append-sources X2）——**不需要任何魔法环境变量**：
 *
 *   cd frontend && npx playwright test
 *
 * - baseURL 默认 `http://localhost:3000`（与 vite.config.js 的 server.port 一致）；
 *   仍可用 `UI_BASE_URL=...` 覆盖（指到别的预览环境时 webServer 自动让位，不会另起本地 vite）。
 * - webServer 自动执行 `npm run dev` 拉起 vite；本机 3000 端口已有 dev server 时
 *   `reuseExistingServer: true` 直接复用，不冲突、不双起。
 * - `CHROMIUM_PATH` 未设置时自动探测本机已安装的 Chrome / Edge（本机 ms-playwright 缓存目录为空也能跑）；
 *   都没有才落回 Playwright 自带下载（npx playwright install chromium）。
 *   注意（如实说明）：executablePath 是全局 launchOptions——glass-material.spec 里手动 `firefox.launch()`
 *   的用例在本机（无 Firefox 二进制）本来就跑不过，探测到的 Chrome 会让它以另一种方式失败；
 *   要跑该用例请先 `npx playwright install firefox` 并显式设 `CHROMIUM_PATH=`（空值走自带浏览器）。
 */

/** CHROMIUM_PATH 缺省时的浏览器探测清单（Windows 常见安装位 + LOCALAPPDATA 用户级安装） */
const BROWSER_CANDIDATES = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  process.env.LOCALAPPDATA ? `${process.env.LOCALAPPDATA}/Google/Chrome/Application/chrome.exe` : null,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  '/usr/bin/google-chrome',          // Linux CI 兜底
  '/usr/bin/microsoft-edge',
].filter(Boolean)

const executablePath = process.env.CHROMIUM_PATH
  || BROWSER_CANDIDATES.find(p => existsSync(p))
  || undefined   // 都没探测到 → 用 Playwright 自带浏览器

const baseURL = process.env.UI_BASE_URL || 'http://localhost:3000'

export default defineConfig({
  testDir: './tests', testMatch: '**/*.spec.js', timeout: 30000, retries: 0, workers: 1,
  outputDir: '../target/ui-results', reporter: [['list'], ['html', { outputFolder: '../target/ui-report', open: 'never' }]],
  // 指到外部预览环境（UI_BASE_URL）时不接管起服；默认本地跑时自动起/复用 vite dev
  webServer: process.env.UI_BASE_URL ? undefined : {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: true,   // 3000 已有 dev server（日常开发常开）→ 直接复用
    timeout: 180_000,            // Z: 网络盘冷启动偏慢，放宽到 3 分钟
  },
  use: {
    baseURL, headless: true, screenshot: 'only-on-failure', trace: 'retain-on-failure',
    launchOptions: executablePath ? { executablePath, args: ['--no-sandbox', '--disable-dev-shm-usage'] } : {},
  },
})
