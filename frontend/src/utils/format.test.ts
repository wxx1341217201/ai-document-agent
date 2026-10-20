import { describe, expect, it } from 'vitest';
import { abbreviateHash, formatBytes, formatCitationPages, formatDateTime } from './format';
import { parsePositiveRouteId } from './route-params';

describe('display formatters and route inputs', () => {
  it('handles missing, invalid, zero and large byte values', () => {
    for (const value of [undefined, null, -1, NaN, Infinity]) expect(formatBytes(value)).toBe('—');
    expect(formatBytes(0)).toBe('0 B');
    expect(formatBytes(1024)).toBe('1.0 KB');
    expect(formatBytes(1024 ** 2 * 20)).toBe('20 MB');
  });
  it('handles invalid timestamps, hashes and actual citation page ranges', () => {
    expect(formatDateTime('invalid')).toBe('—');
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime('2026-10-11T08:30:00')).toMatch(/2026/);
    expect(abbreviateHash('abc')).toBe('abc');
    expect(abbreviateHash('a'.repeat(64))).toBe('aaaaaaaaaaaa…aaaaaaaa');
    expect(formatCitationPages(null, null)).toBe('后端未返回页码');
    expect(formatCitationPages(5, 5)).toBe('第 5 页');
    expect(formatCitationPages(5, 6)).toBe('第 5 至 6 页');
  });
  it('rejects ambiguous, negative and unsafe resource IDs', () => {
    for (const value of [
      '-1',
      '0',
      '1.2',
      '1e3',
      'Infinity',
      '9007199254740993',
      '<script>',
      undefined,
    ])
      expect(parsePositiveRouteId(value)).toBeUndefined();
    expect(parsePositiveRouteId('42')).toBe(42);
  });
});
