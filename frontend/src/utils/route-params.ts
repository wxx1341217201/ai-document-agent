export function parsePositiveRouteId(value: string | undefined): number | undefined {
  if (!value || !/^\d+$/.test(value)) {
    return undefined;
  }

  const result = Number(value);
  return Number.isSafeInteger(result) && result > 0 ? result : undefined;
}
