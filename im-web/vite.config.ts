import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      },
      '/uploads': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    // 打包产物直接输出到 im-server 静态资源目录，后端访问 http://localhost:8080/ 即可零配置运行
    outDir: path.resolve(__dirname, '../im-server/src/main/resources/static'),
    emptyOutDir: true
  }
});
