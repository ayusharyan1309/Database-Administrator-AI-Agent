import { useState } from 'react';
import { Database, Cpu, Bell, Clock, Terminal, Copy, Check } from 'lucide-react';
import clsx from 'clsx';

interface DocBlock {
  label: string;
  color: 'green' | 'red' | 'grey' | 'blue' | 'yellow';
  lines: string[];
}

interface DocSubSection {
  title: string;
  blocks: DocBlock[];
}

interface DocSection {
  id: string;
  icon: React.ComponentType<{ className?: string }>;
  title: string;
  subsections: DocSubSection[];
}

const docs: DocSection[] = [
  {
    id: 'database',
    icon: Database,
    title: 'Target Database',
    subsections: [
      {
        title: 'Connection String',
        blocks: [
          { label: 'Format', color: 'green', lines: [
            'postgresql://username:password@host:port/database',
            'mysql://username:password@host:port/database',
            'mariadb://username:password@host:port/database',
            'sqlserver://username:password@host:port/database',
            'clickhouse://username:password@host:port/database',
          ]},
          { label: 'Examples', color: 'blue', lines: [
            'postgresql://postgres:xxxx@db.xxx.supabase.co:5432/postgres',
            'postgresql://user:pass@ep-xxx.us-east-2.aws.neon.tech:5432/neondb',
            'mysql://root:pass@localhost:3306/myapp',
          ]},
        ],
      },
      {
        title: 'Supported Databases',
        blocks: [
          { label: 'Local', color: 'green', lines: [
            '✅ PostgreSQL',
            '✅ MySQL',
            '✅ MariaDB',
            '✅ SQL Server',
            '✅ ClickHouse',
            '✅ SQLite',
          ]},
          { label: 'Cloud', color: 'blue', lines: [
            '✅ Supabase (PostgreSQL)',
            '✅ Neon (PostgreSQL)',
            '✅ Railway (PostgreSQL)',
            '✅ Render (PostgreSQL)',
            '✅ AWS RDS (PostgreSQL/MySQL)',
            '✅ Google Cloud SQL',
            '✅ Azure Database',
          ]},
        ],
      },
      {
        title: 'Required Permissions',
        blocks: [
          { label: 'PostgreSQL — Create read-only user', color: 'yellow', lines: [
            "CREATE USER optiq_monitor WITH PASSWORD 'your_password';",
            'GRANT CONNECT ON DATABASE your_db TO optiq_monitor;',
            'GRANT USAGE ON SCHEMA public TO optiq_monitor;',
            'GRANT SELECT ON ALL TABLES IN SCHEMA public TO optiq_monitor;',
          ]},
          { label: 'Required extensions', color: 'red', lines: [
            'pg_stat_statements — slow query detection',
            'information_schema — schema extraction',
            'pg_indexes — index analysis',
          ]},
        ],
      },
      {
        title: 'Supabase Setup',
        blocks: [
          { label: 'Steps', color: 'green', lines: [
            '1. Go to Supabase Dashboard → Settings → Database',
            '2. Scroll to "Connection string"',
            '3. Click "View credentials"',
            '4. Copy the Direct connection URL',
            '5. Paste in OptiQuery → Save → Test',
          ]},
          { label: 'Important', color: 'red', lines: [
            '⚠️  Use Direct connection, NOT Pooler (Transaction mode)',
            '⚠️  pg_stat_statements enabled by default',
          ]},
        ],
      },
      {
        title: 'Neon Setup',
        blocks: [
          { label: 'Steps', color: 'green', lines: [
            '1. Go to Neon Dashboard → Connection Details',
            '2. Select "Psql" or "Connection string"',
            '3. Copy the full connection string',
            '4. Paste in OptiQuery → Save → Test',
          ]},
          { label: 'Note', color: 'grey', lines: [
            'Neon uses branching — you can monitor any branch',
          ]},
        ],
      },
    ],
  },
  {
    id: 'ai',
    icon: Cpu,
    title: 'AI Provider',
    subsections: [
      {
        title: 'How It Works',
        blocks: [
          { label: 'Pipeline', color: 'green', lines: [
            '1. Detect slow query (>threshold)',
            '2. Extract table schema + indexes',
            '3. Run EXPLAIN ANALYZE',
            '4. Send to LLM for diagnosis',
            '5. Return: root cause + suggested fix',
          ]},
          { label: 'Output', color: 'blue', lines: [
            '• root_cause      — why it is slow',
            '• suggested_sql   — CREATE INDEX / rewrite',
            '• confidence_score — 1-100',
            '• risk_level      — LOW / MEDIUM / HIGH',
          ]},
          { label: 'Safety', color: 'red', lines: [
            '🔒 No row data (PII) sent to LLM',
            '🔒 Only schema + query structure',
            '🔒 DDL never auto-executed',
          ]},
        ],
      },
      {
        title: 'Local LLMs (Free)',
        blocks: [
          { label: 'Ollama', color: 'green', lines: [
            'brew install ollama',
            'ollama pull llama3',
            '',
            'Base URL:  http://localhost:11434/v1',
            'Model:     llama3',
            'API Key:   (empty)',
          ]},
          { label: 'LM Studio', color: 'green', lines: [
            'Download from lmstudio.ai',
            'Start local server',
            '',
            'Base URL:  http://localhost:1234/v1',
            'Model:     default',
            'API Key:   (empty)',
          ]},
          { label: 'vLLM', color: 'green', lines: [
            'pip install vllm',
            'vllm serve meta-llama/Llama-3-70b-chat-hf',
            '',
            'Base URL:  http://localhost:8000/v1',
            'Model:     default',
            'API Key:   (empty)',
          ]},
        ],
      },
      {
        title: 'Cloud LLMs',
        blocks: [
          { label: 'OpenAI', color: 'blue', lines: [
            'Key:     platform.openai.com/api-keys',
            'URL:     https://api.openai.com/v1',
            'Models:  gpt-4o, gpt-4o-mini, gpt-3.5-turbo',
          ]},
          { label: 'Anthropic', color: 'blue', lines: [
            'Key:     console.anthropic.com',
            'URL:     https://api.anthropic.com',
            'Models:  claude-3-5-sonnet, claude-3-haiku',
            'Note:    Use "Anthropic" protocol toggle',
          ]},
          { label: 'DeepSeek', color: 'blue', lines: [
            'Key:     platform.deepseek.com',
            'URL:     https://api.deepseek.com/v1',
            'Models:  deepseek-chat',
          ]},
          { label: 'Groq', color: 'blue', lines: [
            'Key:     console.groq.com',
            'URL:     https://api.groq.com/openai/v1',
            'Models:  llama3-70b-8192',
          ]},
        ],
      },
    ],
  },
  {
    id: 'notifications',
    icon: Bell,
    title: 'Notifications',
    subsections: [
      {
        title: 'Slack',
        blocks: [
          { label: 'Setup', color: 'green', lines: [
            '1. api.slack.com/apps → Create New App',
            '2. Add "Incoming Webhooks"',
            '3. Activate → Add to Workspace',
            '4. Select channel → Authorize',
            '5. Copy Webhook URL',
          ]},
          { label: 'URL Format', color: 'grey', lines: [
            'https://hooks.slack.com/services/T.../B.../xxx',
          ]},
        ],
      },
      {
        title: 'Discord',
        blocks: [
          { label: 'Setup', color: 'green', lines: [
            '1. Right-click channel → Edit Channel',
            '2. Integrations → Webhooks',
            '3. New Webhook → Name it',
            '4. Choose channel → Copy URL',
          ]},
          { label: 'URL Format', color: 'grey', lines: [
            'https://discord.com/api/webhooks/{id}/{token}',
          ]},
        ],
      },
      {
        title: 'MS Teams',
        blocks: [
          { label: 'Setup', color: 'green', lines: [
            '1. Channel → ••• → Connectors',
            '2. Find "Incoming Webhook" → Configure',
            '3. Name it → Create',
            '4. Copy URL',
          ]},
          { label: 'URL Format', color: 'grey', lines: [
            'https://outlook.office.com/webhook/.../IncomingWebhook/.../...',
          ]},
        ],
      },
      {
        title: 'Generic Webhook',
        blocks: [
          { label: 'Works with', color: 'blue', lines: [
            '• PagerDuty',
            '• OpsGenie',
            '• Zapier',
            '• n8n',
            '• Custom APIs',
          ]},
          { label: 'Payload', color: 'yellow', lines: [
            'OptiQuery auto-formats based on platform:',
            '• Slack   → Block Kit (rich cards)',
            '• Discord → Embeds (colored by risk)',
            '• Teams   → MessageCard format',
            '• Generic → Plain JSON',
          ]},
        ],
      },
    ],
  },
  {
    id: 'polling',
    icon: Clock,
    title: 'Polling',
    subsections: [
      {
        title: 'Poll Interval',
        blocks: [
          { label: 'Default', color: 'grey', lines: ['5 minutes (300000ms)']},
          { label: 'Recommended', color: 'green', lines: [
            'Production:      1-5 minutes',
            'Development:     30s - 1 minute',
            'High traffic:    5-10 minutes',
          ]},
        ],
      },
      {
        title: 'Slow Query Threshold',
        blocks: [
          { label: 'Default', color: 'grey', lines: ['500ms']},
          { label: 'Recommended', color: 'green', lines: [
            'OLTP apps:           200-500ms',
            'Analytics/reporting: 2000-5000ms',
            'Mixed workloads:     500-1000ms',
            'Background jobs:     5000-10000ms',
          ]},
        ],
      },
    ],
  },
];

export default function DocsPage() {
  const [activeSection, setActiveSection] = useState('database');
  const [copiedKey, setCopiedKey] = useState<string | null>(null);

  const section = docs.find(s => s.id === activeSection) ?? docs[0];

  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopiedKey(key);
    setTimeout(() => setCopiedKey(null), 2000);
  };

  const colorMap = {
    green: { header: 'bg-green-500/15 text-green-400', dot: 'bg-green-500', text: 'text-green-400/80' },
    red: { header: 'bg-red-500/15 text-red-400', dot: 'bg-red-500', text: 'text-red-400/80' },
    grey: { header: 'bg-gray-500/15 text-gray-400', dot: 'bg-gray-500', text: 'text-gray-400/80' },
    blue: { header: 'bg-blue-500/15 text-blue-400', dot: 'bg-blue-500', text: 'text-blue-400/80' },
    yellow: { header: 'bg-yellow-500/15 text-yellow-400', dot: 'bg-yellow-500', text: 'text-yellow-400/80' },
  };

  return (
    <div className="flex h-full">
      {/* Sidebar */}
      <aside className="w-56 border-r border-gray-800 bg-gray-950 flex-shrink-0">
        <div className="p-4 border-b border-gray-800">
          <div className="flex items-center gap-2">
            <Terminal className="w-4 h-4 text-gray-500" />
            <span className="text-xs font-mono text-gray-500">docs/</span>
          </div>
        </div>
        <nav className="p-2 space-y-0.5">
          {docs.map(doc => (
            <button
              key={doc.id}
              onClick={() => setActiveSection(doc.id)}
              className={clsx(
                'w-full flex items-center gap-2.5 px-3 py-2 rounded-md text-sm font-mono transition-colors text-left',
                activeSection === doc.id
                  ? 'bg-gray-800 text-white'
                  : 'text-gray-500 hover:text-gray-300 hover:bg-gray-800/50'
              )}
            >
              <doc.icon className="w-3.5 h-3.5" />
              {doc.title}
            </button>
          ))}
        </nav>
      </aside>

      {/* Content */}
      <div className="flex-1 overflow-auto p-6">
        <div className="max-w-3xl space-y-8">
          {/* Sections */}
          {section?.subsections.map((sub, i) => (
            <div key={i}>
              <h3 className="text-sm font-semibold text-gray-300 mb-3">{sub.title}</h3>
              <div className="grid gap-2" style={{ gridTemplateColumns: sub.blocks.length <= 2 ? `repeat(${sub.blocks.length}, 1fr)` : 'repeat(2, 1fr)' }}>
                {sub.blocks.map((block, j) => {
                  const c = colorMap[block.color];
                  const key = `${section.id}-${i}-${j}`;
                  return (
                    <div key={j} className="rounded-lg border border-gray-700/50 bg-gray-900 overflow-hidden">
                      <div className={clsx('flex items-center justify-between px-3 py-2 border-b border-gray-700/50', c.header)}>
                        <div className="flex items-center gap-2">
                          <div className={clsx('w-2 h-2 rounded-full', c.dot)} />
                          <span className="text-xs font-mono font-semibold uppercase tracking-wide">{block.label}</span>
                        </div>
                        <button onClick={() => handleCopy(block.lines.join('\n'), key)} className="p-1 rounded hover:bg-white/10 transition-colors">
                          {copiedKey === key ? <Check className="w-3 h-3 text-green-400" /> : <Copy className="w-3 h-3 text-gray-500 hover:text-gray-300" />}
                        </button>
                      </div>
                      <div className="p-3 bg-black/30">
                        <pre className="text-xs font-mono leading-relaxed whitespace-pre-wrap">
                          {block.lines.map((line, k) => {
                            if (line === '') return <div key={k} className="h-2" />;
                            const isHighlight = line.startsWith('✅') || line.startsWith('⚠️') || line.startsWith('🔒') || line.startsWith('•');
                            const isNumbered = /^\d+\./.test(line);
                            const isCode = line.startsWith('CREATE') || line.startsWith('GRANT') || line.startsWith('pip ') || line.startsWith('brew ') || line.startsWith('ollama ') || line.startsWith('vllm ') || line.startsWith('http');
                            return (
                              <div key={k} className={clsx(
                                isHighlight && 'text-gray-300',
                                isNumbered && 'text-gray-300',
                                isCode && 'text-green-400/90',
                                !isHighlight && !isNumbered && !isCode && 'text-gray-500'
                              )}>
                                {line}
                              </div>
                            );
                          })}
                        </pre>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
