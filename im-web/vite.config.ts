import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import fs from 'node:fs';
import path from 'node:path';

const staticOutDir = path.resolve(__dirname, '../im-server/src/main/resources/static');
const targetClassesDir = path.resolve(__dirname, '../im-server/target/classes');
const targetStaticDir = path.resolve(targetClassesDir, 'static');

function syncTargetStaticPlugin() {
  return {
    name: 'sync-im-server-target-static',
    closeBundle() {
      // 同步输出到 im-server/target/classes/static，确保 IDEA 运行中的 ServerApplication 无需重启即可强刷生效
      if (fs.existsSync(targetClassesDir)) {
        fs.rmSync(targetStaticDir, { recursive: true, force: true });
        fs.cpSync(staticOutDir, targetStaticDir, { recursive: true });
      }
    }
  };
}

export default defineConfig({
  plugins: [vue(), syncTargetStaticPlugin()],
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
    outDir: staticOutDir,
    emptyOutDir: true
  }
});
