import type { ApiResult, FileUploadResp } from '../types/im';

let unauthorizedHandler: ((message: string) => void) | null = null;

export function setUnauthorizedHandler(handler: (message: string) => void) {
  unauthorizedHandler = handler;
}

export async function apiRequest<T>(
  path: string,
  token: string,
  options: RequestInit = {}
): Promise<T> {
  const headers: Record<string, string> = {
    ...(options.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
    ...((options.headers as Record<string, string>) || {})
  };
  if (token) {
    headers['Authorization'] = 'Bearer ' + token;
  }
  const res = await fetch(path, { ...options, headers });
  const json = (await res.json()) as ApiResult<T>;
  if (json.code !== 200) {
    if (token && json.code === 4010 && unauthorizedHandler) {
      unauthorizedHandler(json.message || '登录状态已失效，请重新登录');
    }
    throw new Error(json.message || '请求失败');
  }
  return json.data;
}

/**
 * 浏览器原生 WebCrypto 计算文件 SHA-256（支持零流量秒传检测）
 */
export async function computeFileSha256(file: File): Promise<string | null> {
  try {
    if (!window.crypto?.subtle) return null;
    const buffer = await file.arrayBuffer();
    const digest = await window.crypto.subtle.digest('SHA-256', buffer);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  } catch {
    return null;
  }
}

/**
 * 秒传探测 + 上传一体流程：先用 SHA-256 问服务端是否已存在，未命中再上传字节流
 */
export async function uploadFileWithInstantCheck(
  file: File,
  token: string
): Promise<FileUploadResp> {
  const sha256 = await computeFileSha256(file);
  if (sha256) {
    try {
      const hit = await apiRequest<FileUploadResp | null>(
        `/api/files/instant-check?sha256=${encodeURIComponent(sha256)}`,
        token
      );
      if (hit) {
        return { ...hit, fileName: file.name || hit.fileName, instantHit: true };
      }
    } catch {
      // 秒传探测异常时降级走普通上传
    }
  }

  const form = new FormData();
  form.append('file', file);
  return apiRequest<FileUploadResp>('/api/files/upload', token, {
    method: 'POST',
    body: form
  });
}
