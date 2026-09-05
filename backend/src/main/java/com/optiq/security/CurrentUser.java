package com.optiq.security;

import java.util.Optional;

/**
 * The caller for the request being handled on this thread.
 *
 * Set by {@link FirebaseAuthFilter} and cleared in its finally block. Empty
 * when Firebase is not configured, which is a supported state — callers must
 * handle an absent user rather than assuming one.
 */
public final class CurrentUser {

    private static final ThreadLocal<AuthenticatedUser> HOLDER = new ThreadLocal<>();

    private CurrentUser() {}

    static void set(AuthenticatedUser user) {
        HOLDER.set(user);
    }

    static void clear() {
        HOLDER.remove();
    }

    public static Optional<AuthenticatedUser> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    /** The signed-in uid, or "system" for daemon work that has no request. */
    public static String uidOrSystem() {
        AuthenticatedUser user = HOLDER.get();
        return user != null ? user.uid() : "system";
    }
}
