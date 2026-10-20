import {
  isTerminalProgressEvent,
  type ProgressEvent,
  type ProgressResyncReason,
  type ProgressSnapshot,
} from './progress.contracts';

/** Pure event ordering transition. The transport owns resource/stream matching and
 * its bounded event-ID set; REST remains authoritative for business state.
 */
export function reduceProgressEvent(
  snapshot: ProgressSnapshot,
  event: ProgressEvent,
): { snapshot: ProgressSnapshot; resync?: ProgressResyncReason } {
  if (event.sequence <= snapshot.sequence || event.eventId === snapshot.event?.eventId)
    return { snapshot };
  const expected = snapshot.sequence + 1;
  const missing = event.sequence > expected;
  return {
    snapshot: {
      ...snapshot,
      event,
      sequence: event.sequence,
      gap: missing ? { expected, received: event.sequence } : snapshot.gap,
    },
    resync: isTerminalProgressEvent(event) ? 'terminal' : missing ? 'gap' : undefined,
  };
}
