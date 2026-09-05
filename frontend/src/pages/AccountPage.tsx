import { useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Check, Loader2, Pencil, Plus, Trash2, X, MailCheck, LogOut, ShieldAlert, Clock,
} from 'lucide-react';
import clsx from 'clsx';
import {
  fetchOrganizations, renameOrganization, fetchMembers, addMember, changeMemberRole,
  removeMember, fetchProjects, createProject, updateProject, deleteProject,
  fetchInvites, cancelInvite, canManage, ROLE_LABEL, ROLE_NOTE,
  type Invite, type Member, type OrgRole, type Project,
} from '../lib/orgs';
import { useAuth } from '../lib/auth';

type Tab = 'profile' | 'team' | 'databases';

const ENVIRONMENTS: Project['environment'][] = ['production', 'staging', 'development'];

const ENV_TONE: Record<Project['environment'], string> = {
  production: 'text-heat-crit border-heat-crit/35 bg-heat-crit/10',
  staging: 'text-heat-warm border-heat-warm/35 bg-heat-warm/10',
  development: 'text-muted border-line bg-raised',
};

export default function AccountPage() {
  const { user, signOut } = useAuth();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<Tab>('profile');

  const { data: orgs, isLoading, error } = useQuery({
    queryKey: ['orgs'],
    queryFn: fetchOrganizations,
    retry: false,
  });
  const org = orgs?.[0];

  const { data: memberData } = useQuery({
    queryKey: ['members', org?.id],
    queryFn: () => fetchMembers(org!.id),
    enabled: !!org,
  });

  const { data: projects } = useQuery({
    queryKey: ['projects', org?.id],
    queryFn: () => fetchProjects(org!.id),
    enabled: !!org,
  });

  const { data: invites } = useQuery({
    queryKey: ['invites', org?.id],
    queryFn: () => fetchInvites(org!.id),
    enabled: !!org,
  });

  const refresh = (keys: string[]) =>
    keys.forEach((k) => queryClient.invalidateQueries({ queryKey: k === 'orgs' ? ['orgs'] : [k, org?.id] }));

  if (isLoading) {
    return (
      <Shell>
        <div className="h-7 w-52 rounded-xs bg-raised" />
        <div className="h-40 rounded-lg bg-surface border border-line" />
      </Shell>
    );
  }

  // The commonest cause by far is a backend that predates these endpoints,
  // so say that rather than showing an empty page.
  if (error || !org) {
    return (
      <Shell>
        <div className="panel px-6 py-8 flex items-start gap-3 max-w-[62ch]">
          <ShieldAlert className="w-4 h-4 text-heat-warm mt-0.5 shrink-0" />
          <div>
            <h1 className="head">Your team could not be loaded</h1>
            <p className="text-base text-muted mt-1.5 leading-relaxed">
              {error
                ? 'The server did not return a team. If it is running an older build, rebuild it with "mvn package" and restart — the team endpoints will not exist until then.'
                : 'No team came back for your account. Signing out and back in creates one.'}
            </p>
            <button onClick={() => signOut()} className="btn mt-4">
              <LogOut className="w-3.5 h-3.5" />
              Sign out
            </button>
          </div>
        </div>
      </Shell>
    );
  }

  const yourRole = memberData?.yourRole ?? org.yourRole;
  const manage = canManage(yourRole);
  const members = memberData?.members ?? [];

  const tabs: { id: Tab; label: string; count?: number }[] = [
    { id: 'profile', label: 'Profile' },
    { id: 'team', label: 'Team', count: members.length + (invites?.length ?? 0) },
    { id: 'databases', label: 'Databases', count: projects?.length },
  ];

  return (
    <Shell>
      <header className="space-y-4">
        <div className="flex items-center gap-3.5">
          <Avatar user={{ photoURL: user?.photoURL ?? null, email: user?.email ?? null }} size="lg" />
          <div className="min-w-0">
            <h1 className="text-title font-semibold text-ink truncate">
              {user?.displayName || user?.email?.split('@')[0] || 'Your account'}
            </h1>
            <p className="sub">
              {org.name} · {ROLE_LABEL[yourRole].toLowerCase()}
            </p>
          </div>
        </div>

        <nav className="flex gap-1 border-b border-line" role="tablist">
          {tabs.map((t) => (
            <button
              key={t.id}
              role="tab"
              aria-selected={tab === t.id}
              onClick={() => setTab(t.id)}
              className={clsx(
                'relative px-3 py-2 text-base transition-colors -mb-px border-b-2',
                tab === t.id
                  ? 'text-ink border-signal font-medium'
                  : 'text-muted border-transparent hover:text-ink'
              )}
            >
              {t.label}
              {t.count !== undefined && (
                <span className="text-tiny text-faint ml-1.5 tnum">{t.count}</span>
              )}
            </button>
          ))}
        </nav>
      </header>

      {tab === 'profile' && (
        <ProfileTab
          user={user}
          orgName={org.name}
          role={yourRole}
          memberCount={org.memberCount}
          projectCount={org.projectCount}
          onSignOut={signOut}
        />
      )}

      {tab === 'team' && (
        <TeamTab
          orgId={org.id}
          orgName={org.name}
          members={members}
          invites={invites ?? []}
          yourUid={user?.uid ?? ''}
          canManage={manage}
          onChanged={() => refresh(['members', 'invites', 'orgs'])}
          onRenamed={() => refresh(['orgs'])}
        />
      )}

      {tab === 'databases' && (
        <DatabasesTab
          orgId={org.id}
          projects={projects ?? []}
          canManage={manage}
          onChanged={() => refresh(['projects', 'orgs'])}
        />
      )}
    </Shell>
  );
}

function Shell({ children }: { children: ReactNode }) {
  return <div className="max-w-[820px] mx-auto px-8 py-8 space-y-7">{children}</div>;
}

/* ---------------------------------------------------------------------------
 * Profile
 * ------------------------------------------------------------------------- */

function ProfileTab({ user, orgName, role, memberCount, projectCount, onSignOut }: {
  user: { email: string | null; displayName: string | null; photoURL: string | null; uid: string } | null;
  orgName: string; role: OrgRole; memberCount: number; projectCount: number;
  onSignOut: () => Promise<void>;
}) {
  return (
    <div className="space-y-6">
      <section className="panel divide-y divide-line">
        <Row label="Name" value={user?.displayName || '—'} />
        <Row label="Email" value={user?.email || '—'} mono />
        <Row label="Team" value={orgName} />
        <Row
          label="Your role"
          value={ROLE_LABEL[role]}
          hint={ROLE_NOTE[role]}
        />
      </section>

      <section className="grid grid-cols-2 gap-3">
        <Figure value={memberCount} label={memberCount === 1 ? 'person on the team' : 'people on the team'} />
        <Figure value={projectCount} label={projectCount === 1 ? 'database watched' : 'databases watched'} />
      </section>

      <div>
        <button onClick={() => onSignOut()} className="btn">
          <LogOut className="w-3.5 h-3.5" />
          Sign out
        </button>
      </div>
    </div>
  );
}

function Row({ label, value, hint, mono }: {
  label: string; value: string; hint?: string; mono?: boolean;
}) {
  return (
    <div className="flex items-baseline gap-4 px-4 py-3">
      <span className="text-tiny text-muted w-24 shrink-0">{label}</span>
      <div className="min-w-0">
        <p className={clsx('text-base text-ink truncate', mono && 'font-mono text-tiny')}>{value}</p>
        {hint && <p className="text-micro text-faint mt-0.5">{hint}</p>}
      </div>
    </div>
  );
}

function Figure({ value, label }: { value: number; label: string }) {
  return (
    <div className="panel px-4 py-3.5">
      <p className="tnum text-lead font-semibold text-ink">{value}</p>
      <p className="text-tiny text-muted mt-0.5">{label}</p>
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * Team
 * ------------------------------------------------------------------------- */

function TeamTab({ orgId, orgName, members, invites, yourUid, canManage: manage, onChanged, onRenamed }: {
  orgId: string; orgName: string; members: Member[]; invites: Invite[]; yourUid: string;
  canManage: boolean; onChanged: () => void; onRenamed: () => void;
}) {
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<OrgRole>('MEMBER');
  const [error, setError] = useState('');
  const [invited, setInvited] = useState('');

  const add = useMutation({
    mutationFn: () => addMember(orgId, email, role),
    onSuccess: (result) => {
      setEmail('');
      setError('');
      // An invite changes nothing visible in the list, so say what happened.
      setInvited(result.status === 'invited'
        ? `${result.email} joins this team the first time they sign in.`
        : '');
      onChanged();
    },
    onError: (e: Error) => { setError(e.message); setInvited(''); },
  });

  const remove = useMutation({
    mutationFn: (uid: string) => removeMember(orgId, uid),
    onSuccess: () => { setError(''); onChanged(); },
    onError: (e: Error) => setError(e.message),
  });

  const setRoleFor = useMutation({
    mutationFn: (v: { uid: string; next: OrgRole }) => changeMemberRole(orgId, v.uid, v.next),
    onSuccess: () => { setError(''); onChanged(); },
    onError: (e: Error) => setError(e.message),
  });

  const withdraw = useMutation({
    mutationFn: (id: string) => cancelInvite(orgId, id),
    onSuccess: () => { setError(''); setInvited(''); onChanged(); },
    onError: (e: Error) => setError(e.message),
  });

  return (
    <div className="space-y-6">
      <section className="space-y-2">
        <h2 className="head">Team name</h2>
        <EditableName
          value={orgName}
          canEdit={manage}
          onSave={async (next) => { await renameOrganization(orgId, next); onRenamed(); }}
        />
      </section>

      <section className="space-y-3">
        <div>
          <h2 className="head">People</h2>
          <p className="sub">Everyone here sees the same queries and analyses.</p>
        </div>

        {manage ? (
          <form
            onSubmit={(e) => { e.preventDefault(); add.mutate(); }}
            className="flex flex-wrap items-center gap-2"
          >
            <input
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="teammate@company.com"
              aria-label="Email address"
              className="field flex-1 min-w-[220px]"
            />
            <select
              value={role}
              onChange={(e) => setRole(e.target.value as OrgRole)}
              aria-label="Role"
              className="field w-auto"
            >
              <option value="MEMBER">Member</option>
              <option value="ADMIN">Admin</option>
            </select>
            <button type="submit" disabled={add.isPending} className="btn-primary py-2">
              {add.isPending ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
              Add
            </button>
          </form>
        ) : (
          <p className="sub">Only an owner or admin can add people.</p>
        )}

        {error && <p role="alert" className="text-tiny text-heat-crit">{error}</p>}
        {invited && (
          <p className="flex items-center gap-2 text-tiny text-heat-cool">
            <MailCheck className="w-3.5 h-3.5 shrink-0" />
            {invited}
          </p>
        )}

        <ul className="panel divide-y divide-line">
          {members.map((m) => (
            <li key={m.uid} className="flex items-center gap-3 px-4 py-3">
              <Avatar user={m} />
              <div className="min-w-0 flex-1">
                <p className="text-base text-ink truncate">
                  {m.displayName || m.email}
                  {m.uid === yourUid && <span className="text-tiny text-faint ml-2">you</span>}
                </p>
                {m.displayName && <p className="text-micro text-faint truncate">{m.email}</p>}
              </div>

              {manage && m.role !== 'OWNER' ? (
                <select
                  value={m.role}
                  onChange={(e) => setRoleFor.mutate({ uid: m.uid, next: e.target.value as OrgRole })}
                  aria-label={`Role for ${m.email}`}
                  className="field w-auto py-1 text-tiny"
                >
                  <option value="MEMBER">Member</option>
                  <option value="ADMIN">Admin</option>
                </select>
              ) : (
                <span className="text-tiny text-muted" title={ROLE_NOTE[m.role]}>
                  {ROLE_LABEL[m.role]}
                </span>
              )}

              {manage && m.role !== 'OWNER' && (
                <button
                  onClick={() => remove.mutate(m.uid)}
                  aria-label={`Remove ${m.email}`}
                  className="p-1.5 rounded-sm text-faint hover:text-heat-crit transition-colors"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              )}
            </li>
          ))}

          {invites.map((i) => (
            <li key={i.id} className="flex items-center gap-3 px-4 py-3">
              <span className="w-7 h-7 rounded-full border border-dashed border-edge text-faint flex items-center justify-center shrink-0">
                <Clock className="w-3.5 h-3.5" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-base text-muted truncate">{i.email}</p>
                <p className="text-micro text-faint">
                  Invited{i.invitedBy ? ` by ${i.invitedBy}` : ''} — joins on first sign-in
                </p>
              </div>
              <span className="text-tiny text-faint">{ROLE_LABEL[i.role]}</span>
              {manage && (
                <button
                  onClick={() => withdraw.mutate(i.id)}
                  aria-label={`Cancel the invite for ${i.email}`}
                  className="p-1.5 rounded-sm text-faint hover:text-heat-crit transition-colors"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

/* ---------------------------------------------------------------------------
 * Databases
 * ------------------------------------------------------------------------- */

function DatabasesTab({ orgId, projects, canManage: manage, onChanged }: {
  orgId: string; projects: Project[]; canManage: boolean; onChanged: () => void;
}) {
  const [name, setName] = useState('');
  const [environment, setEnvironment] = useState<Project['environment']>('production');
  const [error, setError] = useState('');

  const add = useMutation({
    mutationFn: () => createProject(orgId, name, environment),
    onSuccess: () => { setName(''); setError(''); onChanged(); },
    onError: (e: Error) => setError(e.message),
  });

  const rename = useMutation({
    mutationFn: (v: { id: string; next: string }) => updateProject(orgId, v.id, { name: v.next }),
    onSuccess: () => { setError(''); onChanged(); },
    onError: (e: Error) => setError(e.message),
  });

  const remove = useMutation({
    mutationFn: (id: string) => deleteProject(orgId, id),
    onSuccess: () => { setError(''); onChanged(); },
    onError: (e: Error) => setError(e.message),
  });

  return (
    <div className="space-y-3">
      <div>
        <h2 className="head">Databases</h2>
        <p className="sub max-w-[62ch]">
          One entry per database you watch. Connection details stay with the agent running
          inside your network — they are never stored here.
        </p>
      </div>

      {manage && (
        <form
          onSubmit={(e) => { e.preventDefault(); add.mutate(); }}
          className="flex flex-wrap items-center gap-2"
        >
          <input
            required
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Orders API"
            aria-label="Database name"
            className="field flex-1 min-w-[220px]"
          />
          <select
            value={environment}
            onChange={(e) => setEnvironment(e.target.value as Project['environment'])}
            aria-label="Environment"
            className="field w-auto"
          >
            {ENVIRONMENTS.map((env) => <option key={env} value={env}>{env}</option>)}
          </select>
          <button type="submit" disabled={add.isPending} className="btn-primary py-2">
            {add.isPending ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
            Add
          </button>
        </form>
      )}

      {error && <p role="alert" className="text-tiny text-heat-crit">{error}</p>}

      {projects.length === 0 ? (
        <div className="panel px-6 py-12 text-center">
          <p className="text-base text-ink">No databases yet</p>
          <p className="sub mt-1">
            {manage ? 'Add one above to start watching it.' : 'An owner or admin can add one.'}
          </p>
        </div>
      ) : (
        <ul className="panel divide-y divide-line">
          {projects.map((p) => (
            <ProjectRow
              key={p.id}
              project={p}
              canManage={manage}
              onRename={(next) => rename.mutate({ id: p.id, next })}
              onDelete={() => remove.mutate(p.id)}
            />
          ))}
        </ul>
      )}
    </div>
  );
}

function ProjectRow({ project, canManage: manage, onRename, onDelete }: {
  project: Project; canManage: boolean; onRename: (name: string) => void; onDelete: () => void;
}) {
  const [confirming, setConfirming] = useState(false);

  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <EditableName value={project.name} canEdit={manage} compact onSave={async (n) => onRename(n)} />
        <p className="text-micro text-faint mt-0.5">
          Checks every {Math.round(project.pollIntervalMs / 1000)}s · flags queries over{' '}
          {project.slowThresholdMs} ms
        </p>
      </div>

      <span className={clsx('text-micro px-2 py-1 rounded-xs border shrink-0', ENV_TONE[project.environment])}>
        {project.environment}
      </span>

      {manage && (
        confirming ? (
          <div className="flex items-center gap-1 shrink-0">
            <button onClick={onDelete} className="btn py-1 text-heat-crit border-heat-crit/40">Delete</button>
            <button onClick={() => setConfirming(false)} aria-label="Cancel" className="btn py-1">
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        ) : (
          <button
            onClick={() => setConfirming(true)}
            aria-label={`Delete ${project.name}`}
            className="p-1.5 rounded-sm text-faint hover:text-heat-crit transition-colors shrink-0"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        )
      )}
    </li>
  );
}

/* ---------------------------------------------------------------------------
 * Shared
 * ------------------------------------------------------------------------- */

function EditableName({ value, canEdit, compact, onSave }: {
  value: string; canEdit: boolean; compact?: boolean; onSave: (next: string) => Promise<void>;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await onSave(draft.trim());
      setEditing(false);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!editing) {
    return (
      <div className="flex items-center gap-2 min-w-0">
        <span className={clsx('text-ink truncate', compact ? 'text-base' : 'text-mid font-medium')}>
          {value}
        </span>
        {canEdit && (
          <button
            onClick={() => { setDraft(value); setEditing(true); }}
            aria-label={`Rename ${value}`}
            className="p-1 rounded-sm text-faint hover:text-ink transition-colors shrink-0"
          >
            <Pencil className="w-3 h-3" />
          </button>
        )}
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="space-y-1.5">
      <div className="flex items-center gap-1.5">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          autoFocus
          maxLength={80}
          aria-label="Name"
          className="field max-w-xs py-1.5 text-base"
        />
        <button type="submit" disabled={busy} aria-label="Save" className="btn py-1.5">
          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
        </button>
        <button type="button" onClick={() => { setEditing(false); setError(''); }} aria-label="Cancel" className="btn py-1.5">
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
      {error && <p role="alert" className="text-tiny text-heat-crit">{error}</p>}
    </form>
  );
}

function Avatar({ user, size = 'sm' }: {
  user: { photoURL: string | null; email: string | null }; size?: 'sm' | 'lg';
}) {
  const cls = size === 'lg' ? 'w-11 h-11 text-mid' : 'w-7 h-7 text-tiny';
  if (user.photoURL) {
    return <img src={user.photoURL} alt="" className={clsx(cls, 'rounded-full shrink-0 object-cover')} />;
  }
  return (
    <span className={clsx(cls, 'rounded-full bg-raised text-muted flex items-center justify-center shrink-0')}>
      {(user.email ?? '?').charAt(0).toUpperCase()}
    </span>
  );
}
