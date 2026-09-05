package com.optiq.controller;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import com.optiq.config.FirebaseConfig;
import com.optiq.security.AuthenticatedUser;
import com.optiq.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Who the caller is, and their record in Firestore.
 *
 * The client calls {@code /api/auth/session} once after signing in. That write
 * is what creates {@code users/{uid}} — the document the organization model
 * will hang memberships off, so it has to exist before any of that lands.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String USERS = "users";

    private final FirebaseConfig firebase;

    public AuthController(FirebaseConfig firebase) {
        this.firebase = firebase;
    }

    /** The current caller, or 401. Cheap — it reads the verified token only. */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Optional<AuthenticatedUser> user = CurrentUser.get();
        if (user.isEmpty()) {
            return authDisabled();
        }
        return ResponseEntity.ok(describe(user.get()));
    }

    /**
     * Records the sign-in. Safe to call repeatedly: the profile fields are
     * merged and only {@code lastSeenAt} moves, so nothing already stored
     * about the user is lost.
     */
    @PostMapping("/session")
    public ResponseEntity<?> startSession() {
        Optional<AuthenticatedUser> caller = CurrentUser.get();
        if (caller.isEmpty()) {
            return authDisabled();
        }
        AuthenticatedUser user = caller.get();

        Optional<Firestore> db = firebase.firestore();
        if (db.isEmpty()) {
            return ResponseEntity.ok(describe(user));
        }

        Instant now = Instant.now();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("email", user.email());
        profile.put("displayName", user.displayName());
        profile.put("photoURL", user.photoUrl());
        profile.put("emailVerified", user.emailVerified());
        profile.put("lastSeenAt", now);

        try {
            var ref = db.get().collection(USERS).document(user.uid());
            boolean isNew = !ref.get().get().exists();
            if (isNew) {
                profile.put("createdAt", now);
                log.info("First sign-in recorded for {}", user.email());
            }
            ref.set(profile, SetOptions.merge()).get();
        } catch (Exception e) {
            // Signing in must not fail because a bookkeeping write did.
            log.warn("Could not record the sign-in for {}: {}", user.uid(), e.getMessage());
        }

        return ResponseEntity.ok(describe(user));
    }

    private ResponseEntity<?> authDisabled() {
        // No verified caller: either Firebase is not configured, or the filter
        // would already have returned 401 before reaching here.
        return firebase.isAvailable()
            ? ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not signed in"))
            : ResponseEntity.ok(Map.of("authEnabled", false));
    }

    private static Map<String, Object> describe(AuthenticatedUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authEnabled", true);
        body.put("uid", user.uid());
        body.put("email", user.email());
        body.put("displayName", user.displayName());
        body.put("photoURL", user.photoUrl());
        body.put("emailVerified", user.emailVerified());
        return body;
    }
}
