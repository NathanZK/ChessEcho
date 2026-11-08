import type { Page } from '@playwright/test';
import { type PracticeAbstractState, STATE_ORACLES } from './practiceModel';

export interface ActionLogEntry {
  step: number;
  fromState: PracticeAbstractState;
  action: string;
  toState: PracticeAbstractState;
  timestamp: number;
}

export interface MonkeyErrorRecord {
  type: 'pageerror' | 'console_error';
  message: string;
  stack?: string;
  step?: number;
}

/**
 * Attaches page-level listeners to intercept unhandled page exceptions
 * and console errors during monkey testing.
 */
export class InvariantErrorObserver {
  private errors: MonkeyErrorRecord[] = [];

  attach(page: Page): void {
    page.on('pageerror', (err) => {
      this.errors.push({
        type: 'pageerror',
        message: err.message,
        stack: err.stack,
      });
    });

    page.on('console', (msg) => {
      if (msg.type() === 'error') {
        this.errors.push({
          type: 'console_error',
          message: msg.text(),
        });
      }
    });
  }

  getErrors(): MonkeyErrorRecord[] {
    return [...this.errors];
  }

  clear(): void {
    this.errors = [];
  }

  hasErrors(): boolean {
    return this.errors.length > 0;
  }
}

/**
 * Tracks network requests to enable detailed diagnostics for missing requests
 * and HTTP response failures when state transitions fail.
 */
export interface NetworkRequestRecord {
  url: string;
  method: string;
  status?: number;
  failed?: boolean;
  failureText?: string;
  timestamp: number;
}

export class NetworkActivityMonitor {
  private requests: NetworkRequestRecord[] = [];

  attach(page: Page): void {
    page.on('request', (req) => {
      this.requests.push({
        url: req.url(),
        method: req.method(),
        timestamp: Date.now(),
      });
    });

    page.on('response', (res) => {
      const rec = this.requests.slice().reverse().find((r) => r.url === res.url());
      if (rec) {
        rec.status = res.status();
        rec.failed = res.status() >= 400;
      }
    });

    page.on('requestfailed', (req) => {
      const rec = this.requests.slice().reverse().find((r) => r.url === req.url());
      if (rec) {
        rec.failed = true;
        rec.failureText = req.failure()?.errorText || 'Failed request';
      }
    });
  }

  getRequestsSince(sinceTimestamp: number): NetworkRequestRecord[] {
    return this.requests.filter((r) => r.timestamp >= sinceTimestamp);
  }

  clear(): void {
    this.requests = [];
  }
}

/**
 * Evaluates core cross-cutting runtime invariant oracles on the live page.
 * Every reachable state is checked against the registered STATE_ORACLES definitions.
 * Missing oracles fail loudly.
 */
export async function assertInvariantOracles(
  page: Page,
  state: PracticeAbstractState,
  observer: InvariantErrorObserver,
  step: number,
): Promise<void> {
  // Oracle 1: Zero Unhandled Exceptions
  if (observer.hasErrors()) {
    const errorDetails = observer
      .getErrors()
      .map((e) => `[${e.type}] ${e.message}`)
      .join('\n');
    throw new Error(`[Oracle 1 Failure] Unhandled page error at step ${step} in state ${state}:\n${errorDetails}`);
  }

  // Oracle 2: No Blank Presentation / Root Container Intact
  const mainContent = page.locator('main');
  const mainVisible = await mainContent.isVisible().catch(() => false);
  if (!mainVisible) {
    throw new Error(`[Oracle 2 Failure] Application root container is not visible at step ${step} in state ${state}.`);
  }

  // Oracle 3: State-Specific Presentation Consistency via registered STATE_ORACLES
  const oracle = STATE_ORACLES[state];
  if (!oracle || typeof oracle.assert !== 'function') {
    throw new Error(`[Oracle Configuration Error] Missing registered oracle for state: ${state} at step ${step}.`);
  }

  try {
    await oracle.assert(page);
  } catch (err) {
    throw new Error(
      `[Oracle 3 Failure] Invariant violated for state ${state} at step ${step} (${oracle.description}): ${(err as Error).message}`
    );
  }
}
