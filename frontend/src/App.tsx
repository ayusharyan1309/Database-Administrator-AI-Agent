import { Routes, Route, Link, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Gauge, Users, Sliders, BookOpen, LogOut, Loader2 } from 'lucide-react';
import clsx from 'clsx';
import Dashboard from './pages/Dashboard';
import QueryDetail from './pages/QueryDetail';
import SettingsPage from './pages/SettingsPage';
import DocsPage from './pages/DocsPage';
import AccountPage from './pages/AccountPage';
import { fetchHealth } from './api';
import { useAuth } from './lib/auth';
import SignIn from './pages/SignIn';

const navItems = [
  { path: '/', label: 'Queries', icon: Gauge },
  { path: '/account', label: 'Team', icon: Users },
  { path: '/settings', label: 'Settings', icon: Sliders },
  { path: '/docs', label: 'Docs', icon: BookOpen },
];

export default function App() {
  const location = useLocation();
  const { user, loading: authLoading, enabled: authRequired, signOut } = useAuth();

  const { data: health, isError } = useQuery({
    queryKey: ['health'],
    queryFn: fetchHealth,
    refetchInterval: 30_000,
    retry: 1,
  });

  // Wait for Firebase to restore any existing session before deciding.
  if (authLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-abyss">
        <Loader2 className="w-5 h-5 text-signal animate-spin" />
      </div>
    );
  }

  if (authRequired && !user) return <SignIn />;

  const watching = !isError && health?.status === 'UP';
  const trial = health?.entitlement;
  // Only worth surfacing while OptiQuery is the one paying for analysis.
  const showTrial = trial && trial.mode === 'HOSTED_TRIAL';

  return (
    <div className="flex h-screen overflow-hidden bg-abyss">
      <aside className="w-[216px] shrink-0 border-r border-line flex flex-col bg-surface">
        <div className="px-5 py-5">
          <Link to="/" className="flex items-baseline gap-2 rounded-sm">
            <span className="text-lead font-bold tracking-tight text-ink">OptiQuery</span>
          </Link>
          <p className="text-micro text-faint mt-0.5">Postgres, watched continuously</p>
        </div>

        <nav className="px-3 space-y-0.5">
          {navItems.map(({ path, label, icon: Icon }) => {
            const isActive =
              path === '/' ? location.pathname === '/' || location.pathname.startsWith('/query')
                           : location.pathname === path;
            return (
              <Link
                key={path}
                to={path}
                aria-current={isActive ? 'page' : undefined}
                className={clsx(
                  'flex items-center gap-2.5 px-2.5 py-2 rounded-md text-base transition-colors',
                  isActive
                    ? 'bg-raised text-ink font-medium'
                    : 'text-muted hover:text-ink hover:bg-raised/60'
                )}
              >
                <Icon className={clsx('w-4 h-4', isActive ? 'text-signal' : 'text-faint')} />
                {label}
              </Link>
            );
          })}
        </nav>

        {user && (
          <div className="mt-auto px-5 py-3 border-t border-line">
            <div className="flex items-center gap-2.5">
              <Link
                to="/account"
                className="flex items-center gap-2.5 min-w-0 flex-1 rounded-sm group"
                title="Profile, team and databases"
              >
              {user.photoURL ? (
                <img src={user.photoURL} alt="" className="w-6 h-6 rounded-full shrink-0" />
              ) : (
                <span className="w-6 h-6 rounded-full bg-raised text-micro text-muted flex items-center justify-center shrink-0">
                  {(user.email ?? '?').charAt(0).toUpperCase()}
                </span>
              )}
              <span className="text-micro text-muted group-hover:text-ink transition-colors truncate">
                {user.displayName || user.email}
              </span>
              </Link>
              <button
                onClick={() => signOut()}
                title="Sign out"
                aria-label="Sign out"
                className="p-1 rounded-sm text-faint hover:text-ink transition-colors"
              >
                <LogOut className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>
        )}

        <div className={clsx('px-5 py-4 border-t border-line space-y-1', !user && 'mt-auto')}>
          <div className="flex items-center gap-2">
            <span
              className={clsx(
                'w-1.5 h-1.5 rounded-full',
                watching ? 'bg-heat-cool animate-pulse7' : 'bg-heat-crit'
              )}
            />
            <span className="text-micro text-muted">
              {watching ? 'Watching' : 'Daemon unreachable'}
            </span>
          </div>
          {watching && health && (
            <p className="text-micro text-faint">
              Flags queries over {health.slowQueryThresholdMs} ms
            </p>
          )}

          {showTrial && (
            <div className="pt-2 mt-1 border-t border-line space-y-1.5">
              <div className="flex items-baseline justify-between gap-2">
                <span className="text-micro text-muted">Trial</span>
                <span className="text-micro tnum text-faint">
                  {trial.analysesRemaining} of {trial.analysesLimit} left
                </span>
              </div>
              <div className="h-1 rounded-full bg-raised overflow-hidden">
                <div
                  className={clsx(
                    'h-full rounded-full',
                    trial.active ? 'bg-heat-warm' : 'bg-heat-crit'
                  )}
                  style={{
                    width: `${trial.analysesLimit > 0
                      ? Math.round((trial.analysesRemaining / trial.analysesLimit) * 100)
                      : 0}%`,
                  }}
                />
              </div>
              {!trial.active ? (
                <Link to="/settings" className="block text-micro text-heat-warm hover:underline">
                  Trial ended — add your API key
                </Link>
              ) : trial.daysRemaining >= 0 ? (
                <p className="text-micro text-faint">
                  {trial.daysRemaining === 0
                    ? 'Ends today'
                    : `${trial.daysRemaining} ${trial.daysRemaining === 1 ? 'day' : 'days'} left`}
                </p>
              ) : null}
            </div>
          )}
        </div>
      </aside>

      <main className="flex-1 overflow-auto">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/query/:id" element={<QueryDetail />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/docs" element={<DocsPage />} />
        </Routes>
      </main>
    </div>
  );
}
