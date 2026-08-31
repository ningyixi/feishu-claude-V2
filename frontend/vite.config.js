import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    port: 3000,
    // 修复 Z: 盘等网络驱动器不支持 fs.watch 的问题
    watch: {
      usePolling: true,
      interval: 2000,
      // 忽略 node_modules 和大型目录，减少网络盘 stat 失败
      ignored: ['**/node_modules/**', '**/.git/**'],
    },
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        configure: (proxy) => {
          proxy.on('error', () => {})
        },
      },
    },
  },
})
