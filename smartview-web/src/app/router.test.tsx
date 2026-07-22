import { render, screen, waitFor } from "@testing-library/react";
import {
  createMemoryRouter,
  RouterProvider,
} from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";

import {
  resetMemorySessionForTest,
  saveAuthSession,
} from "../features/auth/authStorage";
import { appRoutes } from "./router";

const session = {
  token: "mock-token-smart-user",
  user: {
    id: "1",
    username: "smart-user",
    nickname: "小智",
    status: "ACTIVE" as const,
  },
};

describe("应用认证路由", () => {
  beforeEach(() => {
    localStorage.clear();
    resetMemorySessionForTest();
  });

  it("未登录访问业务页会跳转登录页并保留目标地址", async () => {
    const router = createMemoryRouter(appRoutes, {
      initialEntries: ["/resume?tab=latest"],
    });
    render(<RouterProvider router={router} />);

    expect(
      await screen.findByRole("heading", { name: /登录|欢迎回来/ }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/login");
    expect(router.state.location.state).toEqual({
      from: "/resume?tab=latest",
    });
  });

  it.each(["/login", "/register"])(
    "已登录访问公开认证页 %s 会进入首页",
    async (path) => {
      saveAuthSession(session, true);
      const router = createMemoryRouter(appRoutes, {
        initialEntries: [path],
      });
      render(<RouterProvider router={router} />);

      await waitFor(() => {
        expect(router.state.location.pathname).toBe("/");
      });
    },
  );

  it("未知路径会进入受保护首页而不是绕过认证", async () => {
    const router = createMemoryRouter(appRoutes, {
      initialEntries: ["/unknown"],
    });
    render(<RouterProvider router={router} />);

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/login");
    });
  });
});
