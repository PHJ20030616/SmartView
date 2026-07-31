import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
});

Object.defineProperty(window, "matchMedia", {
  configurable: true,
  value: () => ({
    matches: false,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  }),
});

// Ant Design 的响应式布局会在 jsdom 中访问 ResizeObserver；
// 测试只需验证组件渲染和交互，不需要真实的尺寸观测能力，因此提供最小兼容实现。
if (!("ResizeObserver" in window)) {
  class ResizeObserverMock {
    observe() {
      return undefined;
    }

    unobserve() {
      return undefined;
    }

    disconnect() {
      return undefined;
    }
  }

  Object.defineProperty(window, "ResizeObserver", {
    configurable: true,
    value: ResizeObserverMock,
  });
}
