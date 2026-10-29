import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { readFileSync } from 'node:fs';

// 版本号唯一来源：package.json，发版时只改这一处
const pkg = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf-8'));

export default defineConfig({
    plugins: [vue()],
    define: {
        __APP_VERSION__: JSON.stringify(pkg.version)
    },
    server: {
        port: 5173,
        // 联调代理：/api → 后端 8080（后端 context-path 已是 /api，不 rewrite）
        proxy: { '/api': { target: 'http://localhost:8080', changeOrigin: true } }
    }
});
