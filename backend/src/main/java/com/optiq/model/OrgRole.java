package com.optiq.model;

/** What a person may do inside an organization. */
public enum OrgRole {
    /** Created the organization. Cannot be removed or demoted by anyone else. */
    OWNER,
    /** Manages projects and members, but cannot remove the owner. */
    ADMIN,
    /** Reads queries and analyses; cannot change the team or its projects. */
    MEMBER;

    public static OrgRole parse(String raw) {
        if (raw == null || raw.isBlank()) return MEMBER;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEMBER;
        }
    }

    public boolean canManageMembers() {
        return this == OWNER || this == ADMIN;
    }

    public boolean canManageProjects() {
        return this == OWNER || this == ADMIN;
    }

    public boolean canRenameOrg() {
        return this == OWNER || this == ADMIN;
    }
}
