import { Fetcher } from 'openapi-typescript-fetch';
import type { paths } from './generated/schema';

// 创建类型安全的 fetcher 实例，绑定到 OpenAPI 契约
const fetcher = Fetcher.for<paths>();

// 配置基础 URL 和默认请求头
fetcher.configure({
  baseUrl: '/api',
  init: {
    headers: {
      'Content-Type': 'application/json',
    },
  },
  use: [
    // 请求拦截器：添加 JWT Token
    async (url, init, next) => {
      const token = localStorage.getItem('token');
      if (token) {
        init.headers.set('Authorization', `Bearer ${token}`);
      }
      return next(url, init);
    },
    // 响应拦截器：处理 401 未授权
    async (url, init, next) => {
      const response = await next(url, init);

      if (response.status === 401) {
        // 清除过期的认证信息
        localStorage.removeItem('token');
        localStorage.removeItem('user');

        // 跳转到登录页
        if (!window.location.pathname.startsWith('/auth')) {
          window.location.href = '/auth/login?expired=true';
        }
      }

      return response;
    },
  ],
});

export { fetcher };
