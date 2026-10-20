import { AxiosError, AxiosHeaders } from 'axios';
import { describe, expect, it } from 'vitest';
import { ApiClientError, normalizeApiError } from './client';

function httpError(status: number, data: unknown) {
  const config = { headers: new AxiosHeaders() };
  return new AxiosError('raw transport message', 'ERR_BAD_RESPONSE', config, undefined, {
    status,
    statusText: 'error',
    headers: {},
    config,
    data,
  });
}
describe('untrusted error normalization', () => {
  it('does not interpret 500 response details as form errors, and redacts unsafe validation text', () => {
    expect(
      normalizeApiError(
        httpError(500, {
          code: 'INTERNAL_ERROR',
          message: '服务暂不可用',
          data: { password: 'private-secret', stack: 'private-stack' },
        }),
      ).fieldErrors,
    ).toBeUndefined();
    expect(
      normalizeApiError(
        httpError(400, {
          code: 'VALIDATION_ERROR',
          message: '参数校验失败',
          data: [{ field: 'name', message: 'D:\\private\\file' }],
        }),
      ).fieldErrors?.name,
    ).not.toContain('private');
  });
  it('preserves public status, trace and field-validation errors', () => {
    expect(
      normalizeApiError(
        httpError(400, {
          code: 'VALIDATION_ERROR',
          message: '参数校验失败',
          traceId: 'trace-123',
          data: [{ field: 'name', message: '名称不能为空' }],
        }),
      ),
    ).toEqual({
      httpStatus: 400,
      code: 'VALIDATION_ERROR',
      message: '参数校验失败',
      traceId: 'trace-123',
      fieldErrors: { name: '名称不能为空' },
    });
  });
  it.each([400, 404, 409, 413, 429, 500])(
    'handles HTTP %s with a non-envelope body without leaking it',
    (status) => {
      const error = normalizeApiError(httpError(status, 'private error stack'));
      expect(error.httpStatus).toBe(status);
      expect(error.code).toBe('HTTP_ERROR');
      expect(error.message).not.toContain('private');
    },
  );
  it('separates network/timeout/model outages and never displays internal paths/SQL/credentials', () => {
    expect(normalizeApiError(new AxiosError('raw', 'ECONNABORTED')).code).toBe('REQUEST_TIMEOUT');
    expect(normalizeApiError(new AxiosError('raw', 'ERR_NETWORK')).code).toBe('NETWORK_ERROR');
    expect(
      normalizeApiError(
        httpError(503, { code: 'MODEL_UNAVAILABLE', message: 'raw upstream', data: null }),
      ).message,
    ).toBe('模型服务暂时不可用，请稍后重试。');
    for (const message of [
      'SELECT password FROM account',
      'D:\\private\\file.pdf',
      'at com.example.Db.query(Db.java:23)',
      'password=secret',
    ])
      expect(
        normalizeApiError(new ApiClientError({ code: 'INTERNAL_ERROR', httpStatus: 500, message }))
          .message,
      ).not.toBe(message);
    expect(normalizeApiError(new Error('private stack')).code).toBe('UNKNOWN_ERROR');
  });
});
