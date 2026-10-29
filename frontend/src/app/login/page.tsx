'use client';

import Link from 'next/link';
import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Eye, EyeOff } from 'lucide-react';
import { login } from '@/services/api';

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setBusy(true);
    const result = await login(email, password);
    if (result.status === 'authenticated') {
      router.push('/');
    } else {
      setError(result.error || 'Invalid email or password.');
      setBusy(false);
    }
  }

  return (
    <main className="min-h-screen bg-surface text-on-surface flex items-center justify-center px-4">
      <form onSubmit={submit} className="w-full max-w-md space-y-5 rounded-2xl border border-outline-variant bg-surface-container-low p-8 shadow-2xl">
        <div>
          <div className="flex items-center space-x-2.5 mb-3 font-bold tracking-tight text-primary text-base">
            <span className="w-7 h-7 rounded bg-surface-container-high border border-outline-variant flex items-center justify-center text-primary shadow-inner">
              <svg className="w-4 h-4 fill-current text-primary" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M19 22H5v-2h14v2m-1-4H6c-.5 0-.9-.3-1-.7-.4-1.7-.8-4.5 1-6.3 1.1-1.1 2.3-1.6 3-3.5.7-1.9.4-3.5 0-4.5-.1-.3 0-.6.2-.8.2-.2.5-.2.8-.1 1.7.7 3.5 2 4.5 4.4.4.9 1 1.5 1.5 2.5.5.9.8 2 .8 3.5 0 1.2-.5 2.1-1 2.8.5.5.8 1.1.9 1.7.2 1.3-.7 2.4-1.7 3M9.5 9c-.8 0-1.5.7-1.5 1.5s.7 1.5 1.5 1.5 1.5-.7 1.5-1.5S10.3 9 9.5 9z" />
              </svg>
            </span>
            <span className="font-sans font-extrabold tracking-wide text-primary">ChessProbe</span>
          </div>
          <h1 className="text-2xl font-bold text-on-surface">Sign in to ChessProbe</h1>
          <p className="mt-1 text-xs text-on-surface-variant font-mono">Use your email and password to continue.</p>
        </div>
        {error && <p role="alert" className="rounded-lg border border-error-container bg-error-container/20 p-3 text-xs font-mono text-error">{error}</p>}
        <label className="block text-xs font-mono font-medium text-on-surface">
          Email
          <input
            className="mt-1.5 w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-3 py-2 text-on-surface font-sans text-sm outline-none focus:border-primary transition"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>
        <label className="block text-xs font-mono font-medium text-on-surface">
          Password
          <div className="relative mt-1.5">
            <input
              className="w-full rounded-lg border border-outline-variant bg-surface-container-lowest px-3 py-2 pr-11 text-on-surface font-sans text-sm outline-none focus:border-primary transition"
              type={passwordVisible ? 'text' : 'password'}
              autoComplete="current-password"
              required
              minLength={8}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
            <button
              type="button"
              onClick={() => setPasswordVisible((visible) => !visible)}
              aria-label={passwordVisible ? 'Hide password' : 'Show password'}
              aria-pressed={passwordVisible}
              className="absolute inset-y-0 right-0 flex items-center rounded-r-lg px-3 text-outline hover:text-on-surface focus:outline-none focus-visible:ring-2 focus-visible:ring-primary cursor-pointer"
            >
              {passwordVisible ? <EyeOff aria-hidden="true" className="h-4 w-4" /> : <Eye aria-hidden="true" className="h-4 w-4" />}
            </button>
          </div>
        </label>
        <button
          disabled={busy}
          className="w-full rounded-lg bg-primary-container hover:bg-primary text-on-primary-container px-4 py-2 font-mono font-bold text-xs transition shadow-sm disabled:opacity-50 cursor-pointer"
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
        <p className="text-center text-xs font-mono text-on-surface-variant">
          New to ChessProbe? <Link className="text-primary hover:underline" href="/register">Create an account</Link>
        </p>
      </form>
    </main>
  );
}
