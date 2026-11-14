import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// 每个用例结束后清理 DOM 与 Cookie，避免 CSRF / 登录态在用例之间串味。
afterEach(() => {
  cleanup();
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
});
