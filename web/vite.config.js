import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  base: '/designer/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    // 直接输出到 Spring Boot 的静态资源目录，打包后由 /designer/ 访问
    outDir: '../example/src/main/resources/static/designer',
    emptyOutDir: true
  },
  server: {
    port: 5173,
    proxy: {
      '/designer/nodes': 'http://localhost:18080',
      '/designer/example': 'http://localhost:18080'
    }
  }
})
