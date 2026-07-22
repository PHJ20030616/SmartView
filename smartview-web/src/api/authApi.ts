import { fetcher } from './client';

/**
 * 认证相关 API
 * 严格按照 OpenAPI 契约生成，类型安全
 */
export const authApi = {
  // 用户注册
  register: fetcher.path('/auth/register').method('post').create(),

  // 用户登录
  login: fetcher.path('/auth/login').method('post').create(),

  // 获取当前登录用户信息
  getCurrentUser: fetcher.path('/users/me').method('get').create(),
};
