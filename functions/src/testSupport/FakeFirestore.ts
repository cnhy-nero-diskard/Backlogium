export interface RecordedWrite {
  readonly path: string;
  readonly data: Record<string, unknown>;
}

interface DocumentSnapshot {
  readonly exists: boolean;
  data(): Record<string, unknown> | undefined;
}

interface DocumentReference {
  readonly path: string;
  get(): Promise<DocumentSnapshot>;
  collection(name: string): CollectionReference;
}

interface CollectionReference {
  doc(id: string): DocumentReference;
}

interface PendingWrite {
  readonly reference: DocumentReference;
  readonly data: Record<string, unknown>;
}

interface ReadBarrier {
  remaining: number;
  readonly ready: Promise<void>;
  readonly resolveReady: () => void;
  readonly release: Promise<void>;
  readonly resolveRelease: () => void;
}

/**
 * Narrow in-memory Firestore surface used by the presence poller tests.
 * It intentionally models only collection().doc().get(), batch().set(), and commit().
 */
export class FakeFirestore {
  readonly committedWrites: RecordedWrite[] = [];

  private readonly documents = new Map<string, Record<string, unknown>>();
  private readBarrier: ReadBarrier | undefined;

  collection(name: string): CollectionReference {
    return new FakeCollectionReference(this, name);
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
        for (const write of pending) {
          this.committedWrites.push({
            path: write.reference.path,
            data: write.data,
          });
          this.documents.set(write.reference.path, write.data);
        }
      },
    };
  }

  seed(path: string, data: Record<string, unknown>): void {
    this.documents.set(path, data);
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

  async read(path: string): Promise<DocumentSnapshot> {
    const data = this.documents.get(path);
    const snapshot: DocumentSnapshot = {
      exists: data !== undefined,
      data: () => data,
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
}

class FakeCollectionReference implements CollectionReference {
  constructor(
    private readonly firestore: FakeFirestore,
    private readonly path: string,
  ) {}

  doc(id: string): DocumentReference {
    return new FakeDocumentReference(this.firestore, `${this.path}/${id}`);
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
