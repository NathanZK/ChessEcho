import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../services/api';

type LocalAuthResult = {
  status: 'authenticated' | 'unauthenticated' | 'error';
  userId?: string;
};

type LocalAuth = (email: string, password: string) => Promise<LocalAuthResult>;

const register = Reflect.get(api, 'register') as LocalAuth;
const login = Reflect.get(api, 'login') as LocalAuth;

function headerValue(init: RequestInit | undefined, name: string): string | null {
  const headers = init?.headers;
  if (!headers) return null;
  if (headers instanceof Headers) return headers.get(name);
  if (Array.isArray(headers)) {
    const found = headers.find(([key]) => key.toLowerCase() === name.toLowerCase());
    return found?.[1] ?? null;
  }
  const record = headers as Record<string, string>;
  const key = Object.keys(record).find((candidate) => candidate.toLowerCase() === name.toLowerCase());
  return key ? record[key] : null;
}

describe('Local email/password API contract (Issue #276)', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.cookie = 'XSRF-TOKEN=csrf-276; path=/';
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each([
    ['register', register, '/register'],
    ['login', login, '/login'],
  ] as const)('%s sends credentials and the readable CSRF token without persisting secrets', async (_, authenticate, path) => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ userId: 'user-276' }),
    } as Response);
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem');

    await expect(authenticate('Player@Example.COM', 'correct horse battery staple')).resolves.toEqual({
      status: 'authenticated',
      userId: 'user-276',
    });

    const [url, init] = vi.mocked(global.fetch).mock.calls[0];
    const options = init as RequestInit;
    expect(String(url)).toContain(path);
    expect(options.method).toBe('POST');
    expect(options.credentials).toBe('include');
    expect(headerValue(options, 'X-XSRF-TOKEN')).toBe('csrf-276');
    expect(JSON.parse(options.body as string)).toEqual({
      email: 'Player@Example.COM',
      password: 'correct horse battery staple',
    });
    expect(localStorageSpy).not.toHaveBeenCalled();
  });

  it.each([
    ['register', register],
    ['login', login],
  ] as const)('%s keeps failed authentication unauthenticated without storing password or session material', async (_, authenticate) => {
    vi.mocked(global.fetch).mockResolvedValueOnce({
      ok: false,
      status: 401,
      json: async () => ({ error: 'INVALID_CREDENTIALS' }),
    } as Response);
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem');
    const sessionStorageSpy = vi.spyOn(Storage.prototype, 'setItem');

    await expect(authenticate('player@example.com', 'wrong password')).resolves.toEqual({
      status: 'unauthenticated',
    });

    expect(localStorageSpy).not.toHaveBeenCalled();
    expect(sessionStorageSpy).not.toHaveBeenCalled();
  });
});
