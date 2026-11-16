import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// 前端单测：jsdom 环境 + 与 vite.config.ts 相同的 React 插件，
// 这样 JSX/TS 的转换规则和真实构建一致，不会出现「测试能过、构建报错」。
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: false,          // 测试里显式 import，避免 tsconfig 依赖全局类型
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    clearMocks: true,
  },
});
