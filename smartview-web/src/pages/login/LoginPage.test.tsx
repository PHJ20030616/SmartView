import { App as AntApp } from "antd";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
  useLocation,
} from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "../../features/auth";
import * as authService from "../../features/auth/authService";
import {
  clearAuthSession,
  resetMemorySessionForTest,
} from "../../features/auth/authStorage";
import type { LoginData } from "../../features/auth/authTypes";
import LoginPage from "./LoginPage";

vi.mock("../../features/auth/authService", () => ({
  login: vi.fn(),
  register: vi.fn(),
}));

const loginData: LoginData = {
  token: "mock-token-smart-user",
  tokenType: "Bearer",
  expiresIn: 7200,
  user: {
    id: "1",
    username: "smart-user",
    nickname: "小智",
    status: "ACTIVE",
  },
};

function CurrentLocation() {
  const location = useLocation();
  return <span>{`${location.pathname}${location.search}`}</span>;
}

function renderLogin(initialEntry: string | {
  pathname: string;
  search?: string;
  state?: unknown;
}) {
  const router = createMemoryRouter(
    [
      {
        element: (
          <AuthProvider>
            <Outlet />
          </AuthProvider>
        ),
        children: [
          { path: "/login", element: <LoginPage /> },
          { path: "/", element: <CurrentLocation /> },
          { path: "/resume", element: <CurrentLocation /> },
        ],
      },
    ],
    { initialEntries: [initialEntry] },
  );

  render(
    <AntApp>
      <RouterProvider router={router} />
    </AntApp>,
  );

  return router;
}

async function fillLoginForm() {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("用户名"), "smart-user");
  await user.type(screen.getByLabelText("密码"), "secret123");
  return user;
}

describe("登录页面", () => {
  beforeEach(() => {
    localStorage.clear();
    resetMemorySessionForTest();
    clearAuthSession();
    vi.clearAllMocks();
  });

  it("校验必填字段并默认保持登录状态", async () => {
    const user = userEvent.setup();
    renderLogin("/login");

    expect(screen.getByRole("checkbox", { name: "保持登录状态" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByText("请输入用户名")).toBeInTheDocument();
    expect(screen.getByText("请输入密码")).toBeInTheDocument();
    expect(authService.login).not.toHaveBeenCalled();
  });

  it("按去除首尾空格后的用户名和 UTF-8 字节数校验", async () => {
    const user = userEvent.setup();
    renderLogin("/login");

    await user.type(screen.getByLabelText("用户名"), "  a  ");
    await user.type(screen.getByLabelText("密码"), "测".repeat(25));
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(
      await screen.findByText("用户名至少需要 3 个字符"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("密码的 UTF-8 编码不能超过 72 字节"),
    ).toBeInTheDocument();
    expect(authService.login).not.toHaveBeenCalled();
  });

  it("提交期间锁定登录按钮以防止重复请求", async () => {
    let resolveLogin!: (value: LoginData) => void;
    vi.mocked(authService.login).mockImplementation(
      () => new Promise((resolve) => {
        resolveLogin = resolve;
      }),
    );
    renderLogin("/login");
    const user = await fillLoginForm();

    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(screen.getByRole("button", { name: "登录" })).toBeDisabled();
    resolveLogin(loginData);
    await waitFor(() => {
      expect(screen.queryByRole("button", { name: "登录" })).not.toBeInTheDocument();
    });
  });

  it("登录成功后返回受信任的原目标地址", async () => {
    vi.mocked(authService.login).mockResolvedValue(loginData);
    const router = renderLogin({
      pathname: "/login",
      state: { from: "/resume?tab=latest" },
    });
    const user = await fillLoginForm();

    await user.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/resume");
      expect(router.state.location.search).toBe("?tab=latest");
    });
  });

  it("持久化失败时提示当前登录仅在本页会话有效", async () => {
    vi.mocked(authService.login).mockResolvedValue(loginData);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("存储空间不可用");
    });
    renderLogin("/login");
    const user = await fillLoginForm();

    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(
      await screen.findByText("当前为临时登录，刷新后需要重新登录"),
    ).toBeInTheDocument();
  });

  it("Token 失效跳转后显示重新登录提示", () => {
    renderLogin("/login?reason=expired");

    expect(
      screen.getByText("登录状态已失效，请重新登录"),
    ).toBeInTheDocument();
  });
});
