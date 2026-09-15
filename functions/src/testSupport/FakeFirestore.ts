export interface RecordedWrite {
  readonly path: string;
  readonly data: Record<string, unknown>;
}

interface DocumentSnapshot {
  readonly id: string;
  readonly exists: boolean;
  data(): Record<string, unknown> | undefined;
  readonly version: number;
}

interface QuerySnapshot {
  readonly docs: readonly DocumentSnapshot[];
}

interface Query {
  where(field: string, operator: ">" | ">=", value: unknown): Query;
  orderBy(field: string, direction: "asc" | "desc"): Query;
  startAfter(value: unknown): Query;
  limit(value: number): Query;
  get(): Promise<QuerySnapshot>;
}

interface DocumentReference {
  readonly path: string;
  get(): Promise<DocumentSnapshot>;
  collection(name: string): CollectionReference;
}

interface CollectionReference {
  doc(id: string): DocumentReference;
  where(field: string, operator: ">" | ">=", value: unknown): Query;
  orderBy(field: string, direction: "asc" | "desc"): Query;
}

interface PendingWrite {
  readonly reference: DocumentReference;
  readonly data: Record<string, unknown>;
}

interface Transaction {
  get(reference: DocumentReference): Promise<DocumentSnapshot>;
  set(reference: DocumentReference, data: Record<string, unknown>): void;
}

interface VersionedDocument {
  readonly data: Record<string, unknown>;
  readonly version: number;
}

interface ReadBarrier {
  remaining: number;
  readonly ready: Promise<void>;
  readonly resolveReady: () => void;
  readonly release: Promise<void>;
  readonly resolveRelease: () => void;
}

interface CommitBarrier {
  acquired: boolean;
  readonly ready: Promise<void>;
  readonly resolveReady: () => void;
  readonly release: Promise<void>;
  readonly resolveRelease: () => void;
}

/**
 * Narrow in-memory Firestore surface used by the presence poller tests.
 * It intentionally models only collection().doc().get(), transactions, and
 * batch().set()/commit(). Transactions use optimistic version checks and
 * retry a callback when a document read by it changed before commit.
 */
export class FakeFirestore {
  readonly committedWrites: RecordedWrite[] = [];
  transactionAttempts = 0;
  readCount = 0;

  private readonly documents = new Map<string, VersionedDocument>();
  private readBarrier: ReadBarrier | undefined;
  private commitBarrier: CommitBarrier | undefined;

  collection(name: string): CollectionReference {
    return new FakeCollectionReference(this, name);
  }

  documentsForTests(): ReadonlyMap<string, VersionedDocument> {
    return this.documents;
  }

  batch(): {
    set(reference: DocumentReference, data: Record<string, unknown>): void;
    commit(): Promise<void>;
  } {
    const pending: PendingWrite[] = [];

    return {
      set: (reference, data) => {
        pending.push({ reference, data });
      },
      commit: async () => {
        this.commitWrites(pending);
      },
    };
  }

  async runTransaction<T>(
    updateFunction: (transaction: Transaction) => Promise<T>,
  ): Promise<T> {
    for (let attempt = 0; attempt < 5; attempt += 1) {
      this.transactionAttempts += 1;
      const transaction = new FakeTransaction();
      const result = await updateFunction(transaction);

      try {
        await this.commitTransaction(transaction);
        return result;
      } catch (error) {
        if (!(error instanceof TransactionConflictError) || attempt === 4) {
          throw error;
        }
      }
    }

    throw new Error("Transaction retry limit reached");
  }

  seed(path: string, data: Record<string, unknown>): void {
    const version = this.documents.get(path)?.version ?? 0;
    this.documents.set(path, { data, version: version + 1 });
  }

  /** Hold the next N reads until releaseHeldReads() is called. */
  holdNextReads(count: number): void {
    if (count < 1) throw new Error("Read barrier count must be positive");

    let resolveReady!: () => void;
    let resolveRelease!: () => void;
    const ready = new Promise<void>((resolve) => {
      resolveReady = resolve;
    });
    const release = new Promise<void>((resolve) => {
      resolveRelease = resolve;
    });

    this.readBarrier = {
      remaining: count,
      ready,
      resolveReady,
      release,
      resolveRelease,
    };
  }

  async waitUntilReadsHeld(): Promise<void> {
    await this.readBarrier?.ready;
  }

  releaseHeldReads(): void {
    this.readBarrier?.resolveRelease();
  }

  /** Hold the next transaction commit until releaseHeldTransactionCommit(). */
  holdNextTransactionCommit(): void {
    let resolveReady!: () => void;
    let resolveRelease!: () => void;
    const ready = new Promise<void>((resolve) => {
      resolveReady = resolve;
    });
    const release = new Promise<void>((resolve) => {
      resolveRelease = resolve;
    });

    this.commitBarrier = {
      acquired: false,
      ready,
      resolveReady,
      release,
      resolveRelease,
    };
  }

  async waitUntilTransactionCommitHeld(): Promise<void> {
    await this.commitBarrier?.ready;
  }

  releaseHeldTransactionCommit(): void {
    this.commitBarrier?.resolveRelease();
  }

  async read(path: string): Promise<DocumentSnapshot> {
    this.readCount += 1;
    const stored = this.documents.get(path);
    const snapshot: DocumentSnapshot = {
      id: path.split("/").at(-1) ?? path,
      exists: stored !== undefined,
      data: () => stored?.data,
      version: stored?.version ?? 0,
    };
    const barrier = this.readBarrier;

    if (barrier && barrier.remaining > 0) {
      barrier.remaining -= 1;
      if (barrier.remaining === 0) barrier.resolveReady();
      await barrier.release;
      if (barrier.remaining === 0) this.readBarrier = undefined;
    }

    return snapshot;
  }

  async commitTransaction(transaction: FakeTransaction): Promise<void> {
    const barrier = this.commitBarrier;
    if (barrier && !barrier.acquired) {
      barrier.acquired = true;
      barrier.resolveReady();
      await barrier.release;
      if (this.commitBarrier === barrier) this.commitBarrier = undefined;
    }

    for (const [path, version] of transaction.readVersions) {
      const currentVersion = this.documents.get(path)?.version ?? 0;
      if (currentVersion !== version) {
        throw new TransactionConflictError();
      }
    }

    this.commitWrites(transaction.pendingWrites);
  }

  private commitWrites(pending: readonly PendingWrite[]): void {
    for (const write of pending) {
      this.committedWrites.push({
        path: write.reference.path,
        data: write.data,
      });
      const version = this.documents.get(write.reference.path)?.version ?? 0;
      this.documents.set(write.reference.path, {
        data: write.data,
        version: version + 1,
      });
    }
  }
}

class TransactionConflictError extends Error {
  constructor() {
    super("Transaction read was stale");
    this.name = "TransactionConflictError";
  }
}

class FakeTransaction implements Transaction {
  readonly readVersions = new Map<string, number>();
  readonly pendingWrites: PendingWrite[] = [];

  async get(reference: DocumentReference): Promise<DocumentSnapshot> {
    const snapshot = await reference.get();
    this.readVersions.set(reference.path, snapshot.version);
    return snapshot;
  }

  set(reference: DocumentReference, data: Record<string, unknown>): void {
    this.pendingWrites.push({ reference, data });
  }
}

class FakeCollectionReference implements CollectionReference {
  constructor(
    private readonly firestore: FakeFirestore,
    private readonly path: string,
  ) {}

  doc(id: string): DocumentReference {
    return new FakeDocumentReference(this.firestore, `${this.path}/${id}`);
  }

  where(field: string, operator: ">" | ">=", value: unknown): Query {
    return new FakeQuery(this.firestore, this.path).where(field, operator, value);
  }

  orderBy(field: string, direction: "asc" | "desc"): Query {
    return new FakeQuery(this.firestore, this.path).orderBy(field, direction);
  }
}

class FakeDocumentReference implements DocumentReference {
  constructor(
    private readonly firestore: FakeFirestore,
    readonly path: string,
  ) {}

  get(): Promise<DocumentSnapshot> {
    return this.firestore.read(this.path);
  }

  collection(name: string): CollectionReference {
    return new FakeCollectionReference(this.firestore, `${this.path}/${name}`);
  }
}
class FakeQuery implements Query {
  private readonly filter?: {
    readonly field: string;
    readonly operator: ">" | ">=";
    readonly value: unknown;
  };
  private readonly ordering?: {
    readonly field: string;
    readonly direction: "asc" | "desc";
  };
  private readonly after?: unknown;
  private readonly maxResults?: number;

  constructor(
    private readonly firestore: FakeFirestore,
    private readonly path: string,
    filter?: {
      readonly field: string;
      readonly operator: ">" | ">=";
      readonly value: unknown;
    },
    ordering?: {
      readonly field: string;
      readonly direction: "asc" | "desc";
    },
    after?: unknown,
    maxResults?: number,
  ) {
    this.filter = filter;
    this.ordering = ordering;
    this.after = after;
    this.maxResults = maxResults;
  }

  where(field: string, operator: ">" | ">=", value: unknown): Query {
    return new FakeQuery(
      this.firestore,
      this.path,
      { field, operator, value },
      this.ordering,
      this.after,
      this.maxResults,
    );
  }

  orderBy(field: string, direction: "asc" | "desc"): Query {
    return new FakeQuery(
      this.firestore,
      this.path,
      this.filter,
      { field, direction },
      this.after,
      this.maxResults,
    );
  }

  startAfter(value: unknown): Query {
    return new FakeQuery(
      this.firestore,
      this.path,
      this.filter,
      this.ordering,
      value,
      this.maxResults,
    );
  }

  limit(value: number): Query {
    return new FakeQuery(
      this.firestore,
      this.path,
      this.filter,
      this.ordering,
      this.after,
      value,
    );
  }

  async get(): Promise<QuerySnapshot> {
    this.firestore.readCount += 1;
    const prefix = this.path + "/";
    const candidates = [...this.firestore.documentsForTests().entries()]
      .filter(([path]) => {
        const remainder = path.slice(prefix.length);
        return path.startsWith(prefix) && !remainder.includes("/");
      })
      .map(([path, stored]) => ({
        id: path.split("/").at(-1) ?? path,
        data: stored.data,
        version: stored.version,
      }))
      .filter((document) => this.matchesFilter(document.data))
      .filter((document) => this.matchesAfter(document.data));

    const ordering = this.ordering;
    if (ordering) {
      candidates.sort((left, right) => {
        const difference =
          valueMillis(left.data[ordering.field]) - valueMillis(right.data[ordering.field]);
        return ordering.direction === "asc" ? difference : -difference;
      });
    }
    const limited = this.maxResults === undefined
      ? candidates
      : candidates.slice(0, this.maxResults);
    return {
      docs: limited.map((document) => ({
        id: document.id,
        exists: true,
        data: () => document.data,
        version: document.version,
      })),
    };
  }

  private matchesFilter(data: Record<string, unknown>): boolean {
    if (!this.filter) return true;
    const actual = valueMillis(data[this.filter.field]);
    const expected = valueMillis(this.filter.value);
    return this.filter.operator === ">" ? actual > expected : actual >= expected;
  }

  private matchesAfter(data: Record<string, unknown>): boolean {
    if (this.after === undefined || !this.ordering) return true;
    const actual = valueMillis(data[this.ordering.field]);
    const after = valueMillis(this.after);
    return this.ordering.direction === "asc" ? actual > after : actual < after;
  }
}

function valueMillis(value: unknown): number {
  if (value instanceof Date) return value.getTime();
  if (typeof value === "number") return value;
  if (value && typeof value === "object") {
    const candidate = value as { toDate?: unknown; date?: unknown };
    if (typeof candidate.toDate === "function") {
      const date = candidate.toDate();
      if (date instanceof Date) return date.getTime();
    }
    if (candidate.date instanceof Date) return candidate.date.getTime();
  }
  return Number.NEGATIVE_INFINITY;
}
