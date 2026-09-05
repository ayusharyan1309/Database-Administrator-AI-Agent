package com.optiq.model;

import java.time.Instant;

/** Someone with access to an organization. Document id is the Firebase uid. */
public record Member(
    String uid,
    String email,
    String displayName,
    String photoUrl,
    OrgRole role,
    Instant joinedAt,
    String invitedBy
) {}
