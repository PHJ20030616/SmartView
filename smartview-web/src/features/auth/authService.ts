import type {
  LoginData,
  LoginRequest,
  RegisterRequest,
  UserInfo,
} from "./authTypes";
import { authApi } from "../../api/authApi";

/**
 * 用户注册
 * 调用后端注册 API，解析响应并返回用户信息
 */
export async function register(request: RegisterRequest): Promise<UserInfo> {
  try {
    // 调用后端注册 API
    const response = await authApi.register(request);

    // 后端返回 ApiResponse<UserInfo> 包装结构
    // response.data 是 HTTP 响应体（UserResponse）
    // response.data.data 是实际的用户信息（UserInfo）
    const { data } = response.data;

    if (!data) {
      throw new Error('注册响应数据为空');
    }

    return data;
  } catch (error) {
    console.error('注册失败:', error);
    throw error;
  }
}

/**
 * 用户登录
 * 调用后端登录 API，解析响应并存储 token 和用户信息
 */
export async function login(request: LoginRequest): Promise<LoginData> {
  try {
    // 调用后端登录 API
    const response = await authApi.login(request);

    // 后端返回 ApiResponse<LoginData> 包装结构
    // response.data 是 HTTP 响应体（LoginResponse）
    // response.data.data 是实际的登录数据（LoginData，包含 token 和 user）
    const { data } = response.data;

    if (!data) {
      throw new Error('登录响应数据为空');
    }

    // 存储 token 和用户信息到 localStorage
    localStorage.setItem('token', data.token);
    localStorage.setItem('user', JSON.stringify(data.user));

    return data;
  } catch (error) {
    console.error('登录失败:', error);
    throw error;
  }
}
