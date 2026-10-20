import { describe, expect, it } from 'vitest';
import { idleProgress } from './progress.contracts';
import { reduceProgressEvent } from './progress-reducer';
import { mockProgressEvent } from '../test/mocks/progress-socket';

describe('WebSocket reducer', () => {
  it('advances contiguous events, ignores duplicate/older events and keeps REST resync gaps', () => {
    const first = mockProgressEvent(1);
    const current = reduceProgressEvent(idleProgress, first);
    expect(current.snapshot.sequence).toBe(1);
    expect(current.resync).toBeUndefined();
    expect(reduceProgressEvent(current.snapshot, first).snapshot).toBe(current.snapshot);
    const gap = reduceProgressEvent(current.snapshot, mockProgressEvent(4));
    expect(gap.resync).toBe('gap');
    expect(gap.snapshot.gap).toEqual({ expected: 2, received: 4 });
    expect(reduceProgressEvent(gap.snapshot, mockProgressEvent(2)).snapshot).toBe(gap.snapshot);
    expect(reduceProgressEvent(gap.snapshot, mockProgressEvent(5)).snapshot.gap).toEqual(
      gap.snapshot.gap,
    );
  });
  it('requests one terminal resync even when the terminal event has a sequence gap', () => {
    expect(
      reduceProgressEvent(idleProgress, mockProgressEvent(7, { type: 'EXECUTION_FAILED' })).resync,
    ).toBe('terminal');
  });
});
