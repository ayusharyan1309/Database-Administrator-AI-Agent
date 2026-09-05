import { useState, type FormEvent } from 'react';
import { Loader2 } from 'lucide-react';
import {
  signInWithGoogle, signInWithEmail, registerWithEmail, describeAuthError,
} from '../lib/firebase';

type Mode = 'signin' | 'register';

export default function SignIn() {
  const [mode, setMode] = useState<Mode>('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState<'google' | 'email' | null>(null);
  const [error, setError] = useState('');

  const run = async (kind: 'google' | 'email', action: () => Promise<void>) => {
    setError('');
    setBusy(kind);
    try {
      await action();
      // On success the auth listener swaps this screen out; nothing to do here.
    } catch (e) {
      setError(describeAuthError(e));
      setBusy(null);
    }
  };

  const submitEmail = (e: FormEvent) => {
    e.preventDefault();
    run('email', () =>
      mode === 'signin' ? signInWithEmail(email, password) : registerWithEmail(email, password)
    );
  };

  return (
    <div className="min-h-screen flex items-center justify-center px-6 bg-abyss">
      <div className="w-full max-w-[368px]">
        <div className="mb-8">
          <h1 className="text-title font-bold text-ink">OptiQuery</h1>
          <p className="sub mt-1">Sign in to see what your database is spending its time on.</p>
        </div>

        <div className="panel p-6 space-y-5">
          <button
            onClick={() => run('google', signInWithGoogle)}
            disabled={busy !== null}
            className="btn w-full justify-center py-2.5"
          >
            {busy === 'google'
              ? <Loader2 className="w-4 h-4 animate-spin" />
              : <GoogleMark />}
            Continue with Google
          </button>

          <div className="flex items-center gap-3">
            <span className="h-px flex-1 bg-line" />
            <span className="text-micro text-faint">or</span>
            <span className="h-px flex-1 bg-line" />
          </div>

          <form onSubmit={submitEmail} className="space-y-3">
            <div className="space-y-1.5">
              <label htmlFor="email" className="block text-micro text-muted">Email</label>
              <input
                id="email"
                type="email"
                required
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className="field"
                placeholder="you@company.com"
              />
            </div>

            <div className="space-y-1.5">
              <label htmlFor="password" className="block text-micro text-muted">Password</label>
              <input
                id="password"
                type="password"
                required
                minLength={6}
                autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="field"
                placeholder={mode === 'register' ? 'At least 6 characters' : ''}
              />
            </div>

            {error && (
              <p role="alert" className="text-tiny text-heat-crit leading-relaxed">{error}</p>
            )}

            <button type="submit" disabled={busy !== null} className="btn-primary w-full justify-center py-2.5">
              {busy === 'email' && <Loader2 className="w-4 h-4 animate-spin" />}
              {mode === 'signin' ? 'Sign in' : 'Create account'}
            </button>
          </form>
        </div>

        <p className="text-tiny text-muted text-center mt-5">
          {mode === 'signin' ? "Don't have an account? " : 'Already have an account? '}
          <button
            onClick={() => { setMode(mode === 'signin' ? 'register' : 'signin'); setError(''); }}
            className="text-signal hover:underline rounded-sm"
          >
            {mode === 'signin' ? 'Create one' : 'Sign in'}
          </button>
        </p>
      </div>
    </div>
  );
}

function GoogleMark() {
  return (
    <svg className="w-4 h-4" viewBox="0 0 18 18" aria-hidden="true">
      <path fill="#4285F4" d="M17.64 9.2c0-.64-.06-1.25-.16-1.84H9v3.48h4.84a4.14 4.14 0 0 1-1.8 2.72v2.26h2.92c1.7-1.57 2.68-3.88 2.68-6.62Z" />
      <path fill="#34A853" d="M9 18c2.43 0 4.47-.8 5.96-2.18l-2.92-2.26c-.8.54-1.84.86-3.04.86-2.34 0-4.32-1.58-5.03-3.7H.96v2.33A9 9 0 0 0 9 18Z" />
      <path fill="#FBBC05" d="M3.97 10.72a5.4 5.4 0 0 1 0-3.44V4.95H.96a9 9 0 0 0 0 8.1l3.01-2.33Z" />
      <path fill="#EA4335" d="M9 3.58c1.32 0 2.5.45 3.44 1.35l2.58-2.58C13.46.89 11.43 0 9 0A9 9 0 0 0 .96 4.95l3.01 2.33C4.68 5.16 6.66 3.58 9 3.58Z" />
    </svg>
  );
}
