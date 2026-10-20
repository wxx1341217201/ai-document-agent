import { useEffect, useRef, useState } from 'react';
import {
  idleProgress,
  progressSubscriptionKey,
  type ProgressResyncReason,
  type ProgressSnapshot,
  type ProgressSubscription,
} from '../../api/progress.contracts';
import {
  progressWebSocketClient,
  type ProgressWebSocketClient,
} from '../../api/progress-websocket';

export interface ProgressHookOptions<T> {
  enabled?: boolean;
  initialData?: T;
  client?: ProgressWebSocketClient;
}

// Keep polling as a safety net: M18 is best effort, without replay or multi-instance fanout.
export const PROGRESS_POLL_INTERVAL_MS = 5_000;

export function useProgressSubscription(
  target: ProgressSubscription,
  enabled: boolean,
  synchronize: (reason: ProgressResyncReason) => Promise<unknown>,
  client = progressWebSocketClient,
): ProgressSnapshot {
  const key = progressSubscriptionKey(target);
  const [state, setState] = useState({ key: '', snapshot: idleProgress });
  const synchronizeRef = useRef(synchronize);
  useEffect(() => {
    synchronizeRef.current = synchronize;
  }, [synchronize]);

  useEffect(() => {
    if (!enabled) return;
    let active = true;
    const unsubscribe = client.subscribe(target, {
      onChange: (snapshot) => {
        if (active) setState({ key, snapshot });
      },
      onResync: (reason, snapshot) => {
        void synchronizeRef
          .current(reason)
          .then(() => {
            if (active) client.markSynchronized(target, snapshot.sequence);
          })
          .catch(() => {
            // Query exposes the REST error; polling/manual refresh can retry synchronization.
          });
      },
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, [client, enabled, key, target]);

  return enabled && state.key === key ? state.snapshot : idleProgress;
}
