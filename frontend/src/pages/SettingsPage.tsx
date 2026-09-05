import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Database, Cpu, Bell, Clock, Save, Loader2,
  CheckCircle, XCircle, Eye, EyeOff, RefreshCw, Globe, Link
} from 'lucide-react';
import clsx from 'clsx';
import { apiFetch } from '../api';

interface SettingItem {
  key: string;
  value: string;
  description: string;
  sensitive: boolean;
}

interface SettingsResponse {
  settings: { database: SettingItem[]; ai: SettingItem[]; notifications: SettingItem[]; slack: SettingItem[]; polling: SettingItem[] };
  aiProvider: string;
  dbType: string;
  builtUrl: string;
}

interface TestResult {
  success: boolean;
  message: string;
  version?: string;
  type?: string;
  url?: string;
  driver?: string;
  extra?: string;
}



// ── Notification presets ──
const NOTIFY_PRESETS = [
  { id: 'slack', label: 'Slack', icon: '💬', placeholder: 'https://hooks.slack.com/services/T.../B.../xxx', help: 'Create a Slack Incoming Webhook at api.slack.com/apps' },
  { id: 'discord', label: 'Discord', icon: '🎮', placeholder: 'https://discord.com/api/webhooks/.../...', help: 'Create a Discord Webhook in Channel Settings → Integrations' },
  { id: 'teams', label: 'MS Teams', icon: '💼', placeholder: 'https://outlook.office.com/webhook/...', help: 'Create an Incoming Webhook connector in Teams Channel' },
  { id: 'generic', label: 'Any Webhook', icon: '🔗', placeholder: 'https://your-service.com/webhook', help: 'Any URL that accepts POST JSON — PagerDuty, OpsGenie, etc.' },
];

// ── Helpers ──
function parseConnectionString(url: string): { host: string; port: string; name: string; user: string; password: string; type: string } | null {
  try {
    const match = url.match(/^(?:postgresql|postgres|mysql|mariadb|sqlserver|mssql|clickhouse):\/\/([^:]+):([^@]+)@([^:]+):(\d+)\/([^?&]+)/);
    if (match && match[1] && match[2] && match[3] && match[4] && match[5]) {
      return { user: match[1], password: match[2], host: match[3], port: match[4], name: match[5], type: detectDbType(url) };
    }
    const matchNoPort = url.match(/^(?:postgresql|postgres|mysql|mariadb|clickhouse):\/\/([^:]+):([^@]+)@([^/]+)\/([^?&]+)/);
    if (matchNoPort && matchNoPort[1] && matchNoPort[2] && matchNoPort[3] && matchNoPort[4]) {
      return { user: matchNoPort[1], password: matchNoPort[2], host: matchNoPort[3], port: '', name: matchNoPort[4], type: detectDbType(url) };
    }
  } catch {}
  return null;
}

function detectDbType(url: string): string {
  if (url.startsWith('postgresql://') || url.startsWith('postgres://')) return 'postgresql';
  if (url.startsWith('mysql://')) return 'mysql';
  if (url.startsWith('mariadb://')) return 'mariadb';
  if (url.startsWith('sqlserver://') || url.startsWith('mssql://')) return 'sqlserver';
  if (url.startsWith('clickhouse://')) return 'clickhouse';
  return 'postgresql';
}



export default function SettingsPage() {
  const queryClient = useQueryClient();

  const { data: settingsData, isLoading } = useQuery<SettingsResponse>({
    queryKey: ['settings'],
    queryFn: async () => {
      const res = await apiFetch('/settings');
      if (!res.ok) throw new Error('Failed to load settings');
      return res.json();
    },
  });

  const [dbConnStr, setDbConnStr] = useState('');
  const [dbForm, setDbForm] = useState<Record<string, string>>({});
  const [aiForm, setAiForm] = useState<Record<string, string>>({});
  const [notifyForm, setNotifyForm] = useState<Record<string, string>>({});
  const [pollingForm, setPollingForm] = useState<Record<string, string>>({});
  const [showPasswords, setShowPasswords] = useState<Record<string, boolean>>({});
  const [testResults, setTestResults] = useState<Record<string, TestResult | undefined>>({});
  const [testing, setTesting] = useState<Record<string, boolean | undefined>>({});
  // Track original values to detect dirty fields
  const [dbOriginal, setDbOriginal] = useState<Record<string, string>>({});
  const [aiOriginal, setAiOriginal] = useState<Record<string, string>>({});
  const [notifyOriginal, setNotifyOriginal] = useState<Record<string, string>>({});
  const [pollingOriginal, setPollingOriginal] = useState<Record<string, string>>({});

  useEffect(() => {
    if (settingsData) {
      const db: Record<string, string> = {};
      const ai: Record<string, string> = {};
      const notify: Record<string, string> = {};
      const poll: Record<string, string> = {};
      settingsData.settings.database?.forEach(s => { db[s.key] = s.value; });
      settingsData.settings.ai?.forEach(s => { ai[s.key] = s.value; });
      settingsData.settings.notifications?.forEach(s => { notify[s.key] = s.value; });
      settingsData.settings.slack?.forEach(s => { if (!notify[s.key]) notify[s.key] = s.value; });
      settingsData.settings.polling?.forEach(s => { poll[s.key] = s.value; });
      setDbForm(db); setAiForm(ai); setNotifyForm(notify); setPollingForm(poll);
      setDbOriginal({ ...db }); setAiOriginal({ ...ai }); setNotifyOriginal({ ...notify }); setPollingOriginal({ ...poll });

      // Reconstruct connection string from saved fields
      const savedUrl = db['db.jdbc_url'] || db['db.url'] || '';
      if (savedUrl) {
        const cleaned = savedUrl.replace(/^jdbc:/, '');
        setDbConnStr(cleaned);
      } else {
        // Build from individual fields
        const host = db['db.host'] || '';
        const port = db['db.port'] || '';
        const name = db['db.name'] || '';
        const user = db['db.username'] || '';
        const pass = db['db.password'] || '';
        if (host && name && user) {
          const type = db['db.type'] || 'postgresql';
          const scheme = type === 'sqlserver' ? 'sqlserver' : type === 'mssql' ? 'mssql' : type;
          setDbConnStr(`${scheme}://${user}${pass ? ':' + pass : ''}@${host}${port ? ':' + port : ''}/${name}`);
        }
      }
    }
  }, [settingsData]);

  const saveMutation = useMutation({
    mutationFn: async (updates: Record<string, string>) => {
      const res = await apiFetch('/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(updates) });
      if (!res.ok) throw new Error('Failed to save');
      return res.json();
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['settings'] }),
  });

  const parsed = dbConnStr.trim() ? parseConnectionString(dbConnStr.trim()) : null;

  // Only return fields that differ from original
  const dirtyFields = (current: Record<string, string>, original: Record<string, string>): Record<string, string> => {
    const dirty: Record<string, string> = {};
    for (const [k, v] of Object.entries(current)) {
      if (v !== original[k]) dirty[k] = v;
    }
    return dirty;
  };

  const handleSaveDb = () => {
    const updates: Record<string, string> = {};
    if (parsed) {
      updates['db.type'] = parsed.type;
      updates['db.host'] = parsed.host;
      updates['db.port'] = parsed.port || (parsed.type === 'mysql' || parsed.type === 'mariadb' ? '3306' : parsed.type === 'sqlserver' ? '1433' : parsed.type === 'clickhouse' ? '8123' : '5432');
      updates['db.name'] = parsed.name;
      updates['db.username'] = parsed.user;
      updates['db.password'] = parsed.password;
      updates['db.jdbc_url'] = dbConnStr.trim();
    }
    // Only add fields that aren't already set from parsed connection string
    Object.entries(dbForm).forEach(([k, v]) => { if (!updates[k]) updates[k] = v; });
    // Filter to only dirty fields
    const dirty = dirtyFields(updates, dbOriginal);
    if (Object.keys(dirty).length === 0) { handleTestDb(); return; }
    saveMutation.mutate(dirty, { onSuccess: async () => {
      // Update originals to new values
      setDbOriginal({ ...dbForm, ...updates });
      await handleTestDb();
    }});
  };
  const handleSaveAi = () => {
    const dirty = dirtyFields(aiForm, aiOriginal);
    if (Object.keys(dirty).length === 0) { handleTestAi(); return; }
    saveMutation.mutate(dirty, { onSuccess: async () => {
      setAiOriginal({ ...aiForm });
      await handleTestAi();
    }});
  };
  const handleSaveNotify = () => {
    const dirty = dirtyFields(notifyForm, notifyOriginal);
    if (Object.keys(dirty).length === 0) { handleTestWebhook(); return; }
    saveMutation.mutate(dirty, { onSuccess: async () => {
      setNotifyOriginal({ ...notifyForm });
      await handleTestWebhook();
    }});
  };
  const handleSavePolling = () => {
    const dirty = dirtyFields(pollingForm, pollingOriginal);
    if (Object.keys(dirty).length === 0) return;
    saveMutation.mutate(dirty, { onSuccess: () => { setPollingOriginal({ ...pollingForm }); } });
  };

  const handleTestDb = async () => {
    setTesting(t => ({ ...t, db: true })); setTestResults(t => ({ ...t, db: undefined }));
    try {
      const body = parsed ? { type: parsed.type, host: parsed.host, port: parsed.port, name: parsed.name, username: parsed.user, password: parsed.password } : { type: dbForm['db.type'], host: dbForm['db.host'], port: dbForm['db.port'], name: dbForm['db.name'], username: dbForm['db.username'], password: dbForm['db.password'] };
      const res = await apiFetch('/settings/test-db', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      const data = await res.json();
      setTestResults(t => ({ ...t, db: data }));
    } catch { setTestResults(t => ({ ...t, db: { success: false, message: 'Request failed' } })); }
    finally { setTesting(t => ({ ...t, db: false })); }
  };

  const handleTestAi = async () => {
    setTesting(t => ({ ...t, ai: true })); setTestResults(t => ({ ...t, ai: undefined }));
    try {
      const res = await apiFetch('/settings/test-ai', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ providerType: aiForm['ai.provider'], baseUrl: aiForm['ai.base_url'], apiKey: aiForm['ai.api_key'], model: aiForm['ai.model'] }) });
      const data = await res.json();
      setTestResults(t => ({ ...t, ai: data }));
    } catch { setTestResults(t => ({ ...t, ai: { success: false, message: 'Request failed' } })); }
    finally { setTesting(t => ({ ...t, ai: false })); }
  };

  const handleTestWebhook = async () => {
    setTesting(t => ({ ...t, webhook: true })); setTestResults(t => ({ ...t, webhook: undefined }));
    try {
      const res = await apiFetch('/settings/test-webhook', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ type: notifyForm['notify.type'], url: notifyForm['notify.webhook_url'] }) });
      const data = await res.json();
      setTestResults(t => ({ ...t, webhook: data }));
    } catch { setTestResults(t => ({ ...t, webhook: { success: false, message: 'Request failed' } })); }
    finally { setTesting(t => ({ ...t, webhook: false })); }
  };

  if (isLoading) return <div className="flex items-center justify-center h-full"><Loader2 className="w-8 h-8 text-signal animate-spin" /></div>;

  const activeNotifyType = notifyForm['notify.type'] || 'slack';

  return (
    <div className="p-8 max-w-4xl space-y-8">
      <div>
        <h2 className="text-title font-semibold text-ink">Settings</h2>
        <p className="text-muted mt-1">Configure database, AI provider, notifications, and polling</p>
      </div>

      {/* ─── Target Database ─── */}
      <Card icon={Database} title="Target Database" subtitle="Paste your connection string to connect" iconColor="text-blue-400">
        <div className="space-y-4">
          <div>
            <label className="block text-xs font-semibold text-muted mb-2">Connection String</label>
            <div className="flex items-center gap-2">
              <Link className="w-4 h-4 text-muted flex-shrink-0" />
              <input
                type="text"
                value={dbConnStr}
                onChange={e => setDbConnStr(e.target.value)}
                placeholder="postgresql://user:password@host:5432/database"
                className="flex-1 px-4 py-2.5 bg-abyss border border-line rounded-lg text-sm text-ink placeholder-faint focus:outline-none focus:ring-2 focus:ring-signal font-mono"
              />
            </div>
            {parsed && (
              <p className="text-xs text-green-500 mt-2 flex items-center gap-1">
                <CheckCircle className="w-3 h-3" />
                Detected <span className="font-medium">{parsed.type}</span> — {parsed.host}:{parsed.port || 'default'}/{parsed.name} as {parsed.user}
              </p>
            )}
            {!parsed && dbConnStr.trim() && (
              <p className="text-xs text-yellow-500 mt-2">Paste a connection string — postgresql://, mysql://, mariadb://, sqlserver://, clickhouse://</p>
            )}
          </div>

          {/* Quick examples */}
          <div className="flex flex-wrap gap-2 text-xs text-faint">
            <span className="text-muted">Examples:</span>
            {[
              { label: 'Supabase', value: 'postgresql://postgres:password@db.your-project.supabase.co:5432/postgres' },
              { label: 'Neon', value: 'postgresql://user:password@ep-xxx.us-east-2.aws.neon.tech:5432/neondb' },
              { label: 'Local PG', value: 'postgresql://user:password@localhost:5432/mydb' },
            ].map(ex => (
              <button key={ex.label} onClick={() => setDbConnStr(ex.value)} className="hover:text-ink/90 transition-colors">
                {ex.label}
              </button>
            ))}
          </div>
        </div>

        <Actions onSave={handleSaveDb} onTest={handleTestDb} testing={testing.db} saving={saveMutation.isPending} testLabel="Test Connection" result={testResults.db} renderResult={r => <DbTestResult result={r} />} />
      </Card>

      {/* ─── AI Provider ─── */}
      <Card icon={Cpu} title="AI Provider" subtitle="Connect any LLM — local or cloud" iconColor="text-purple-400">
        <div className="space-y-4">
          <Field label="Base URL" value={aiForm['ai.base_url'] || ''} onChange={v => setAiForm(f => ({ ...f, 'ai.base_url': v }))} placeholder="http://localhost:11434/v1" icon={<Globe className="w-4 h-4 text-muted" />} />
          <div className="grid grid-cols-4 gap-4">
            <div className="col-span-1"><Field label="Model" value={aiForm['ai.model'] || ''} onChange={v => setAiForm(f => ({ ...f, 'ai.model': v }))} placeholder="gpt-4o" /></div>
            <div className="col-span-3"><Field label="API Key" value={aiForm['ai.api_key'] || ''} onChange={v => setAiForm(f => ({ ...f, 'ai.api_key': v }))} type={showPasswords['ai.key'] ? 'text' : 'password'} placeholder="Optional for local LLMs" suffix={<EyeToggle show={!!showPasswords['ai.key']} onToggle={() => setShowPasswords(p => ({ ...p, 'ai.key': !p['ai.key'] }))} />} /></div>
          </div>
          {/* Quick examples */}
          <div className="flex flex-wrap gap-2 text-xs text-faint">
            <span className="text-muted">Examples:</span>
            {[
              { label: 'BazaarLink (Free)', url: 'https://api.bazaarlink.ai/v1', model: 'auto:free' },
              { label: 'Ollama', url: 'http://localhost:11434/v1', model: 'llama3' },
              { label: 'OpenAI', url: 'https://api.openai.com/v1', model: 'gpt-4o' },
              { label: 'DeepSeek', url: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
              { label: 'Groq', url: 'https://api.groq.com/openai/v1', model: 'llama3-70b-8192' },
            ].map(ex => (
              <button key={ex.label} onClick={() => setAiForm(f => ({ ...f, 'ai.base_url': ex.url, 'ai.model': ex.model }))} className="hover:text-ink/90 transition-colors">
                {ex.label}
              </button>
            ))}
          </div>
        </div>
        <Actions onSave={handleSaveAi} onTest={handleTestAi} testing={testing.ai} saving={saveMutation.isPending} testLabel="Test Connection" result={testResults.ai} renderResult={r => <TestResultBanner result={r} />} />
      </Card>

      {/* ─── Notifications ─── */}
      <Card icon={Bell} title="Notifications" subtitle="Get alerts when slow queries are detected" iconColor="text-green-400">
        <div className="flex items-center gap-3 mb-1">
          <label className="text-sm text-muted">Enable</label>
          <button onClick={() => setNotifyForm(f => ({ ...f, 'notify.enabled': f['notify.enabled'] === 'true' ? 'false' : 'true' }))} className={clsx('relative w-10 h-5 rounded-full transition-colors', notifyForm['notify.enabled'] === 'true' ? 'bg-green-500' : 'bg-edge')}>
            <span className={clsx('absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white transition-transform', notifyForm['notify.enabled'] === 'true' && 'translate-x-5')} />
          </button>
        </div>
        <PresetGrid label="Choose a Platform" presets={NOTIFY_PRESETS} activeId={activeNotifyType} onSelect={(p) => setNotifyForm(f => ({ ...f, 'notify.type': p.id }))} activeColor="green" />
        <div className="space-y-4">
          <Field label="Webhook URL" value={notifyForm['notify.webhook_url'] || ''} onChange={v => setNotifyForm(f => ({ ...f, 'notify.webhook_url': v }))} placeholder={NOTIFY_PRESETS.find(p => p.id === activeNotifyType)?.placeholder || ''} icon={<Globe className="w-4 h-4 text-muted" />} />
          <p className="text-xs text-faint">{NOTIFY_PRESETS.find(p => p.id === activeNotifyType)?.help}</p>
        </div>
        <Actions onSave={handleSaveNotify} onTest={handleTestWebhook} testing={testing.webhook} saving={saveMutation.isPending} testLabel="Send Test Alert" result={testResults.webhook} renderResult={r => <TestResultBanner result={r} />} />
      </Card>

      {/* ─── Polling ─── */}
      <Card icon={Clock} title="Polling" subtitle="How often to check for slow queries" iconColor="text-signal">
        <div className="space-y-5">
          {/* Poll Interval */}
          <div>
            <label className="block text-xs font-semibold text-muted mb-2">Check Every</label>
            <div className="flex flex-wrap gap-2">
              {[{ label: '30s', value: '30000' }, { label: '1 min', value: '60000' }, { label: '2 min', value: '120000' }, { label: '5 min', value: '300000' }, { label: '10 min', value: '600000' }, { label: '30 min', value: '1800000' }].map(p => (
                <button key={p.value} onClick={() => setPollingForm(f => ({ ...f, 'polling.interval_ms': p.value }))}
                  className={clsx(
                    'px-3 py-1.5 rounded-lg text-xs font-medium border transition-all',
                    pollingForm['polling.interval_ms'] === p.value
                      ? 'bg-signal-wash border-signal/40 text-signal-soft'
                      : 'bg-abyss/50 border-line text-muted hover:border-edge hover:text-ink/90'
                  )}>{p.label}</button>
              ))}
              <div className="flex items-center gap-1">
                <input type="number" min="1" value={pollingForm['polling.interval_ms'] || ''} onChange={e => setPollingForm(f => ({ ...f, 'polling.interval_ms': e.target.value }))} placeholder="custom" className="w-24 px-2 py-1.5 bg-abyss border border-line rounded-lg text-xs text-ink placeholder-faint focus:outline-none focus:ring-2 focus:ring-signal font-mono" />
                <span className="text-xs text-faint">ms</span>
              </div>
            </div>
          </div>

          {/* Slow Threshold */}
          <div>
            <label className="block text-xs font-semibold text-muted mb-2">Flag Queries Slower Than</label>
            <div className="flex flex-wrap gap-2">
              {[{ label: '>100ms', value: '100' }, { label: '>200ms', value: '200' }, { label: '>500ms', value: '500' }, { label: '>1s', value: '1000' }, { label: '>2s', value: '2000' }, { label: '>5s', value: '5000' }].map(p => (
                <button key={p.value} onClick={() => setPollingForm(f => ({ ...f, 'polling.slow_threshold_ms': p.value }))}
                  className={clsx(
                    'px-3 py-1.5 rounded-lg text-xs font-medium border transition-all',
                    pollingForm['polling.slow_threshold_ms'] === p.value
                      ? 'bg-signal-wash border-signal/40 text-signal-soft'
                      : 'bg-abyss/50 border-line text-muted hover:border-edge hover:text-ink/90'
                  )}>{p.label}</button>
              ))}
              {/* Custom input */}
              <div className="flex items-center gap-1">
                <input type="number" value={pollingForm['polling.slow_threshold_ms'] || ''} onChange={e => setPollingForm(f => ({ ...f, 'polling.slow_threshold_ms': e.target.value }))} placeholder="custom" className="w-20 px-2 py-1.5 bg-abyss border border-line rounded-lg text-xs text-ink placeholder-faint focus:outline-none focus:ring-2 focus:ring-signal font-mono" />
                <span className="text-xs text-faint">ms</span>
              </div>
            </div>
          </div>

          {/* Batch size */}
          <div>
            <label className="block text-xs font-semibold text-muted mb-2">Analyze At Most</label>
            <div className="flex items-center gap-2">
              <input type="number" min="1" value={pollingForm['polling.max_queries_per_poll'] || ''} onChange={e => setPollingForm(f => ({ ...f, 'polling.max_queries_per_poll': e.target.value }))} placeholder="10" className="w-20 px-2 py-1.5 bg-abyss border border-line rounded-lg text-xs text-ink placeholder-faint focus:outline-none focus:ring-2 focus:ring-signal font-mono" />
              <span className="text-xs text-faint">queries per check — the slowest ones are analyzed first</span>
            </div>
          </div>
        </div>
        <div className="flex items-center gap-3 mt-6">
          <SaveBtn onClick={handleSavePolling} saving={saveMutation.isPending} />
          <span className="text-xs text-faint">Applies from the next check. No restart needed.</span>
        </div>
      </Card>
    </div>
  );
}

// ─── Components ───

function Card({ icon: Icon, title, subtitle, iconColor, children }: { icon: React.ComponentType<{ className?: string }>; title: string; subtitle: string; iconColor: string; children: React.ReactNode }) {
  return (
    <div className="bg-surface rounded-xl border border-line overflow-hidden">
      <div className="px-6 py-5 border-b border-line">
        <div className="flex items-center gap-3">
          <Icon className={clsx('w-5 h-5', iconColor)} />
          <div>
            <h3 className="text-lg font-semibold text-ink">{title}</h3>
            <p className="text-sm text-muted">{subtitle}</p>
          </div>
        </div>
      </div>
      <div className="px-6 py-5 space-y-5">{children}</div>
    </div>
  );
}

function PresetGrid<T extends { id: string; label: string; icon: string }>({ label, presets, activeId, onSelect, activeColor, customActiveCheck }: {
  label: string; presets: T[]; activeId: string; onSelect: (p: T) => void; activeColor: string; customActiveCheck?: (p: T) => boolean;
}) {
  const colorMap: Record<string, string> = {
    blue: 'bg-blue-500/15 border-blue-500/40 text-blue-300 shadow-sm shadow-blue-500/10',
    purple: 'bg-purple-500/15 border-purple-500/40 text-purple-300 shadow-sm shadow-purple-500/10',
    green: 'bg-green-500/15 border-green-500/40 text-green-300 shadow-sm shadow-green-500/10',
  };
  const cols = presets.length <= 4 ? 'grid-cols-4' : presets.length <= 6 ? 'grid-cols-6' : 'grid-cols-5';
  return (
    <div>
      <label className="block text-xs font-semibold text-muted mb-3">{label}</label>
      <div className={clsx('grid gap-2', cols)}>
        {presets.map(p => {
          const isActive = customActiveCheck ? customActiveCheck(p) : activeId === p.id;
          return (
            <button key={p.id} onClick={() => onSelect(p)} className={clsx('flex flex-col items-center gap-1.5 px-3 py-3 rounded-xl border text-xs font-medium transition-all', isActive ? colorMap[activeColor] || colorMap.blue : 'bg-abyss/50 border-line text-muted hover:border-edge hover:text-ink/90')}>
              <span className="text-lg">{p.icon}</span>
              <span className="leading-tight text-center">{p.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function Field({ label, value, onChange, type = 'text', placeholder, suffix, icon }: {
  label: string; value: string; onChange: (v: string) => void; type?: string; placeholder?: string; suffix?: React.ReactNode; icon?: React.ReactNode;
}) {
  return (
    <div>
      <label className="block text-xs font-medium text-muted mb-1.5">{label}</label>
      <div className="flex items-center gap-2">
        {icon && <span className="flex-shrink-0">{icon}</span>}
        <input type={type} value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} className="flex-1 px-4 py-2.5 bg-abyss border border-line rounded-lg text-sm text-ink placeholder-faint focus:outline-none focus:ring-2 focus:ring-signal font-mono" />
        {suffix}
      </div>
    </div>
  );
}

function EyeToggle({ show, onToggle }: { show: boolean; onToggle: () => void }) {
  return <button onClick={onToggle} className="p-1">{show ? <EyeOff className="w-4 h-4 text-muted" /> : <Eye className="w-4 h-4 text-muted" />}</button>;
}

function SaveBtn({ onClick, saving }: { onClick: () => void; saving: boolean }) {
  return <button onClick={onClick} disabled={saving} className="flex items-center gap-2 px-4 py-2 rounded-lg bg-signal text-ink text-sm font-medium hover:bg-signal transition-colors disabled:opacity-50">{saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />} Save</button>;
}

function TestBtn({ onClick, testing, label }: { onClick: () => void; testing?: boolean; label: string }) {
  return <button onClick={onClick} disabled={testing} className="flex items-center gap-2 px-4 py-2 rounded-lg bg-raised text-ink/90 text-sm font-medium hover:bg-edge transition-colors disabled:opacity-50">{testing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />} {label}</button>;
}

function Actions({ onSave, onTest, testing, saving, testLabel, result, renderResult }: {
  onSave: () => void; onTest: () => void; testing?: boolean; saving: boolean; testLabel: string; result?: TestResult; renderResult: (r: TestResult) => React.ReactNode;
}) {
  return (
    <>
      <div className="flex items-center gap-3 pt-2">
        <SaveBtn onClick={onSave} saving={saving} />
        <TestBtn onClick={onTest} testing={testing} label={testLabel} />
      </div>
      {result && <div className="mt-3">{renderResult(result)}</div>}
    </>
  );
}

function DbTestResult({ result }: { result?: TestResult }) {
  if (!result) return null;
  return <div className={clsx('flex items-start gap-2 p-3 rounded-lg text-sm whitespace-pre-line', result.success ? 'bg-green-500/10 text-green-400' : 'bg-red-500/10 text-red-400')}>
    {result.success ? <CheckCircle className="w-4 h-4 mt-0.5 flex-shrink-0" /> : <XCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />}
    <div>
      <p>{result.message}</p>
      {result.version && <p className="text-xs text-muted mt-1 font-mono">{result.version}</p>}
      {result.extra && <p className="text-xs text-muted mt-0.5">{result.extra}</p>}
    </div>
  </div>;
}

function TestResultBanner({ result }: { result?: TestResult }) {
  if (!result) return null;
  return <div className={clsx('flex items-start gap-2 p-3 rounded-lg text-sm whitespace-pre-line', result.success ? 'bg-green-500/10 text-green-400' : 'bg-red-500/10 text-red-400')}>
    {result.success ? <CheckCircle className="w-4 h-4 mt-0.5 flex-shrink-0" /> : <XCircle className="w-4 h-4 mt-0.5 flex-shrink-0" />}
    <p>{result.message}</p>
  </div>;
}
