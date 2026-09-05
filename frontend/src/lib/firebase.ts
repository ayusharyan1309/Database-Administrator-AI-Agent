/**
 * Firebase bootstrap.
 *
 * Deliberately optional. When no config is present the app runs exactly as it
 * did before authentication existed — no login wall, no broken screens — so a
 * local checkout without a Firebase project stays usable.
 */
import { initializeApp, type FirebaseApp } from 'firebase/app';
import {
  getAuth, GoogleAuthProvider, signInWithPopup, signOut as fbSignOut,
  signInWithEmailAndPassword, createUserWithEmailAndPassword,
  onAuthStateChanged, type Auth, type User,
} from 'firebase/auth';

const config = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
  appId: import.meta.env.VITE_FIREBASE_APP_ID,
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID,
};

/** True only when enough config exists for Firebase Auth to work. */
export const authEnabled = Boolean(config.apiKey && config.authDomain && config.projectId);

let app: FirebaseApp | null = null;
let auth: Auth | null = null;

if (authEnabled) {
  app = initializeApp(config);
  auth = getAuth(app);
}

export function getFirebaseAuth(): Auth {
  if (!auth) throw new Error('Firebase Auth is not configured');
  return auth;
}

// --- Sign in ---------------------------------------------------------------

export async function signInWithGoogle(): Promise<void> {
  const provider = new GoogleAuthProvider();
  provider.setCustomParameters({ prompt: 'select_account' });
  await signInWithPopup(getFirebaseAuth(), provider);
}

export async function signInWithEmail(email: string, password: string): Promise<void> {
  await signInWithEmailAndPassword(getFirebaseAuth(), email, password);
}

export async function registerWithEmail(email: string, password: string): Promise<void> {
  await createUserWithEmailAndPassword(getFirebaseAuth(), email, password);
}

export async function signOut(): Promise<void> {
  if (auth) await fbSignOut(auth);
}

export function watchUser(onChange: (user: User | null) => void): () => void {
  if (!auth) {
    onChange(null);
    return () => {};
  }
  return onAuthStateChanged(auth, onChange);
}

/**
 * A current ID token for the API. Firebase refreshes it automatically when it
 * is within five minutes of expiry, so this is cheap to call per request.
 */
export async function getIdToken(): Promise<string | null> {
  if (!auth?.currentUser) return null;
  try {
    return await auth.currentUser.getIdToken();
  } catch {
    return null;
  }
}

/** Turn Firebase's error codes into something a person can act on. */
export function describeAuthError(error: unknown): string {
  const code = (error as { code?: string })?.code ?? '';
  switch (code) {
    case 'auth/invalid-email': return 'That email address is not valid.';
    case 'auth/user-not-found':
    case 'auth/wrong-password':
    case 'auth/invalid-credential': return 'That email and password do not match an account.';
    case 'auth/email-already-in-use': return 'An account already exists for that email. Sign in instead.';
    case 'auth/weak-password': return 'Choose a password of at least six characters.';
    case 'auth/popup-closed-by-user': return 'The sign-in window closed before finishing.';
    case 'auth/popup-blocked': return 'Your browser blocked the sign-in window. Allow popups and try again.';
    case 'auth/unauthorized-domain':
      return 'This domain is not authorized in Firebase. Add it under Authentication → Settings → Authorized domains.';
    case 'auth/operation-not-allowed':
      return 'That sign-in method is not enabled in the Firebase console.';
    case 'auth/network-request-failed': return 'Could not reach Firebase. Check your connection.';
    default: return (error as Error)?.message || 'Sign-in failed. Try again.';
  }
}
