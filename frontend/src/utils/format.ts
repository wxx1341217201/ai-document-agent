const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

export function formatDateTime(value?: string | null): string {
  if (!value) {
    return '—';
  }

  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : dateTimeFormatter.format(date);
}

export function formatBytes(value?: number | null): string {
  if (value === undefined || value === null || !Number.isFinite(value) || value < 0) {
    return '—';
  }

  if (value < 1024) {
    return `${value} B`;
  }

  const units = ['KB', 'MB', 'GB'];
  const exponent = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length);
  const amount = value / 1024 ** exponent;
  return `${amount >= 10 ? amount.toFixed(0) : amount.toFixed(1)} ${units[exponent - 1]}`;
}

export function abbreviateHash(value?: string | null): string {
  if (!value) {
    return '—';
  }

  return value.length <= 24 ? value : `${value.slice(0, 12)}…${value.slice(-8)}`;
}

export function formatCitationPages(pageFrom: number | null, pageTo: number | null): string {
  if (pageFrom === null && pageTo === null) return '后端未返回页码';
  if (pageFrom !== null && pageTo !== null && pageFrom === pageTo) return `第 ${pageFrom} 页`;
  return `第 ${pageFrom ?? '—'} 至 ${pageTo ?? '—'} 页`;
}
