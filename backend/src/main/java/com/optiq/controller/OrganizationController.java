package com.optiq.controller;

import com.optiq.model.*;
import com.optiq.security.AuthenticatedUser;
import com.optiq.security.CurrentUser;
import com.optiq.service.OrganizationService;
import com.optiq.service.OrganizationService.OrgException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Teams, their people, and their projects.
 *
 * Every route requires a signed-in caller; {@code FirebaseAuthFilter} has
 * already rejected anyone without a valid token by the time a method runs.
 */
@RestController
@RequestMapping("/api/orgs")
public class OrganizationController {

    private final OrganizationService orgs;

    public OrganizationController(OrganizationService orgs) {
        this.orgs = orgs;
    }

    // ── Organizations ──────────────────────────────────────────────────────

    /** Teams the caller belongs to. Creates one on first use. */
    @GetMapping
    public ResponseEntity<?> mine() {
        AuthenticatedUser me = caller();
        List<Organization> mine = orgs.bootstrapFor(me);
        return ResponseEntity.ok(Map.of(
            "organizations", mine.stream().map(o -> describe(o, me)).toList()
        ));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        AuthenticatedUser me = caller();
        Organization org = orgs.createOrganization(body.get("name"), me);
        return ResponseEntity.ok(describe(org, me));
    }

    @PatchMapping("/{orgId}")
    public ResponseEntity<?> rename(@PathVariable String orgId, @RequestBody Map<String, String> body) {
        orgs.renameOrganization(orgId, body.get("name"), caller());
        return ResponseEntity.ok(Map.of("status", "renamed", "name", body.get("name")));
    }

    // ── Members ────────────────────────────────────────────────────────────

    @GetMapping("/{orgId}/members")
    public ResponseEntity<?> members(@PathVariable String orgId) {
        AuthenticatedUser me = caller();
        return ResponseEntity.ok(Map.of(
            "members", orgs.listMembers(orgId, me.uid()).stream().map(OrganizationController::describe).toList(),
            "yourRole", orgs.requireRole(orgId, me.uid()).name()
        ));
    }

    /** Add by email. Joins immediately if they have an account, else invites. */
    @PostMapping("/{orgId}/members")
    public ResponseEntity<?> addMember(@PathVariable String orgId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(orgs.addMember(
            orgId, body.get("email"), OrgRole.parse(body.get("role")), caller()));
    }

    @PatchMapping("/{orgId}/members/{uid}")
    public ResponseEntity<?> changeRole(@PathVariable String orgId, @PathVariable String uid,
                                        @RequestBody Map<String, String> body) {
        orgs.changeRole(orgId, uid, OrgRole.parse(body.get("role")), caller());
        return ResponseEntity.ok(Map.of("status", "updated"));
    }

    @DeleteMapping("/{orgId}/members/{uid}")
    public ResponseEntity<?> removeMember(@PathVariable String orgId, @PathVariable String uid) {
        orgs.removeMember(orgId, uid, caller());
        return ResponseEntity.ok(Map.of("status", "removed"));
    }

    /** People added who have not signed in yet. */
    @GetMapping("/{orgId}/invites")
    public ResponseEntity<?> invites(@PathVariable String orgId) {
        return ResponseEntity.ok(Map.of("invites", orgs.listInvites(orgId, caller().uid())));
    }

    @DeleteMapping("/{orgId}/invites/{inviteId}")
    public ResponseEntity<?> cancelInvite(@PathVariable String orgId, @PathVariable String inviteId) {
        orgs.cancelInvite(orgId, inviteId, caller());
        return ResponseEntity.ok(Map.of("status", "cancelled"));
    }

    // ── Projects ───────────────────────────────────────────────────────────

    @GetMapping("/{orgId}/projects")
    public ResponseEntity<?> projects(@PathVariable String orgId) {
        AuthenticatedUser me = caller();
        return ResponseEntity.ok(Map.of(
            "projects", orgs.listProjects(orgId, me.uid()).stream().map(OrganizationController::describe).toList()
        ));
    }

    @PostMapping("/{orgId}/projects")
    public ResponseEntity<?> createProject(@PathVariable String orgId, @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(describe(
            orgs.createProject(orgId, body.get("name"), body.get("environment"), caller())));
    }

    @PatchMapping("/{orgId}/projects/{projectId}")
    public ResponseEntity<?> updateProject(@PathVariable String orgId, @PathVariable String projectId,
                                           @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(describe(orgs.updateProject(orgId, projectId, body, caller())));
    }

    @DeleteMapping("/{orgId}/projects/{projectId}")
    public ResponseEntity<?> deleteProject(@PathVariable String orgId, @PathVariable String projectId) {
        orgs.deleteProject(orgId, projectId, caller());
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }

    // ── Errors ─────────────────────────────────────────────────────────────

    /**
     * Anything the caller could fix comes back with its own status and a
     * message written for a person, not a stack trace.
     */
    @ExceptionHandler(OrgException.class)
    public ResponseEntity<?> handle(OrgException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    // ── Shaping ────────────────────────────────────────────────────────────

    private static AuthenticatedUser caller() {
        return CurrentUser.get().orElseThrow(() -> new OrgException(401, "Sign in to use this API"));
    }

    private Map<String, Object> describe(Organization org, AuthenticatedUser me) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", org.id());
        body.put("name", org.name());
        body.put("isOwner", me.uid().equals(org.ownerUid()));
        body.put("yourRole", String.valueOf(orgs.roleOf(org.id(), me.uid())));
        body.put("memberCount", org.memberCount());
        body.put("projectCount", org.projectCount());
        body.put("createdAt", org.createdAt() != null ? org.createdAt().toString() : null);
        return body;
    }

    private static Map<String, Object> describe(Member m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uid", m.uid());
        body.put("email", m.email());
        body.put("displayName", m.displayName());
        body.put("photoURL", m.photoUrl());
        body.put("role", m.role().name());
        body.put("joinedAt", m.joinedAt() != null ? m.joinedAt().toString() : null);
        body.put("invitedBy", m.invitedBy());
        return body;
    }

    private static Map<String, Object> describe(Project p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("name", p.name());
        body.put("environment", p.environment());
        body.put("enabled", p.enabled());
        body.put("pollIntervalMs", p.pollIntervalMs());
        body.put("slowThresholdMs", p.slowThresholdMs());
        body.put("maxQueriesPerPoll", p.maxQueriesPerPoll());
        body.put("createdAt", p.createdAt() != null ? p.createdAt().toString() : null);
        body.put("createdBy", p.createdBy());
        return body;
    }
}
