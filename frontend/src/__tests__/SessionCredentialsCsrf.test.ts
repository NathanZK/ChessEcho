import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as api from '../services/api';

/**
 * Issue #113 (AC12, #79 D2, §3.3) — the session API helper contract.
 *
 * `fetchCurrentSession`/`logout` must call `fetch` with `credentials:'include'`,
 * `logout` must send the `X-XSRF-TOKEN` header read from the readable
 * `XSRF-TOKEN` cookie (double-submit CSRF), and none of these calls may persist
 * any session secret/material to `localStorage`.
 *
 * Written test-first: `fetchCurrentSession`/`logout` are planned exports that do
 * not exist on `../services/api` yet, so these assertions are expected to be red
 * until production exists. They are referenced dynamically (Reflect.get) so the
 * test targets the real planned surface without a hard import binding.
 */

type FetchCurrentSession = () => Promise<{ status: string; userId?: string; devPrincipal?: boolean }>;
type Logout = () => Promise<void>;

const fetchCurrentSession = Reflect.get(api, 'fetchCurrentSession') as FetchCurrentSession;
const logout = Reflect.get(api, 'logout') as Logout;

function headerValue(init: RequestInit | undefined, name: string): string | null {
  const headers = init?.headers;
  if (!headers) return null;
  if (headers instanceof Headers) return headers.get(name);
  if (Array.isArray(headers)) {
    const found = headers.find(([key]) => key.toLowerCase() === name.toLowerCase());
    return found ? found[1] : null;
  }
  const record = headers as Record<string, string>;
  const key = Object.keys(record).find((k) => k.toLowerCase() === name.toLowerCase());
  return key ? record[key] : null;
}

describe('Session API credentials & CSRF contract (Issue #113)', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.stubGlobal('fetch', vi.fn());
    // Clear any cookies from a prior test.
    document.cookie.split(';').forEach((c) => {
      const name = c.split('=')[0].trim();
      if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fetchCurrentSession issues a credentialed GET to /me', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ userId: 'user-1', devPrincipal: true }),
    } as Response);

    await fetchCurrentSession();

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [calledUrl, init] = vi.mocked(global.fetch).mock.calls[0];
    expect(String(calledUrl)).toContain('/me');
    expect((init as RequestInit).credentials).toBe('include');
  });

  it('fetchCurrentSession maps a 401 to an unauthenticated result', async () => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: false,
      status: 401,
    } as Response);

    const result = await fetchCurrentSession();

    expect(result.status).toBe('unauthenticated');
  });

  it('logout POSTs to /logout with credentials and the CSRF header from the cookie', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-abc-123; path=/';
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      status: 204,
    } as Response);

    await logout();

    expect(global.fetch).toHaveBeenCalledTimes(1);
    const [calledUrl, init] = vi.mocked(global.fetch).mock.calls[0];
    const options = init as RequestInit;
    expect(String(calledUrl)).toContain('/logout');
    expect((options.method || 'GET').toUpperCase()).toBe('POST');
    expect(options.credentials).toBe('include');
    expect(headerValue(options, 'X-XSRF-TOKEN')).toBe('csrf-abc-123');
  });

  it('never writes session secrets or material to localStorage', async () => {
    document.cookie = 'XSRF-TOKEN=csrf-abc-123; path=/';
    vi.mocked(global.fetch)
      .mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => ({ userId: 'user-1', devPrincipal: false }),
      } as Response)
      .mockResolvedValueOnce({ ok: true, status: 204 } as Response);
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem');

    await fetchCurrentSession();
    await logout();

    const forbidden = /session|secret|token|xsrf|csrf/i;
    for (const [key, value] of setItemSpy.mock.calls) {
      expect(forbidden.test(String(key))).toBe(false);
      expect(forbidden.test(String(value))).toBe(false);
    }
  });
});
