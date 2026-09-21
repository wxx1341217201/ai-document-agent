import axios, { AxiosHeaders, type AxiosRequestConfig } from 'axios';
import type { ApiError, ApiResponse, FieldValidationError } from './contracts';

const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim();
const baseURL = (configuredBaseUrl || '/api/v1').replace(/\/$/, '');

export const apiClient = axios.create({
  baseURL,
  timeout: 15_000,
  headers: {
    Accept: 'application/json',
  },
});

export class ApiClientError extends Error implements ApiError {
  readonly httpStatus?: number;
  readonly code: string;
  readonly traceId?: string;
  readonly fieldErrors?: Record<string, string>;

  constructor(error: ApiError) {
    super(error.message);
    this.name = 'ApiClientError';
    this.httpStatus = error.httpStatus;
    this.code = error.code;
    this.traceId = error.traceId;
    this.fieldErrors = error.fieldErrors;
  }
}

interface ApiRequestConfig extends AxiosRequestConfig {
  traceId?: string;
}

export async function apiRequest<T>(config: ApiRequestConfig): Promise<T> {
  const headers = new AxiosHeaders();
  if (config.headers) {
    Object.entries(config.headers).forEach(([name, value]) => {
      if (value !== undefined) {
        headers.set(name, value);
      }
    });
  }
  if (config.traceId) {
    headers.set('X-Trace-Id', config.traceId);
  }

  if (config.data instanceof FormData) {
    headers.delete('Content-Type');
  } else if (!headers.has('Content-Type') && config.data !== undefined) {
    headers.set('Content-Type', 'application/json');
  }

  try {
    const response = await apiClient.request<ApiResponse<T>>({ ...config, headers });
    const body = response.data;

    if (!isApiResponse(body)) {
      throw new ApiClientError({
        httpStatus: response.status,
        code: 'INVALID_API_RESPONSE',
        message: '服务返回了无法识别的数据，请稍后重试。',
      });
    }

    if (body.code !== 'SUCCESS') {
      throw new ApiClientError({
        httpStatus: response.status,
        code: body.code,
        message: body.message || '请求未能完成，请稍后重试。',
        traceId: body.traceId,
        fieldErrors: toFieldErrors(body.data),
      });
    }

    return body.data;
  } catch (error) {
    if (error instanceof ApiClientError) {
      throw error;
    }
    throw new ApiClientError(normalizeApiError(error));
  }
}

/** Resolves a controlled backend URL, never a server filesystem path. */
export function apiUrl(path: string): string {
  return apiClient.getUri({ url: path });
}

export function normalizeApiError(error: unknown): ApiError {
  if (error instanceof ApiClientError) {
    return {
      httpStatus: error.httpStatus,
      code: error.code,
      message: error.message,
      traceId: error.traceId,
      fieldErrors: error.fieldErrors,
    };
  }

  if (axios.isAxiosError(error)) {
    const body = error.response?.data;
    if (isApiResponse(body)) {
      return {
        httpStatus: error.response?.status,
        code: body.code,
        message: body.message || fallbackMessage(error.response?.status),
        traceId: body.traceId,
        fieldErrors: toFieldErrors(body.data),
      };
    }

    return {
      httpStatus: error.response?.status,
      code: error.code === 'ECONNABORTED' ? 'REQUEST_TIMEOUT' : 'NETWORK_ERROR',
      message: fallbackMessage(error.response?.status, error.code),
    };
  }

  return {
    code: 'UNKNOWN_ERROR',
    message: '发生了意外错误，请稍后重试。',
  };
}

function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  return (
    isRecord(value) &&
    typeof value.code === 'string' &&
    typeof value.message === 'string' &&
    Object.hasOwn(value, 'data')
  );
}

function toFieldErrors(value: unknown): Record<string, string> | undefined {
  if (Array.isArray(value)) {
    const errors = value.reduce<Record<string, string>>((result, item) => {
      if (isFieldValidationError(item)) {
        result[item.field] = item.message;
      }
      return result;
    }, {});
    return Object.keys(errors).length > 0 ? errors : undefined;
  }

  if (isRecord(value)) {
    const errors = Object.entries(value).reduce<Record<string, string>>(
      (result, [field, message]) => {
        if (typeof message === 'string') {
          result[field] = message;
        }
        return result;
      },
      {},
    );
    return Object.keys(errors).length > 0 ? errors : undefined;
  }

  return undefined;
}

function isFieldValidationError(value: unknown): value is FieldValidationError {
  return isRecord(value) && typeof value.field === 'string' && typeof value.message === 'string';
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function fallbackMessage(status?: number, axiosCode?: string): string {
  if (axiosCode === 'ECONNABORTED') {
    return '请求超时，请检查网络后重试。';
  }

  switch (status) {
    case 400:
      return '提交的数据不符合要求，请检查后重试。';
    case 404:
      return '请求的资源不存在或已被删除。';
    case 409:
      return '当前操作与资源状态冲突，请刷新后重试。';
    case 413:
      return '文件大小超过服务端允许的限制。';
    case 429:
      return '请求过于频繁，请稍后再试。';
    default:
      return status && status >= 500
        ? '服务暂时不可用，请稍后重试。'
        : '网络连接不可用，请检查后重试。';
  }
}
