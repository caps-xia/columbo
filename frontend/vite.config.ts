import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// base=/app/：构建产物部署在 columbo 服务 /app/ 路径下（不与脚手架演示页 / 冲突）
export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: '/app/',
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    // Vite 8（rolldown）分包：业务代码与第三方分离，react 核心 / markdown 渲染链再各自成块——
    // 单独升级任一依赖时不失效其他块的长缓存；echarts 已懒加载不进首屏
    rolldownOptions: {
      output: {
        advancedChunks: {
          groups: [
            { name: 'react-core', test: /[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/ },
            // echarts+zrender 单独成组（不落 vendor）：只有 ChartView 懒加载块引用它，首屏不加载
            { name: 'echarts', test: /[\\/]node_modules[\\/](echarts|zrender)[\\/]/ },
            {
              name: 'markdown',
              test: /[\\/]node_modules[\\/](react-markdown|remark|rehype|unified|micromark|mdast|unist|hast|property-information|vfile|decode-named-character-reference)[\\/]/,
            },
            { name: 'vendor', test: /node_modules/ },
          ],
        },
      },
    },
  },
});
