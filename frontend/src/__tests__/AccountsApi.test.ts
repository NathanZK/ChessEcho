import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AccountAssociationError,
  associateAccount,
  disconnectAccount,
  fetchAccounts,
  startAccountImportJob,
  startImportJob,
} from '../services/api';

function response(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response;
}

describe('connected account API contract', () => {
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

  it('rejects an account-list entry missing a required account summary field', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(200, [{ id: 'account-1', platform: 'CHESS_COM' }])
    );

    await expect(fetchAccounts()).rejects.toThrow(/unexpected response body/i);
  });

  it('rejects account lists containing more than one connection', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(200, [
        { id: 'account-1', platform: 'CHESS_COM', username: 'hikaru' },
        { id: 'account-2', platform: 'CHESS_COM', username: 'magnus' },
      ])
    );

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

  it('preserves the HTTP status and API code for account-association errors', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(409, { error: 'ACCOUNT_CONNECTION_LIMIT_REACHED' })
    );

    const error = await associateAccount('CHESS_COM', 'other-player').catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(AccountAssociationError);
    expect(error).toMatchObject({
      status: 409,
      code: 'ACCOUNT_CONNECTION_LIMIT_REACHED',
      message: 'ACCOUNT_CONNECTION_LIMIT_REACHED',
    });
  });

  it('uses a null association error code when the API error field is not a string', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(503, { error: 42 }));

    const error = await associateAccount('CHESS_COM', 'new-player').catch((reason: unknown) => reason);

    expect(error).toBeInstanceOf(AccountAssociationError);
    expect(error).toMatchObject({
      status: 503,
      code: null,
      message: 'Failed to associate account: 503',
    });
  });

  it('rejects a successful association response that is not an account summary', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(200, { id: 'account-1', platform: 'CHESS_COM', username: '' })
    );

    await expect(associateAccount('CHESS_COM', 'new-player')).rejects.toThrow(
      'Failed to associate account: unexpected response body'
    );
  });

  it('keeps the status and existing fallback when an association error body is invalid', async () => {
    const invalidJsonResponse = response(503, {});
    vi.mocked(fetch).mockResolvedValueOnce({
      ...invalidJsonResponse,
      json: vi.fn().mockRejectedValue(new SyntaxError('invalid JSON')),
    });

    const error = await associateAccount('CHESS_COM', 'new-player').catch((reason: unknown) => reason);

    expect(error).toMatchObject({
      status: 503,
      code: null,
      message: 'Failed to associate account: 503',
    });
  });

  it('disconnects the selected account with credentials and the CSRF token', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(204, {}));

    await expect(disconnectAccount('account-3')).resolves.toBeUndefined();

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toContain('/accounts/account-3/connection');
    expect(init?.method).toBe('DELETE');
    expect(init?.credentials).toBe('include');
    expect(new Headers(init?.headers).get('X-XSRF-TOKEN')).toBe('account-csrf');
  });

  it('retains the Not Found status and server error code for a disconnect failure', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(404, { error: 'ACCOUNT_NOT_FOUND' }));

    await expect(disconnectAccount('account-3')).rejects.toMatchObject({
      status: 404,
      message: 'ACCOUNT_NOT_FOUND',
    });
  });

  it('retains the forbidden status and server error code for a disconnect failure', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(403, { error: 'FORBIDDEN' }));

    await expect(disconnectAccount('account-3')).rejects.toMatchObject({
      status: 403,
      message: 'FORBIDDEN',
    });
  });

  it('submits authenticated imports with the explicit connected account ID', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(202, { jobId: 'job-1', status: 'QUEUED' })
    );

    await startAccountImportJob({
      accountId: 'account-3',
      username: 'hikaru',
      timeControls: ['BLITZ'],
      playerColor: 'WHITE',
    });

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toContain('/games/import');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({
      accountId: 'account-3',
      platform: 'CHESS_COM',
      username: 'hikaru',
      timeControls: ['BLITZ'],
      playerColor: 'WHITE',
    });
  });

  it('keeps guest imports username-based', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      response(202, { jobId: 'job-2', status: 'QUEUED' })
    );

    await startImportJob('guest-player', 'CHESS_COM', ['RAPID'], 'BOTH');

    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect(JSON.parse(String(init?.body))).toMatchObject({
      username: 'guest-player',
      platform: 'CHESS_COM',
      timeControls: ['RAPID'],
      playerColor: 'BOTH',
    });
    expect(JSON.parse(String(init?.body))).not.toHaveProperty('accountId');
  });
});
