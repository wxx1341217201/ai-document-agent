import type { ReadingCompletion } from '../../api/reading.api';

export const completionLabels: Record<ReadingCompletion, string> = {
  FULL: '全部完成',
  PARTIAL: '部分完成',
  NONE: '尚无可靠结果',
};
