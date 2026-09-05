import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { User } from 'firebase/auth';
import { authEnabled, watchUser, signOut as fbSignOut } from './firebase';
import { startSession } from '../api';

interface AuthState {
  /** Null when signed out, or when auth is switched off entirely. */
  user: User | null;
  /** True until Firebase has restored any existing session. */
  loading: boolean;
  /** False when no Firebase config is present — the app runs open. */
  enabled: boolean;
  signOut: () => Promise<void>;
}

const AuthContext = createContext<AuthState>({
  user: null,
  loading: false,
  enabled: false,
  signOut: async () => {},
});

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  // Without Firebase there is no session to restore, so never show a spinner.
  const [loading, setLoading] = useState(authEnabled);

  useEffect(() => {
    if (!authEnabled) return;
    return watchUser((next) => {
      setUser(next);
      setLoading(false);
      // Record the sign-in. Failure here must not block using the app.
      if (next) void startSession().catch(() => {});
    });
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, enabled: authEnabled, signOut: fbSignOut }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  return useContext(AuthContext);
}
