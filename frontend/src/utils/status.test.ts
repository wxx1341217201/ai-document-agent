import { describe, expect, it } from 'vitest';
import { statusMetadata } from './status';

describe('status mapping', () => {
  it.each([
    ['READY', '已就绪', 'success'],
    ['RUNNING', '执行中', 'processing'],
    ['PAUSED', '已暂停', 'warning'],
    ['FAILED', '失败', 'error'],
    ['CANCELLED', '已取消', 'default'],
    ['DOWN', '不可用', 'error'],
    ['UNKNOWN', '未知', 'default'],
  ])('maps %s to meaningful text and color', (status, label, color) => {
    expect(statusMetadata(status)).toEqual({ label, color });
  });
  it('never treats an unknown or prototype key as success', () => {
    for (const value of ['', 'toString', '__proto__', 'UP_WITH_INTERNAL_DETAILS'])
      expect(statusMetadata(value)).toEqual({ color: 'default', label: '未知状态' });
  });
});
