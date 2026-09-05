/** Teams, their people, and their projects. */
import { apiFetch } from '../api';

export type OrgRole = 'OWNER' | 'ADMIN' | 'MEMBER';

export interface Organization {
  id: string;
  name: string;
  isOwner: boolean;
  yourRole: OrgRole;
  memberCount: number;
  projectCount: number;
  createdAt: string | null;
}

export interface Member {
  uid: string;
  email: string | null;
  displayName: string | null;
  photoURL: string | null;
  role: OrgRole;
  joinedAt: string | null;
  invitedBy: string | null;
}

export interface Project {
  id: string;
  name: string;
  environment: 'production' | 'staging' | 'development';
  enabled: boolean;
  pollIntervalMs: number;
  slowThresholdMs: number;
  maxQueriesPerPoll: number;
  createdAt: string | null;
  createdBy: string | null;
}

/** Server errors carry a message written for a person — surface it as-is. */
async function unwrap<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `Request failed (${res.status})`);
  return body as T;
}

const json = { 'Content-Type': 'application/json' };

// --- Organizations ---

export async function fetchOrganizations(): Promise<Organization[]> {
  const body = await unwrap<{ organizations: Organization[] }>(await apiFetch('/orgs'));
  return body.organizations;
}

export async function createOrganization(name: string): Promise<Organization> {
  return unwrap(await apiFetch('/orgs', { method: 'POST', headers: json, body: JSON.stringify({ name }) }));
}

export async function renameOrganization(orgId: string, name: string): Promise<void> {
  await unwrap(await apiFetch(`/orgs/${orgId}`, {
    method: 'PATCH', headers: json, body: JSON.stringify({ name }),
  }));
}

// --- Members ---

export async function fetchMembers(orgId: string): Promise<{ members: Member[]; yourRole: OrgRole }> {
  return unwrap(await apiFetch(`/orgs/${orgId}/members`));
}

/** Joins immediately if they have an account, otherwise leaves an invite. */
export async function addMember(
  orgId: string, email: string, role: OrgRole
): Promise<{ status: 'added' | 'invited'; email: string }> {
  return unwrap(await apiFetch(`/orgs/${orgId}/members`, {
    method: 'POST', headers: json, body: JSON.stringify({ email, role }),
  }));
}

export async function changeMemberRole(orgId: string, uid: string, role: OrgRole): Promise<void> {
  await unwrap(await apiFetch(`/orgs/${orgId}/members/${uid}`, {
    method: 'PATCH', headers: json, body: JSON.stringify({ role }),
  }));
}

export async function removeMember(orgId: string, uid: string): Promise<void> {
  await unwrap(await apiFetch(`/orgs/${orgId}/members/${uid}`, { method: 'DELETE' }));
}

export interface Invite {
  id: string;
  email: string;
  role: OrgRole;
  invitedBy: string | null;
  invitedAt: string | null;
}

/** People added who have not signed in yet, so are not members yet. */
export async function fetchInvites(orgId: string): Promise<Invite[]> {
  const body = await unwrap<{ invites: Invite[] }>(await apiFetch(`/orgs/${orgId}/invites`));
  return body.invites;
}

export async function cancelInvite(orgId: string, inviteId: string): Promise<void> {
  await unwrap(await apiFetch(`/orgs/${orgId}/invites/${inviteId}`, { method: 'DELETE' }));
}

// --- Projects ---

export async function fetchProjects(orgId: string): Promise<Project[]> {
  const body = await unwrap<{ projects: Project[] }>(await apiFetch(`/orgs/${orgId}/projects`));
  return body.projects;
}

export async function createProject(
  orgId: string, name: string, environment: Project['environment']
): Promise<Project> {
  return unwrap(await apiFetch(`/orgs/${orgId}/projects`, {
    method: 'POST', headers: json, body: JSON.stringify({ name, environment }),
  }));
}

export async function updateProject(
  orgId: string, projectId: string, changes: Partial<Project>
): Promise<Project> {
  return unwrap(await apiFetch(`/orgs/${orgId}/projects/${projectId}`, {
    method: 'PATCH', headers: json, body: JSON.stringify(changes),
  }));
}

export async function deleteProject(orgId: string, projectId: string): Promise<void> {
  await unwrap(await apiFetch(`/orgs/${orgId}/projects/${projectId}`, { method: 'DELETE' }));
}

// --- Presentation ---

export const ROLE_LABEL: Record<OrgRole, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
};

export const ROLE_NOTE: Record<OrgRole, string> = {
  OWNER: 'Created the team. Cannot be removed.',
  ADMIN: 'Can add people and manage projects.',
  MEMBER: 'Can read queries and analyses.',
};

export function canManage(role: OrgRole | undefined): boolean {
  return role === 'OWNER' || role === 'ADMIN';
}
