package com.optiq.model;

import java.time.Instant;

/** A team that uses OptiQuery. Owns projects and members. */
public record Organization(
    String id,
    String name,
    String ownerUid,
    Instant createdAt,
    int memberCount,
    int projectCount
) {}
