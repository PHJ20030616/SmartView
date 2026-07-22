import {
  createContext,
  type PropsWithChildren,
  useContext,
  useMemo,
  useState,
} from "react";

import * as authService from "./authService";
import {
  clearAuthSession,
  readAuthSession,
  saveAuthSession,
} from "./authStorage";
import type {
  AuthSession,
  LoginRequest,
  UserInfo,
} from "./authTypes";

type AuthContextValue = {
  session: AuthSession | null;
  user: UserInfo | null;
  token: string | null;
  isAuthenticated: boolean;
  login: (
    credentials: LoginRequest,
    persistent: boolean,
  ) => Promise<{ persisted: boolean }>;
  logout: () => void;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<AuthSession | null>(() =>
    readAuthSession(),
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      user: session?.user ?? null,
      token: session?.token ?? null,
      isAuthenticated: session !== null,
      login: async (credentials, persistent) => {
        const loginData = await authService.login(credentials);
        const nextSession: AuthSession = {
          token: loginData.token,
          user: loginData.user,
        };
        const result = saveAuthSession(nextSession, persistent);
        setSession(nextSession);
        return result;
      },
      logout: () => {
        clearAuthSession();
        setSession(null);
      },
    }),
    [session],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth 必须在 AuthProvider 内使用");
  }

  return context;
}
