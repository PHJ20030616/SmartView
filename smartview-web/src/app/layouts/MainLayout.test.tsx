import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  createMemoryRouter,
  RouterProvider,
} from "react-router-dom";
import { beforeEach, describe, expect, it } from "vitest";

import {
  readAuthSession,
  resetMemorySessionForTest,
  saveAuthSession,
} from "../../features/auth/authStorage";
import { appRoutes } from "../router";

const session = {
  token: "mock-token-smart-user",
  user: {
    id: "1",
    username: "smart-user",
    nickname: "小智",
    status: "ACTIVE" as const,
  },
};

describe("主布局认证操作", () => {
  beforeEach(() => {
    localStorage.clear();
    resetMemorySessionForTest();
  });

  it("显示当前用户并在退出后进入登录页", async () => {
    saveAuthSession(session, true);
    const router = createMemoryRouter(appRoutes, { initialEntries: ["/"] });
    const user = userEvent.setup();
    render(<RouterProvider router={router} />);

    expect(await screen.findByText("小智")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "退出登录" }));

    expect(readAuthSession()).toBeNull();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe("/login");
    });
  });
});
