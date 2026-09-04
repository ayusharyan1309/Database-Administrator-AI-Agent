import { Routes, Route, Link, useLocation } from 'react-router-dom';
import { Activity, BarChart3, Settings, Zap, BookOpen } from 'lucide-react';
import clsx from 'clsx';
import Dashboard from './pages/Dashboard';
import QueryDetail from './pages/QueryDetail';
import SettingsPage from './pages/SettingsPage';
import DocsPage from './pages/DocsPage';

const navItems = [
  { path: '/', label: 'Dashboard', icon: BarChart3 },
  { path: '/settings', label: 'Settings', icon: Settings },
  { path: '/docs', label: 'Docs', icon: BookOpen },
];

export default function App() {
  const location = useLocation();

  return (
    <div className="flex h-screen overflow-hidden">
      {/* Sidebar */}
      <aside className="w-64 bg-gray-900 border-r border-gray-800 flex flex-col">
        {/* Logo */}
        <div className="p-6 border-b border-gray-800">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-lg bg-optiq-600 flex items-center justify-center">
              <Zap className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className="text-lg font-bold text-white">OptiQuery</h1>
              <p className="text-xs text-gray-500">AI-DBA Dashboard</p>
            </div>
          </div>
        </div>

        {/* Nav */}
        <nav className="flex-1 p-4 space-y-1">
          {navItems.map(({ path, label, icon: Icon }) => {
            const isActive = location.pathname === path;
            return (
              <Link
                key={path}
                to={path}
                className={clsx(
                  'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors',
                  isActive
                    ? 'bg-optiq-600/20 text-optiq-400'
                    : 'text-gray-400 hover:text-gray-200 hover:bg-gray-800'
                )}
              >
                <Icon className="w-4 h-4" />
                {label}
              </Link>
            );
          })}
        </nav>

        {/* Status bar */}
        <div className="p-4 border-t border-gray-800">
          <div className="flex items-center gap-2 text-xs text-gray-500">
            <Activity className="w-3 h-3 text-green-500" />
            <span>Monitoring active</span>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-auto">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/query/:id" element={<QueryDetail />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/docs" element={<DocsPage />} />
        </Routes>
      </main>
    </div>
  );
}
