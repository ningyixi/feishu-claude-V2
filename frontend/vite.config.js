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
      interval: 1000,
    },
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        // 静默处理代理错误，后端未启动时不刷屏
        configure: (proxy) => {
          proxy.on('error', () => {})
        },
      },
    },
  },
})
