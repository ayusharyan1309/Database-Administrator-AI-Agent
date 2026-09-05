package com.optiq.security;

/** The verified caller behind the current request. */
public record AuthenticatedUser(
    String uid,
    String email,
    String displayName,
    String photoUrl,
    boolean emailVerified
) {}
