import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { associateAccount, fetchAccounts } from '../services/api';

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

describe('owned account API contract', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
    document.cookie = 'XSRF-TOKEN=account-csrf; path=/';
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns the server-owned account summaries', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(200, [{ id: 'account-1', platform: 'CHESS_COM', username: 'hikaru' }])
    );

    await expect(fetchAccounts()).resolves.toEqual([
      { id: 'account-1', platform: 'CHESS_COM', username: 'hikaru' },
    ]);
  });

  it('surfaces an expired session instead of treating it as an empty account list', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(401, { error: 'UNAUTHENTICATED' }));

    await expect(fetchAccounts()).rejects.toThrow(/401/);
  });

  it('surfaces a malformed account-list response instead of treating it as empty', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(200, { accounts: [] }));

    await expect(fetchAccounts()).rejects.toThrow(/unexpected response body/i);
  });

  it('associates a Chess.com username with credentials and the CSRF token', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(201, { id: 'account-2', platform: 'CHESS_COM', username: 'new-player' })
    );

    await expect(associateAccount('CHESS_COM', 'new-player')).resolves.toEqual({
      id: 'account-2',
      platform: 'CHESS_COM',
      username: 'new-player',
    });

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toContain('/accounts');
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('include');
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('account-csrf');
    expect(JSON.parse(String(init?.body))).toEqual({
      platform: 'CHESS_COM',
      username: 'new-player',
    });
  });
});
