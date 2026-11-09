import type { Page, ConsoleMessage } from '@playwright/test';
import { type PracticeAbstractState, STATE_ORACLES } from './practiceModel';

export interface ActionLogEntry<TState = PracticeAbstractState> {
  step: number;
  fromState: TState;
  action: string;
  toState: TState;
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
  private attachedPage: Page | null = null;
  private pageErrorHandler: ((err: Error) => void) | null = null;
  private consoleHandler: ((msg: ConsoleMessage) => void) | null = null;

  attach(page: Page): void {
    this.attachedPage = page;
    this.pageErrorHandler = (err: Error) => {
      this.errors.push({
        type: 'pageerror',
        message: err.message,
        stack: err.stack,
      });
    };

    this.consoleHandler = (msg: ConsoleMessage) => {
      if (msg.type() === 'error') {
        const text = msg.text();
        // Ignore expected HTTP error responses logged automatically by Chromium
        if (text.includes('Failed to load resource: the server responded with a status of')) {
          return;
        }
        this.errors.push({
          type: 'console_error',
          message: text,
        });
      }
    };

    page.on('pageerror', this.pageErrorHandler);
    page.on('console', this.consoleHandler);
  }

  detach(): void {
    if (this.attachedPage) {
      if (this.pageErrorHandler) {
        this.attachedPage.off('pageerror', this.pageErrorHandler);
        this.pageErrorHandler = null;
      }
      if (this.consoleHandler) {
        this.attachedPage.off('console', this.consoleHandler);
        this.consoleHandler = null;
      }
      this.attachedPage = null;
    }
  }

  assertNoErrors(step?: number, state?: string): void {
    if (this.hasErrors()) {
      const errorDetails = this.getErrors()
        .map((e) => `[${e.type}] ${e.message}`)
        .join('\n');
      throw new Error(`[Unhandled Page Error] at step ${step ?? 'unknown'} in state ${state ?? 'unknown'}:\n${errorDetails}`);
    }
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
