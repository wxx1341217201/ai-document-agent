import { reduceProgressEvent } from './progress-reducer';
import {
  idleProgress,
  isPositiveId,
  isRecord,
  isUuid,
  parseProgressEvent,
  progressSubscriptionKey,
  type ProgressObserver,
  type ProgressResyncReason,
  type ProgressSnapshot,
  type ProgressSubscription,
} from './progress.contracts';

export type ProgressSocket = Pick<
  WebSocket,
  'onopen' | 'onmessage' | 'onclose' | 'onerror' | 'send' | 'close'
>;

interface ClientOptions {
  url?: string | (() => string);
  createSocket?: (url: string) => ProgressSocket;
  reconnectDelaysMs?: readonly number[];
  maxReconnectDelayMs?: number;
  jitterRatio?: number;
  random?: () => number;
  timeoutMs?: number;
}

interface SubscriptionEntry {
  target: ProgressSubscription;
  observers: Set<ProgressObserver>;
  snapshot: ProgressSnapshot;
  streamId?: string;
  eventIds: Set<string>;
  hasSubscribed: boolean;
}

interface Command {
  action: 'SUBSCRIBE' | 'UNSUBSCRIBE';
  entry: SubscriptionEntry;
}

/** VITE_WS_BASE_URL is a public base URL, never a bearer token or a query credential. */
export function progressWebSocketUrl(
  base = import.meta.env.VITE_WS_BASE_URL || '/ws',
  pageUrl = window.location.href,
): string {
  const url = new URL(`${base.replace(/\/$/, '')}/progress`, pageUrl);
  if (url.protocol === 'http:') url.protocol = 'ws:';
  if (url.protocol === 'https:') url.protocol = 'wss:';
  if (!['ws:', 'wss:'].includes(url.protocol) || url.username || url.password || url.search) {
    throw new Error('进度 WebSocket 地址不合法');
  }
  return url.href;
}

/** One lazy, reference-counted connection. No event replay is assumed on reconnect. */
export class ProgressWebSocketClient {
  private readonly options: Required<Omit<ClientOptions, 'url'>> & { url: string | (() => string) };
  private readonly entries = new Map<string, SubscriptionEntry>();
  private socket?: ProgressSocket;
  private open = false;
  private commands: Command[] = [];
  private pending?: Command;
  private timeout?: ReturnType<typeof setTimeout>;
  private reconnectTimer?: ReturnType<typeof setTimeout>;
  private reconnectAttempt = 0;

  constructor(options: ClientOptions = {}) {
    this.options = {
      url: progressWebSocketUrl,
      createSocket: (url) => new WebSocket(url),
      reconnectDelaysMs: [1_000, 2_000, 4_000, 8_000, 15_000, 30_000],
      maxReconnectDelayMs: 30_000,
      jitterRatio: 0.2,
      random: Math.random,
      timeoutMs: 10_000,
      ...options,
    };
  }

  subscribe(target: ProgressSubscription, observer: ProgressObserver): () => void {
    if (
      !isPositiveId(target.knowledgeBaseId) ||
      !['EXECUTION', 'DOCUMENT'].includes(target.resource) ||
      (target.resource === 'EXECUTION'
        ? !isUuid(target.executionId)
        : !isPositiveId(target.documentId))
    ) {
      throw new Error('进度订阅资源不合法');
    }
    const key = progressSubscriptionKey(target);
    let entry = this.entries.get(key);
    if (!entry) {
      entry = {
        target: { ...target },
        observers: new Set(),
        snapshot: {
          ...idleProgress,
          connection: this.reconnectTimer ? 'reconnecting' : 'connecting',
        },
        eventIds: new Set(),
        hasSubscribed: false,
      };
      this.entries.set(key, entry);
      if (this.open) this.commands.push({ action: 'SUBSCRIBE', entry });
    }
    entry.observers.add(observer);
    observer.onChange(entry.snapshot);
    this.connect();
    this.flushCommands();
    let removed = false;
    return () => {
      if (!removed) this.unsubscribe(target, observer);
      removed = true;
    };
  }

  unsubscribe(target: ProgressSubscription, observer: ProgressObserver): void {
    const key = progressSubscriptionKey(target);
    const entry = this.entries.get(key);
    if (!entry || !entry.observers.delete(observer) || entry.observers.size) return;
    this.entries.delete(key);
    // Remove unsent subscriptions; a subscription already in flight still needs UNSUBSCRIBE.
    this.commands = this.commands.filter((command) => command.entry !== entry);
    if (!this.entries.size) {
      this.stopConnection();
      this.reconnectAttempt = 0;
    } else if (this.open) {
      if (entry.hasSubscribed || this.pending?.entry === entry) {
        this.commands.push({ action: 'UNSUBSCRIBE', entry });
      }
      this.flushCommands();
    }
  }

  /** Explicit retry is available after a subscription rejection or a deployment/auth change. */
  reconnect(): void {
    this.stopConnection();
    this.reconnectAttempt = 0;
    this.connect();
  }

  markSynchronized(target: ProgressSubscription, sequence: number): void {
    const entry = this.entries.get(progressSubscriptionKey(target));
    if (entry?.snapshot.gap && entry.snapshot.sequence === sequence) {
      this.update(entry, { gap: null });
    }
  }

  getSnapshot(target: ProgressSubscription): ProgressSnapshot {
    return this.entries.get(progressSubscriptionKey(target))?.snapshot ?? idleProgress;
  }

  dispose(): void {
    this.stopConnection();
    for (const entry of this.entries.values()) this.update(entry, { connection: 'idle' });
    this.entries.clear();
    this.reconnectAttempt = 0;
  }

  private connect(): void {
    if (this.socket || this.reconnectTimer || !this.entries.size) return;
    for (const entry of this.entries.values()) {
      this.update(entry, { connection: 'connecting', rejectionCode: null });
    }
    try {
      const url = typeof this.options.url === 'function' ? this.options.url() : this.options.url;
      const socket = this.options.createSocket(url);
      this.socket = socket;
      socket.onopen = () => {
        if (this.socket !== socket) return;
        this.clearTimeout();
        this.open = true;
        this.commands = [...this.entries.values()].map((entry) => ({ action: 'SUBSCRIBE', entry }));
        this.flushCommands();
      };
      socket.onmessage = (message) => {
        if (this.socket === socket) this.receive(message.data);
      };
      socket.onclose = socket.onerror = () => {
        if (this.socket === socket) this.connectionLost();
      };
      this.timeout = setTimeout(() => this.connectionLost(), this.options.timeoutMs);
    } catch {
      this.connectionLost();
    }
  }

  private connectionLost(): void {
    this.stopConnection();
    if (!this.entries.size) return;
    for (const entry of this.entries.values()) this.update(entry, { connection: 'reconnecting' });
    const delays = this.options.reconnectDelaysMs;
    const base = delays[Math.min(this.reconnectAttempt++, delays.length - 1)] ?? 1_000;
    const jitter = 1 + (this.options.random() * 2 - 1) * this.options.jitterRatio;
    const delay = Math.max(
      1,
      Math.min(this.options.maxReconnectDelayMs, Math.round(base * jitter)),
    );
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined;
      this.connect();
    }, delay);
  }

  private stopConnection(): void {
    this.clearTimeout();
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.commands = [];
    this.pending = undefined;
    this.open = false;
    const socket = this.socket;
    this.socket = undefined;
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
      try {
        socket.close();
      } catch {
        // Some native implementations throw when closing an incomplete handshake.
      }
    }
  }

  private clearTimeout(): void {
    clearTimeout(this.timeout);
    this.timeout = undefined;
  }

  private flushCommands(): void {
    if (!this.open || this.pending || !this.socket) return;
    const command = this.commands.shift();
    if (!command) return;
    this.pending = command;
    if (command.action === 'SUBSCRIBE') this.update(command.entry, { connection: 'subscribing' });
    try {
      this.timeout = setTimeout(() => this.connectionLost(), this.options.timeoutMs);
      this.socket.send(JSON.stringify({ action: command.action, ...command.entry.target }));
    } catch {
      this.connectionLost();
    }
  }

  private finishCommand(): void {
    this.clearTimeout();
    this.pending = undefined;
    this.flushCommands();
  }

  private receive(data: unknown): void {
    if (typeof data !== 'string') return;
    let message: unknown;
    try {
      message = JSON.parse(data);
    } catch {
      return;
    }
    if (!isRecord(message)) return;
    if (message.type === 'SUBSCRIBED') {
      this.subscribed(message);
      return;
    }
    if (message.type === 'UNSUBSCRIBED' && this.pending?.action === 'UNSUBSCRIBE') {
      this.finishCommand();
      return;
    }
    if (message.type === 'SUBSCRIPTION_REJECTED' && this.pending) {
      if (this.pending.action === 'SUBSCRIBE') {
        this.update(this.pending.entry, {
          connection: 'unavailable',
          rejectionCode:
            typeof message.code === 'string' && /^[A-Z_]{1,64}$/.test(message.code)
              ? message.code
              : 'UNAVAILABLE',
        });
      }
      this.finishCommand();
      return;
    }
    const event = parseProgressEvent(message);
    if (!event) return;
    for (const entry of this.entries.values()) {
      const target = entry.target;
      const matches =
        target.resource === 'EXECUTION'
          ? event.executionId === target.executionId && event.payload.documentId === null
          : event.payload.documentId === target.documentId;
      if (!matches || !['subscribing', 'subscribed'].includes(entry.snapshot.connection)) continue;
      if (entry.streamId !== event.executionId) {
        // The backend can publish before SUBSCRIBED. Only an outstanding subscription
        // can bind a new ingestion stream; delayed events from old jobs are ignored.
        if (entry.streamId && this.pending?.entry !== entry) continue;
        entry.streamId = event.executionId;
        entry.eventIds.clear();
        this.update(entry, { event: null, sequence: 0, gap: null });
      }
      if (entry.eventIds.has(event.eventId)) continue;
      const transition = reduceProgressEvent(entry.snapshot, event);
      if (transition.snapshot === entry.snapshot) continue;
      entry.eventIds.add(event.eventId);
      if (entry.eventIds.size > 256) entry.eventIds.delete(entry.eventIds.values().next().value!);
      this.update(entry, transition.snapshot);
      // One refetch suffices when a terminal event also reveals a gap.
      if (transition.resync) this.resync(entry, transition.resync);
    }
  }

  private subscribed(message: Record<string, unknown>): void {
    const command = this.pending;
    if (!command || command.action !== 'SUBSCRIBE') return;
    const entry = command.entry;
    const target = entry.target;
    if (
      message.resource !== target.resource ||
      message.knowledgeBaseId !== target.knowledgeBaseId ||
      !isUuid(message.executionId) ||
      (target.resource === 'EXECUTION'
        ? message.executionId !== target.executionId || message.documentId != null
        : message.documentId !== target.documentId) ||
      typeof message.latestSequence !== 'number' ||
      !Number.isSafeInteger(message.latestSequence) ||
      message.latestSequence < 0
    ) {
      return;
    }
    if (entry.streamId !== message.executionId) {
      entry.streamId = message.executionId;
      entry.eventIds.clear();
      this.update(entry, { event: null, sequence: 0, gap: null });
    }
    this.update(entry, {
      connection: 'subscribed',
      sequence: Math.max(entry.snapshot.sequence, message.latestSequence),
      rejectionCode: null,
    });
    const reason = entry.hasSubscribed ? 'reconnect' : 'subscribe';
    entry.hasSubscribed = true;
    this.reconnectAttempt = 0;
    if (entry.observers.size) this.resync(entry, reason);
    this.finishCommand();
  }

  private update(entry: SubscriptionEntry, patch: Partial<ProgressSnapshot>): void {
    entry.snapshot = { ...entry.snapshot, ...patch };
    for (const observer of entry.observers) observer.onChange(entry.snapshot);
  }

  private resync(entry: SubscriptionEntry, reason: ProgressResyncReason): void {
    for (const observer of entry.observers) observer.onResync(reason, entry.snapshot);
  }
}

export const progressWebSocketClient = new ProgressWebSocketClient();
