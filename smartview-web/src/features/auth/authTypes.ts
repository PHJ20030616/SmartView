import type { components } from "../../api/generated/schema";

export type RegisterRequest = components["schemas"]["RegisterRequest"];
export type LoginRequest = components["schemas"]["LoginRequest"];
export type LoginData = components["schemas"]["LoginData"];
export type UserInfo = components["schemas"]["UserInfo"];

export type AuthSession = {
  token: string;
  user: UserInfo;
};

export type LoginFormValues = LoginRequest & {
  remember: boolean;
};

export type RegisterFormValues = RegisterRequest & {
  confirmPassword: string;
};
