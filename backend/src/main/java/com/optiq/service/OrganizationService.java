package com.optiq.service;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import com.optiq.config.FirebaseConfig;
import com.optiq.model.*;
import com.optiq.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Organizations, their projects, and who belongs to them.
 *
 * Firestore layout:
 * <pre>
 *   organizations/{orgId}
 *     members/{uid}
 *     projects/{projectId}
 *   users/{uid}/memberships/{orgId}     mirror, so "which orgs am I in?" is one read
 *   invites/{emailKey}                  people invited before they have an account
 * </pre>
 *
 * The membership mirror is denormalized on purpose: a subcollection cannot
 * answer "which organizations is this person in?" without a collection-group
 * query. Every write that touches membership therefore writes both copies in a
 * single batch — if they ever diverge, roles silently disagree.
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    private static final String ORGS = "organizations";
    private static final String MEMBERS = "members";
    private static final String PROJECTS = "projects";
    private static final String USERS = "users";
    private static final String MEMBERSHIPS = "memberships";
    private static final String INVITES = "invites";

    private final FirebaseConfig firebase;

    public OrganizationService(FirebaseConfig firebase) {
        this.firebase = firebase;
    }

    /** Thrown for anything the caller could fix: bad input or insufficient role. */
    public static class OrgException extends RuntimeException {
        private final int status;
        public OrgException(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }

    private Firestore db() {
        return firebase.firestore().orElseThrow(
            () -> new OrgException(503, "Firestore is not configured"));
    }

    // ── Bootstrap ──────────────────────────────────────────────────────────

    /**
     * Make sure a freshly signed-in person lands somewhere sensible: they join
     * any organization that invited them, or get one of their own.
     *
     * Called on every sign-in, so it must be idempotent.
     */
    public List<Organization> bootstrapFor(AuthenticatedUser user) {
        List<Organization> mine = listMine(user.uid());
        if (!mine.isEmpty()) {
            acceptPendingInvites(user);           // may add more
            return listMine(user.uid());
        }

        List<String> joined = acceptPendingInvites(user);
        if (!joined.isEmpty()) {
            return listMine(user.uid());
        }

        createOrganization(defaultOrgName(user), user);
        return listMine(user.uid());
    }

    private static String defaultOrgName(AuthenticatedUser user) {
        if (user.displayName() != null && !user.displayName().isBlank()) {
            return user.displayName().trim() + "'s team";
        }
        String email = user.email();
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@')) + "'s team";
        }
        return "My team";
    }

    // ── Organizations ──────────────────────────────────────────────────────

    public Organization createOrganization(String name, AuthenticatedUser owner) {
        String clean = requireName(name, "Organization name");
        Firestore db = db();
        Instant now = Instant.now();

        DocumentReference orgRef = db.collection(ORGS).document();

        Map<String, Object> org = new LinkedHashMap<>();
        org.put("name", clean);
        org.put("ownerUid", owner.uid());
        org.put("createdAt", now);
        org.put("createdBy", owner.email());

        WriteBatch batch = db.batch();
        batch.set(orgRef, org);
        writeMembership(batch, orgRef.getId(), clean, memberOf(owner, OrgRole.OWNER, now, null));
        commit(batch, "create organization");

        log.info("Created organization '{}' ({}) for {}", clean, orgRef.getId(), owner.email());
        return new Organization(orgRef.getId(), clean, owner.uid(), now, 1, 0);
    }

    /** Organizations this person belongs to, newest first. */
    public List<Organization> listMine(String uid) {
        try {
            var snaps = db().collection(USERS).document(uid).collection(MEMBERSHIPS).get().get();
            List<Organization> orgs = new ArrayList<>();
            for (DocumentSnapshot m : snaps.getDocuments()) {
                DocumentSnapshot org = db().collection(ORGS).document(m.getId()).get().get();
                if (!org.exists()) continue;  // org deleted; stale mirror row
                orgs.add(new Organization(
                    org.getId(),
                    org.getString("name"),
                    org.getString("ownerUid"),
                    instantOf(org.get("createdAt")),
                    countOf(org.getReference().collection(MEMBERS)),
                    countOf(org.getReference().collection(PROJECTS))
                ));
            }
            orgs.sort(Comparator.comparing(
                (Organization o) -> o.createdAt() == null ? Instant.EPOCH : o.createdAt()).reversed());
            return orgs;
        } catch (OrgException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not list organizations for {}: {}", uid, e.getMessage());
            return List.of();
        }
    }

    public void renameOrganization(String orgId, String name, AuthenticatedUser caller) {
        require(requireRole(orgId, caller.uid()).canRenameOrg(), 403, "Only an owner or admin can rename the team");
        String clean = requireName(name, "Organization name");

        Firestore db = db();
        WriteBatch batch = db.batch();
        batch.update(db.collection(ORGS).document(orgId), "name", clean);
        // The mirror carries the name so a sidebar switcher needs one read.
        for (DocumentSnapshot m : membersSnapshot(orgId)) {
            batch.update(db.collection(USERS).document(m.getId())
                .collection(MEMBERSHIPS).document(orgId), "orgName", clean);
        }
        commit(batch, "rename organization");
        log.info("Organization {} renamed to '{}' by {}", orgId, clean, caller.email());
    }

    // ── Members ────────────────────────────────────────────────────────────

    public List<Member> listMembers(String orgId, String callerUid) {
        requireRole(orgId, callerUid);
        List<Member> members = new ArrayList<>();
        for (DocumentSnapshot d : membersSnapshot(orgId)) {
            members.add(new Member(
                d.getId(),
                d.getString("email"),
                d.getString("displayName"),
                d.getString("photoURL"),
                OrgRole.parse(d.getString("role")),
                instantOf(d.get("joinedAt")),
                d.getString("invitedBy")
            ));
        }
        members.sort(Comparator.comparing(m -> m.role().ordinal()));
        return members;
    }

    /**
     * Add someone by email.
     *
     * If they already have a Firebase account they join immediately. If not,
     * the invite is stored against their email and redeemed the first time
     * they sign in — so adding a colleague never depends on them being there
     * first.
     */
    public Map<String, Object> addMember(String orgId, String email, OrgRole role, AuthenticatedUser caller) {
        require(requireRole(orgId, caller.uid()).canManageMembers(), 403,
            "Only an owner or admin can add members");
        require(role != OrgRole.OWNER, 400, "An organization has a single owner");

        String clean = requireEmail(email);
        Instant now = Instant.now();
        String orgName = orgName(orgId);

        UserRecord existing = lookUpByEmail(clean);
        if (existing != null) {
            require(roleOf(orgId, existing.getUid()) == null, 409, clean + " is already on this team");

            Member member = new Member(existing.getUid(), clean, existing.getDisplayName(),
                existing.getPhotoUrl(), role, now, caller.email());

            WriteBatch batch = db().batch();
            writeMembership(batch, orgId, orgName, member);
            commit(batch, "add member");

            log.info("Added {} to organization {} as {}", clean, orgId, role);
            return Map.of("status", "added", "email", clean, "role", role.name(), "uid", existing.getUid());
        }

        // No account yet — leave an invite for them to redeem on first sign-in.
        Map<String, Object> invite = new LinkedHashMap<>();
        invite.put("email", clean);
        invite.put("orgId", orgId);
        invite.put("orgName", orgName);
        invite.put("role", role.name());
        invite.put("invitedBy", caller.email());
        invite.put("invitedAt", now);

        try {
            db().collection(INVITES).document(inviteKey(clean, orgId)).set(invite).get();
        } catch (Exception e) {
            throw new OrgException(500, "Could not save the invite: " + e.getMessage());
        }

        log.info("Invited {} to organization {} as {} (no account yet)", clean, orgId, role);
        return Map.of("status", "invited", "email", clean, "role", role.name());
    }

    public void changeRole(String orgId, String uid, OrgRole role, AuthenticatedUser caller) {
        require(requireRole(orgId, caller.uid()).canManageMembers(), 403,
            "Only an owner or admin can change roles");
        require(role != OrgRole.OWNER, 400, "Ownership cannot be granted this way");
        require(!uid.equals(ownerUid(orgId)), 400, "The owner's role cannot be changed");

        Firestore db = db();
        WriteBatch batch = db.batch();
        batch.update(db.collection(ORGS).document(orgId).collection(MEMBERS).document(uid),
            "role", role.name());
        batch.update(db.collection(USERS).document(uid).collection(MEMBERSHIPS).document(orgId),
            "role", role.name());
        commit(batch, "change role");
    }

    public void removeMember(String orgId, String uid, AuthenticatedUser caller) {
        boolean leavingSelf = uid.equals(caller.uid());
        require(leavingSelf || requireRole(orgId, caller.uid()).canManageMembers(), 403,
            "Only an owner or admin can remove members");
        require(!uid.equals(ownerUid(orgId)), 400,
            "The owner cannot be removed. Transfer ownership first.");

        Firestore db = db();
        WriteBatch batch = db.batch();
        batch.delete(db.collection(ORGS).document(orgId).collection(MEMBERS).document(uid));
        batch.delete(db.collection(USERS).document(uid).collection(MEMBERSHIPS).document(orgId));
        commit(batch, "remove member");

        log.info("Removed {} from organization {}", uid, orgId);
    }

    /**
     * People invited who have not signed in yet.
     *
     * Without this the invite is invisible: adding a colleague who has no
     * account appears to do nothing, because they cannot show up in the member
     * list until they first sign in.
     */
    public List<Map<String, Object>> listInvites(String orgId, String callerUid) {
        requireRole(orgId, callerUid);
        try {
            var snaps = db().collection(INVITES).whereEqualTo("orgId", orgId).get().get();
            List<Map<String, Object>> invites = new ArrayList<>();
            for (DocumentSnapshot d : snaps.getDocuments()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", d.getId());
                row.put("email", d.getString("email"));
                row.put("role", OrgRole.parse(d.getString("role")).name());
                row.put("invitedBy", d.getString("invitedBy"));
                Instant at = instantOf(d.get("invitedAt"));
                row.put("invitedAt", at != null ? at.toString() : null);
                invites.add(row);
            }
            invites.sort(Comparator.comparing(r -> String.valueOf(r.get("email"))));
            return invites;
        } catch (Exception e) {
            log.warn("Could not list invites for {}: {}", orgId, e.getMessage());
            return List.of();
        }
    }

    public void cancelInvite(String orgId, String inviteId, AuthenticatedUser caller) {
        require(requireRole(orgId, caller.uid()).canManageMembers(), 403,
            "Only an owner or admin can cancel invites");
        try {
            var ref = db().collection(INVITES).document(inviteId);
            DocumentSnapshot d = ref.get().get();
            require(d.exists(), 404, "No such invite");
            // Scope the delete to this org so an id from elsewhere cannot be used.
            require(orgId.equals(d.getString("orgId")), 403, "That invite belongs to another team");
            ref.delete().get();
            log.info("Cancelled the invite for {} to organization {}", d.getString("email"), orgId);
        } catch (OrgException e) {
            throw e;
        } catch (Exception e) {
            throw new OrgException(500, "Could not cancel the invite: " + e.getMessage());
        }
    }

    /** Invites waiting for this email; joins them and returns the org ids. */
    private List<String> acceptPendingInvites(AuthenticatedUser user) {
        if (user.email() == null || user.email().isBlank()) return List.of();
        List<String> joined = new ArrayList<>();
        try {
            var pending = db().collection(INVITES)
                .whereEqualTo("email", user.email().toLowerCase(Locale.ROOT)).get().get();

            for (DocumentSnapshot invite : pending.getDocuments()) {
                String orgId = invite.getString("orgId");
                if (orgId == null || roleOf(orgId, user.uid()) != null) {
                    invite.getReference().delete();
                    continue;
                }
                Member member = memberOf(user, OrgRole.parse(invite.getString("role")),
                    Instant.now(), invite.getString("invitedBy"));

                WriteBatch batch = db().batch();
                writeMembership(batch, orgId, invite.getString("orgName"), member);
                batch.delete(invite.getReference());
                commit(batch, "accept invite");

                joined.add(orgId);
                log.info("{} accepted an invite to organization {}", user.email(), orgId);
            }
        } catch (Exception e) {
            log.warn("Could not check invites for {}: {}", user.email(), e.getMessage());
        }
        return joined;
    }

    // ── Projects ───────────────────────────────────────────────────────────

    public List<Project> listProjects(String orgId, String callerUid) {
        requireRole(orgId, callerUid);
        try {
            var snaps = db().collection(ORGS).document(orgId).collection(PROJECTS).get().get();
            List<Project> projects = new ArrayList<>();
            for (DocumentSnapshot d : snaps.getDocuments()) projects.add(toProject(d));
            projects.sort(Comparator.comparing(p -> p.name() == null ? "" : p.name().toLowerCase(Locale.ROOT)));
            return projects;
        } catch (Exception e) {
            log.warn("Could not list projects for {}: {}", orgId, e.getMessage());
            return List.of();
        }
    }

    public Project createProject(String orgId, String name, String environment, AuthenticatedUser caller) {
        require(requireRole(orgId, caller.uid()).canManageProjects(), 403,
            "Only an owner or admin can add projects");
        String clean = requireName(name, "Project name");
        Instant now = Instant.now();

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", clean);
        doc.put("environment", normalizeEnvironment(environment));
        doc.put("enabled", true);
        // Seeded from the current defaults; the agent reads these each cycle.
        doc.put("pollIntervalMs", 300_000L);
        doc.put("slowThresholdMs", 500.0);
        doc.put("maxQueriesPerPoll", 10);
        doc.put("createdAt", now);
        doc.put("createdBy", caller.email());

        try {
            var ref = db().collection(ORGS).document(orgId).collection(PROJECTS).document();
            ref.set(doc).get();
            log.info("Created project '{}' ({}) in organization {}", clean, ref.getId(), orgId);
            return toProject(ref.get().get());
        } catch (Exception e) {
            throw new OrgException(500, "Could not create the project: " + e.getMessage());
        }
    }

    public Project updateProject(String orgId, String projectId, Map<String, Object> changes,
                                 AuthenticatedUser caller) {
        require(requireRole(orgId, caller.uid()).canManageProjects(), 403,
            "Only an owner or admin can change projects");

        Map<String, Object> allowed = new LinkedHashMap<>();
        if (changes.containsKey("name")) {
            allowed.put("name", requireName(String.valueOf(changes.get("name")), "Project name"));
        }
        if (changes.containsKey("environment")) {
            allowed.put("environment", normalizeEnvironment(String.valueOf(changes.get("environment"))));
        }
        if (changes.containsKey("enabled")) {
            allowed.put("enabled", Boolean.parseBoolean(String.valueOf(changes.get("enabled"))));
        }
        copyNumber(changes, allowed, "pollIntervalMs", 1_000L, Long.MAX_VALUE);
        copyNumber(changes, allowed, "slowThresholdMs", 1L, Long.MAX_VALUE);
        copyNumber(changes, allowed, "maxQueriesPerPoll", 1L, 1_000L);
        require(!allowed.isEmpty(), 400, "Nothing to change");

        try {
            var ref = db().collection(ORGS).document(orgId).collection(PROJECTS).document(projectId);
            require(ref.get().get().exists(), 404, "No such project");
            ref.update(allowed).get();
            return toProject(ref.get().get());
        } catch (OrgException e) {
            throw e;
        } catch (Exception e) {
            throw new OrgException(500, "Could not update the project: " + e.getMessage());
        }
    }

    public void deleteProject(String orgId, String projectId, AuthenticatedUser caller) {
        require(requireRole(orgId, caller.uid()).canManageProjects(), 403,
            "Only an owner or admin can delete projects");
        try {
            // Firestore does not cascade: findings under the project would be
            // orphaned and keep costing storage, so clear them first.
            var ref = db().collection(ORGS).document(orgId).collection(PROJECTS).document(projectId);
            deleteSubcollection(ref.collection("queries"));
            ref.delete().get();
            log.info("Deleted project {} from organization {}", projectId, orgId);
        } catch (Exception e) {
            throw new OrgException(500, "Could not delete the project: " + e.getMessage());
        }
    }

    // ── Access ─────────────────────────────────────────────────────────────

    /**
     * The caller's role, or 403 when they are not a member. Use this wherever a
     * permission is being checked — {@link #roleOf} returns null for outsiders,
     * which would otherwise turn a denied request into a 500.
     */
    public OrgRole requireRole(String orgId, String uid) {
        OrgRole role = roleOf(orgId, uid);
        if (role == null) throw new OrgException(403, "You are not a member of this team");
        return role;
    }

    /** The caller's role in an organization, or null when they are not a member. */
    public OrgRole roleOf(String orgId, String uid) {
        try {
            DocumentSnapshot d = db().collection(ORGS).document(orgId)
                .collection(MEMBERS).document(uid).get().get();
            return d.exists() ? OrgRole.parse(d.getString("role")) : null;
        } catch (OrgException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not resolve the role of {} in {}: {}", uid, orgId, e.getMessage());
            return null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Member memberOf(AuthenticatedUser user, OrgRole role, Instant now, String invitedBy) {
        return new Member(user.uid(), user.email(), user.displayName(), user.photoUrl(), role, now, invitedBy);
    }

    /** Writes both the member document and its mirror, so they cannot diverge. */
    private void writeMembership(WriteBatch batch, String orgId, String orgName, Member member) {
        Firestore db = db();

        Map<String, Object> memberDoc = new LinkedHashMap<>();
        memberDoc.put("email", member.email());
        memberDoc.put("displayName", member.displayName());
        memberDoc.put("photoURL", member.photoUrl());
        memberDoc.put("role", member.role().name());
        memberDoc.put("joinedAt", member.joinedAt());
        memberDoc.put("invitedBy", member.invitedBy());

        Map<String, Object> mirror = new LinkedHashMap<>();
        mirror.put("role", member.role().name());
        mirror.put("orgName", orgName);
        mirror.put("joinedAt", member.joinedAt());

        batch.set(db.collection(ORGS).document(orgId).collection(MEMBERS).document(member.uid()), memberDoc);
        batch.set(db.collection(USERS).document(member.uid()).collection(MEMBERSHIPS).document(orgId), mirror);
    }

    private List<QueryDocumentSnapshot> membersSnapshot(String orgId) {
        try {
            return db().collection(ORGS).document(orgId).collection(MEMBERS).get().get().getDocuments();
        } catch (Exception e) {
            throw new OrgException(500, "Could not read the member list: " + e.getMessage());
        }
    }

    private String orgName(String orgId) {
        try {
            DocumentSnapshot d = db().collection(ORGS).document(orgId).get().get();
            require(d.exists(), 404, "No such organization");
            return d.getString("name");
        } catch (OrgException e) {
            throw e;
        } catch (Exception e) {
            throw new OrgException(500, "Could not read the organization: " + e.getMessage());
        }
    }

    private String ownerUid(String orgId) {
        try {
            return db().collection(ORGS).document(orgId).get().get().getString("ownerUid");
        } catch (Exception e) {
            return null;
        }
    }

    private UserRecord lookUpByEmail(String email) {
        try {
            return FirebaseAuth.getInstance().getUserByEmail(email);
        } catch (Exception e) {
            return null;   // No account yet — a normal case, handled by the caller.
        }
    }

    private void deleteSubcollection(CollectionReference ref) throws Exception {
        while (true) {
            var page = ref.limit(300).get().get().getDocuments();
            if (page.isEmpty()) return;
            WriteBatch batch = db().batch();
            page.forEach(d -> batch.delete(d.getReference()));
            batch.commit().get();
            if (page.size() < 300) return;
        }
    }

    private void commit(WriteBatch batch, String what) {
        try {
            batch.commit().get();
        } catch (Exception e) {
            throw new OrgException(500, "Could not " + what + ": " + e.getMessage());
        }
    }

    private int countOf(CollectionReference ref) {
        try {
            return (int) ref.count().get().get().getCount();
        } catch (Exception e) {
            return 0;
        }
    }

    private Project toProject(DocumentSnapshot d) {
        return new Project(
            d.getId(),
            d.getString("name"),
            d.getString("environment"),
            !Boolean.FALSE.equals(d.getBoolean("enabled")),
            longOf(d.get("pollIntervalMs"), 300_000L),
            doubleOf(d.get("slowThresholdMs"), 500.0),
            (int) longOf(d.get("maxQueriesPerPoll"), 10L),
            instantOf(d.get("createdAt")),
            d.getString("createdBy")
        );
    }

    private static void copyNumber(Map<String, Object> from, Map<String, Object> to,
                                   String key, long min, long max) {
        if (!from.containsKey(key)) return;
        try {
            double value = Double.parseDouble(String.valueOf(from.get(key)));
            require(value >= min && value <= max, 400,
                key + " must be between " + min + " and " + max);
            to.put(key, value);
        } catch (NumberFormatException e) {
            throw new OrgException(400, key + " must be a number");
        }
    }

    private static String normalizeEnvironment(String raw) {
        if (raw == null || raw.isBlank()) return Project.ENV_PRODUCTION;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case Project.ENV_PRODUCTION, Project.ENV_STAGING, Project.ENV_DEVELOPMENT -> value;
            default -> Project.ENV_PRODUCTION;
        };
    }

    private static String requireName(String raw, String what) {
        String clean = raw == null ? "" : raw.trim();
        require(!clean.isEmpty(), 400, what + " cannot be empty");
        require(clean.length() <= 80, 400, what + " must be 80 characters or fewer");
        return clean;
    }

    private static String requireEmail(String raw) {
        String clean = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        require(clean.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"), 400, "That email address is not valid");
        return clean;
    }

    private static String inviteKey(String email, String orgId) {
        return Integer.toHexString((email + "|" + orgId).hashCode()) + "_"
            + email.replaceAll("[^a-zA-Z0-9]", "_");
    }

    private static void require(boolean condition, int status, String message) {
        if (!condition) throw new OrgException(status, message);
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Timestamp ts) return ts.toDate().toInstant();
        if (value instanceof Date d) return d.toInstant();
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        return null;
    }

    private static long longOf(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static double doubleOf(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }
}
