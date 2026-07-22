// BCrypt 只处理密码 UTF-8 编码后的前 72 字节，前端提前校验可避免用户误以为超出部分有效。
export const AUTH_PASSWORD_MAX_BYTES = 72;

export function trimTextValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

export function getUtf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}
