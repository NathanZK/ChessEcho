export class StopwatchTimer {
  private startTimeMs: number | null = null;
  private elapsedBeforeStop: number = 0;
  private running: boolean = false;
  private currentAttemptId: string | null = null;

  startNewAttempt(): string {
    this.currentAttemptId = crypto.randomUUID();
    this.reset();
    return this.currentAttemptId;
  }

  start(currentTimeMs: number = performance.now()): void {
    this.startTimeMs = currentTimeMs;
    this.running = true;
  }

  stop(currentTimeMs: number = performance.now()): number {
    if (!this.running || this.startTimeMs === null) return this.elapsedBeforeStop;
    this.elapsedBeforeStop = Math.round(currentTimeMs - this.startTimeMs);
    this.running = false;
    this.startTimeMs = null;
    return this.elapsedBeforeStop;
  }

  pause(currentTimeMs: number = performance.now()): number {
    return this.stop(currentTimeMs);
  }

  resume(currentTimeMs: number = performance.now()): void {
    if (this.running) return;
    this.startTimeMs = currentTimeMs - this.elapsedBeforeStop;
    this.running = true;
  }

  getElapsed(currentTimeMs: number = performance.now()): number {
    if (!this.running) return this.elapsedBeforeStop;
    if (this.startTimeMs === null) return this.elapsedBeforeStop;
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
  private remainingBeforePause: number | null = null;

  constructor(allowedMs: number) {
    this.allowedMs = allowedMs;
  }

  startNewAttempt(): string {
    this.currentAttemptId = crypto.randomUUID();
    this.reset();
    return this.currentAttemptId;
  }

  start(currentTimeMs: number = performance.now()): void {
    this.startTimeMs = currentTimeMs;
    this.expired = false;
    this.remainingBeforePause = null;
  }

  pause(currentTimeMs: number = performance.now()): number {
    const remaining = this.getRemaining(currentTimeMs);
    if (this.startTimeMs !== null && remaining > 0) {
      this.remainingBeforePause = remaining;
      this.startTimeMs = null;
    }
    return remaining;
  }

  resume(currentTimeMs: number = performance.now()): void {
    if (this.startTimeMs !== null || this.expired || this.remainingBeforePause === null) return;
    this.startTimeMs = currentTimeMs - (this.allowedMs - this.remainingBeforePause);
    this.remainingBeforePause = null;
  }

  getRemaining(currentTimeMs: number = performance.now()): number {
    if (this.startTimeMs === null) return this.remainingBeforePause ?? this.allowedMs;
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
    this.remainingBeforePause = null;
  }

  isRunning(): boolean {
    return this.startTimeMs !== null && !this.expired;
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
