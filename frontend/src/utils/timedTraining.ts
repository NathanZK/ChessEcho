export class StopwatchTimer {
  private startTimeMs: number | null = null;
  private elapsedBeforeStop: number = 0;
  private running: boolean = false;
  private currentAttemptId: string | null = null;

  startNewAttempt(puzzleId: string): string {
    const safePuzzleId = puzzleId.replace(/[^a-zA-Z0-9_-]/g, '');
    this.currentAttemptId = `attempt-${safePuzzleId}-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;
    this.reset();
    return this.currentAttemptId;
  }

  start(currentTimeMs: number = performance.now()): void {
    this.startTimeMs = currentTimeMs;
    this.running = true;
  }

  stop(currentTimeMs: number = performance.now()): number {
    if (!this.running || !this.startTimeMs) return 0;
    this.elapsedBeforeStop = Math.round(currentTimeMs - this.startTimeMs);
    this.running = false;
    this.startTimeMs = null;
    return this.elapsedBeforeStop;
  }

  getElapsed(currentTimeMs: number = performance.now()): number {
    if (!this.running) return this.elapsedBeforeStop;
    if (!this.startTimeMs) return 0;
    return Math.round(currentTimeMs - this.startTimeMs);
  }

  reset(): void {
    this.startTimeMs = null;
    this.elapsedBeforeStop = 0;
    this.running = false;
  }

  isRunning(): boolean {
    return this.running;
  }

  cancelAttempt(): void {
    this.reset();
    this.currentAttemptId = null;
  }

  getCurrentAttemptId(): string | null {
    return this.currentAttemptId;
  }
}

export class CountdownTimer {
  private startTimeMs: number | null = null;
  private allowedMs: number;
  private expired: boolean = false;
  private currentAttemptId: string | null = null;

  constructor(allowedMs: number) {
    this.allowedMs = allowedMs;
  }

  startNewAttempt(puzzleId: string): string {
    const safePuzzleId = puzzleId.replace(/[^a-zA-Z0-9_-]/g, '');
    this.currentAttemptId = `attempt-${safePuzzleId}-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`;
    this.reset();
    return this.currentAttemptId;
  }

  start(currentTimeMs: number = performance.now()): void {
    this.startTimeMs = currentTimeMs;
    this.expired = false;
  }

  getRemaining(currentTimeMs: number = performance.now()): number {
    if (!this.startTimeMs) return this.allowedMs;
    const elapsed = Math.round(currentTimeMs - this.startTimeMs);
    const remaining = Math.max(0, this.allowedMs - elapsed);
    if (remaining === 0) {
      this.expired = true;
    }
    return remaining;
  }

  isExpired(currentTimeMs: number = performance.now()): boolean {
    return this.getRemaining(currentTimeMs) === 0;
  }

  reset(): void {
    this.startTimeMs = null;
    this.expired = false;
  }

  getOutcome(): string {
    return this.expired ? 'EXPIRED' : 'SUBMITTED';
  }

  getCurrentAttemptId(): string | null {
    return this.currentAttemptId;
  }

  canSubmit(): boolean {
    return !this.expired;
  }
}

export async function submitTrainingAttempt(payload: {
  attemptId: string;
  puzzleId: string;
  mode: string;
  elapsedMs: number;
  allowedMs?: number;
  outcome: string;
}): Promise<{
  attemptId: string;
  puzzleId: string;
  mode: string;
  elapsedMs: number;
  allowedMs?: number;
  outcome: string;
  recordedAt: string;
}> {
  const response = await fetch('/api/puzzles/attempt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  if (!response.ok) {
    throw new Error(`Failed to submit training attempt: ${response.statusText}`);
  }

  return response.json();
}
