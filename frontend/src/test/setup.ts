import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeAll, vi } from 'vitest';

// 每个用例结束后清理 DOM 与 Cookie，避免 CSRF / 登录态在用例之间串味。
afterEach(() => {
  cleanup();
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
});

/**
 * antd 的 Table / Tabs 依赖 `ResizeObserver` 与 `matchMedia`，jsdom 两者都没实现。
 * 不补的话组件会在 commit 阶段抛 `ReferenceError: ResizeObserver is not defined`，
 * 看起来像被测组件的 bug，实际是测试环境缺失——所以统一在这里补桩。
 */
beforeAll(() => {
  if (!('ResizeObserver' in globalThis)) {
    class ResizeObserverStub {
      observe(): void {}
      unobserve(): void {}
      disconnect(): void {}
    }
    Object.defineProperty(globalThis, 'ResizeObserver', { writable: true, value: ResizeObserverStub });
  }

  if (!window.matchMedia) {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string) => ({
        matches: false,
        media: query,
        onchange: null,
        addListener: vi.fn(),
        removeListener: vi.fn(),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
        dispatchEvent: vi.fn(),
      }),
    });
  }
});
