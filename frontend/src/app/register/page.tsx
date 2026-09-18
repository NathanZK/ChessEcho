'use client';

import Link from 'next/link';
import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Eye, EyeOff } from 'lucide-react';
import { register } from '@/services/api';

export default function RegisterPage() {
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
    const result = await register(email, password);
    if (result.status === 'authenticated') {
      router.push('/');
    } else {
      setError(result.error || 'Unable to create your account.');
      setBusy(false);
    }
  }

  return (
    <main className="min-h-screen bg-slate-950 text-slate-100 flex items-center justify-center px-4">
      <form onSubmit={submit} className="w-full max-w-md space-y-5 rounded-2xl border border-slate-800 bg-slate-900 p-8 shadow-xl">
        <div>
          <h1 className="text-2xl font-bold">Create your ChessEcho account</h1>
          <p className="mt-2 text-sm text-slate-400">Your password is used only for this sign-in.</p>
        </div>
        {error && <p role="alert" className="rounded-lg border border-rose-800 bg-rose-950/40 p-3 text-sm text-rose-200">{error}</p>}
        <label className="block text-sm font-medium">
          Email
          <input
            className="mt-2 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>
        <label className="block text-sm font-medium">
          Password
          <div className="relative mt-2">
            <input
              className="w-full rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 pr-11"
              type={passwordVisible ? 'text' : 'password'}
              autoComplete="new-password"
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
              className="absolute inset-y-0 right-0 flex items-center rounded-r-lg px-3 text-slate-400 hover:text-slate-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
            >
              {passwordVisible ? <EyeOff aria-hidden="true" className="h-4 w-4" /> : <Eye aria-hidden="true" className="h-4 w-4" />}
            </button>
          </div>
          <span className="mt-1 block text-xs text-slate-400">Use at least 8 characters.</span>
        </label>
        <button disabled={busy} className="w-full rounded-lg bg-emerald-600 px-4 py-2 font-semibold hover:bg-emerald-500 disabled:opacity-50">
          {busy ? 'Creating account…' : 'Create account'}
        </button>
        <p className="text-center text-sm text-slate-400">
          Already registered? <Link className="text-emerald-400 hover:underline" href="/login">Sign in</Link>
        </p>
      </form>
    </main>
  );
}
