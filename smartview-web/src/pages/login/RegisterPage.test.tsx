import { App as AntApp } from "antd";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryRouter,
  Outlet,
  RouterProvider,
} from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "../../features/auth";
import * as authService from "../../features/auth/authService";
import {
  clearAuthSession,
  resetMemorySessionForTest,
} from "../../features/auth/authStorage";
import LoginPage from "./LoginPage";
import RegisterPage from "./RegisterPage";

vi.mock("../../features/auth/authService", () => ({
  login: vi.fn(),
  register: vi.fn(),
}));

function renderRegister() {
  const router = createMemoryRouter(
    [
      {
        element: (
          <AuthProvider>
            <Outlet />
          </AuthProvider>
        ),
        children: [
          { path: "/register", element: <RegisterPage /> },
          { path: "/login", element: <LoginPage /> },
        ],
      },
    ],
    { initialEntries: ["/register"] },
  );

  render(
    <AntApp>
      <RouterProvider router={router} />
    </AntApp>,
  );

  return router;
}

describe("注册页面", () => {
  beforeEach(() => {
    localStorage.clear();
    resetMemorySessionForTest();
    clearAuthSession();
    vi.clearAllMocks();
  });

  it("注册字段严格按照确认顺序竖向排列", () => {
    renderRegister();
    const labels = [
      "用户名",
      "昵称",
      "邮箱（选填）",
      "手机号（选填）",
      "密码",
      "确认密码",
    ];
    const inputs = labels.map((label) => screen.getByLabelText(label));

    for (let index = 1; index < inputs.length; index += 1) {
      expect(
        inputs[index - 1].compareDocumentPosition(inputs[index])
          & Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBeTruthy();
    }
  });

  it("空表单会显示全部必填字段的中文提示", async () => {
    const user = userEvent.setup();
    renderRegister();

    await user.click(screen.getByRole("button", { name: "注册账户" }));

    expect(await screen.findByText("请输入用户名")).toBeInTheDocument();
    expect(screen.getByText("请输入昵称")).toBeInTheDocument();
    expect(screen.getByText("请输入密码")).toBeInTheDocument();
    expect(screen.getByText("请再次输入密码")).toBeInTheDocument();
    expect(authService.register).not.toHaveBeenCalled();
  });

  it("校验用户名、邮箱、手机号和确认密码格式", async () => {
    const user = userEvent.setup();
    renderRegister();

    await user.type(screen.getByLabelText("用户名"), "ab");
    await user.type(screen.getByLabelText("昵称"), "小智");
    await user.type(screen.getByLabelText("邮箱（选填）"), "invalid-email");
    await user.type(screen.getByLabelText("手机号（选填）"), "123456");
    await user.type(screen.getByLabelText("密码"), "secret123");
    await user.type(screen.getByLabelText("确认密码"), "secret456");
    await user.click(screen.getByRole("button", { name: "注册账户" }));

    expect(
      await screen.findByText("用户名至少需要 3 个字符"),
    ).toBeInTheDocument();
    expect(screen.getByText("请输入正确的邮箱地址")).toBeInTheDocument();
    expect(screen.getByText("请输入正确的 11 位手机号")).toBeInTheDocument();
    expect(screen.getByText("两次输入的密码不一致")).toBeInTheDocument();
    expect(authService.register).not.toHaveBeenCalled();
  });

  it("按去除首尾空格后的必填值和 UTF-8 字节数校验", async () => {
    const user = userEvent.setup();
    renderRegister();

    await user.type(screen.getByLabelText("用户名"), "  a  ");
    await user.type(screen.getByLabelText("昵称"), "   ");
    await user.type(screen.getByLabelText("密码"), "测".repeat(25));
    await user.type(screen.getByLabelText("确认密码"), "测".repeat(25));
    await user.click(screen.getByRole("button", { name: "注册账户" }));

    expect(
      await screen.findByText("用户名至少需要 3 个字符"),
    ).toBeInTheDocument();
    expect(screen.getByText("请输入昵称")).toBeInTheDocument();
    expect(
      screen.getByText("密码的 UTF-8 编码不能超过 72 字节"),
    ).toBeInTheDocument();
    expect(authService.register).not.toHaveBeenCalled();
  });

  it("注册成功后返回登录页并预填用户名", async () => {
    vi.mocked(authService.register).mockResolvedValue({
      id: "1",
      username: "smart-user",
      nickname: "小智",
      status: "ACTIVE",
    });
    const user = userEvent.setup();
    const router = renderRegister();

    await user.type(screen.getByLabelText("用户名"), "smart-user");
    await user.type(screen.getByLabelText("昵称"), "小智");
    await user.type(screen.getByLabelText("密码"), "secret123");
    await user.type(screen.getByLabelText("确认密码"), "secret123");
    await user.click(screen.getByRole("button", { name: "注册账户" }));

    expect(await screen.findByText("注册成功，请登录")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/login");
    expect(screen.getByLabelText("用户名")).toHaveValue("smart-user");
    expect(authService.register).toHaveBeenCalledWith({
      username: "smart-user",
      nickname: "小智",
      password: "secret123",
    });
  });
});
