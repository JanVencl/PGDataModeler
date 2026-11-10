import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import { api } from '../api';
import { applyAll, applyOp } from '../model/reducer';
import type { DataModel, Operation, OperationEvent, Participant } from '../model/types';
import { newId } from '../util';

/**
 * Client side of the collaboration protocol.
 *
 * - `confirmed` is the server state at `version`, built only from broadcast events applied in version order.
 * - `pending` are local ops sent but not yet confirmed. The displayed model is confirmed + pending replayed,
 *   so local edits show instantly and remote edits are never lost or reordered.
 * - On connect (and whenever a version gap is detected) the session subscribes first, then loads a snapshot,
 *   then replays buffered events newer than the snapshot; pending ops are re-sent (all ops are idempotent).
 */
export interface SessionState {
  status: 'connecting' | 'online' | 'offline';
  model: DataModel | null;
  version: number;
  pendingCount: number;
  participants: Participant[];
  lastError: string | null;
}

interface PendingOp {
  clientOpId: string;
  op: Operation;
}

type Listener = () => void;

export class CollabSession {
  private client: Client | null = null;
  private subscriptions: StompSubscription[] = [];
  private confirmed: DataModel | null = null;
  private version = 0;
  private pending: PendingOp[] = [];
  private syncing = false;
  private buffer: OperationEvent[] = [];
  private listeners = new Set<Listener>();
  private selectedId: string | null = null;
  private state: SessionState = {
    status: 'connecting',
    model: null,
    version: 0,
    pendingCount: 0,
    participants: [],
    lastError: null,
  };

  constructor(
    private readonly modelId: string,
    private readonly userName: string,
    private readonly color: string,
  ) {}

  // ---- store interface for useSyncExternalStore ----
  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): SessionState => this.state;

  private emit(patch: Partial<SessionState> = {}) {
    const model = this.confirmed ? applyAll(this.confirmed, this.pending.map((p) => p.op)) : null;
    this.state = { ...this.state, ...patch, model, version: this.version, pendingCount: this.pending.length };
    this.listeners.forEach((l) => l());
  }

  // ---- lifecycle ----
  connect(): void {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    // Callbacks of a client that was replaced (e.g. React StrictMode remount) are ignored.
    const client: Client = new Client({
      brokerURL: `${scheme}://${location.host}/ws`,
      reconnectDelay: 2000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        if (this.client === client) this.onConnect();
      },
      onWebSocketClose: () => {
        if (this.client === client) this.emit({ status: 'offline' });
      },
      onStompError: (frame) => {
        if (this.client === client) this.emit({ lastError: frame.headers['message'] ?? 'STOMP error' });
      },
    });
    this.client = client;
    client.activate();
  }

  async disconnect(): Promise<void> {
    const client = this.client;
    if (client?.connected) {
      this.publish(`/app/models/${this.modelId}/presence`, { userName: this.userName, leaving: true });
    }
    this.client = null;
    this.subscriptions = [];
    await client?.deactivate();
  }

  private onConnect(): void {
    const client = this.client!;
    this.subscriptions.forEach((s) => {
      try {
        s.unsubscribe();
      } catch {
        /* connection already gone */
      }
    });
    this.subscriptions = [
      client.subscribe(`/topic/models/${this.modelId}`, (m: IMessage) => this.onEvent(JSON.parse(m.body))),
      client.subscribe(`/topic/models/${this.modelId}/presence`, (m: IMessage) =>
        this.emit({ participants: JSON.parse(m.body) as Participant[] }),
      ),
    ];
    this.sendPresence();
    void this.resync().then(() => {
      this.pending.forEach((p) => this.sendOp(p));
      this.emit({ status: 'online' });
    });
  }

  private async resync(): Promise<void> {
    this.syncing = true;
    this.buffer = [];
    try {
      const snapshot = await api.snapshot(this.modelId);
      this.confirmed = snapshot.document;
      this.version = snapshot.version;
      const buffered = this.buffer.sort((a, b) => a.version - b.version);
      this.syncing = false;
      this.buffer = [];
      buffered.forEach((e) => this.onEvent(e));
      this.emit();
    } catch (e) {
      this.syncing = false;
      this.emit({ lastError: `Could not load model: ${(e as Error).message}` });
    }
  }

  private onEvent(event: OperationEvent): void {
    if (this.syncing) {
      this.buffer.push(event);
      return;
    }
    const own = event.clientOpId != null && this.pending.some((p) => p.clientOpId === event.clientOpId);
    if (event.status === 'REJECTED') {
      if (own) {
        this.pending = this.pending.filter((p) => p.clientOpId !== event.clientOpId);
        this.emit({ lastError: event.error ?? 'Change rejected by server' });
      }
      return;
    }
    if (!this.confirmed || event.version <= this.version) return; // already included
    if (event.version !== this.version + 1) {
      void this.resync(); // missed something: reload
      return;
    }
    try {
      this.confirmed = applyOp(this.confirmed, event.op);
    } catch {
      void this.resync(); // should not happen: server and client reducers disagree
      return;
    }
    this.version = event.version;
    if (own) this.pending = this.pending.filter((p) => p.clientOpId !== event.clientOpId);
    this.emit();
  }

  // ---- editing ----
  dispatch(op: Operation): void {
    const p: PendingOp = { clientOpId: newId(), op };
    this.pending = [...this.pending, p];
    this.emit({ lastError: null });
    if (this.client?.connected && !this.syncing) this.sendOp(p);
  }

  select(id: string | null): void {
    if (id === this.selectedId) return;
    this.selectedId = id;
    this.sendPresence();
  }

  clearError(): void {
    this.emit({ lastError: null });
  }

  private sendOp(p: PendingOp): void {
    this.publish(`/app/models/${this.modelId}/ops`, { clientOpId: p.clientOpId, author: this.userName, op: p.op });
  }

  private sendPresence(): void {
    this.publish(`/app/models/${this.modelId}/presence`, {
      userName: this.userName,
      color: this.color,
      selectedId: this.selectedId,
      leaving: false,
    });
  }

  private publish(destination: string, body: unknown): void {
    if (this.client?.connected) {
      this.client.publish({ destination, body: JSON.stringify(body) });
    }
  }
}
