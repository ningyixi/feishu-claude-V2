import { defineConfig } from '@playwright/test'
export default defineConfig({
  testDir: './tests', testMatch: '**/*.spec.js', timeout: 30000, retries: 0, workers: 1,
  outputDir: '../target/ui-results', reporter: [['list'], ['html', { outputFolder: '../target/ui-report', open: 'never' }]],
  use: { baseURL: process.env.UI_BASE_URL || 'http://feishu-ui-preview:5173', headless: true, screenshot: 'only-on-failure', trace: 'retain-on-failure', launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH, args: ['--no-sandbox', '--disable-dev-shm-usage'] } : {} },
})
