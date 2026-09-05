package com.optiq.security;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.optiq.config.FirebaseConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Verifies the Firebase ID token on API requests and publishes the caller.
 *
 * Enforcement is conditional on Firebase being configured. An installation with
 * no Firebase project keeps working exactly as it did before authentication
 * existed — which is what lets this ship without a flag day, and what keeps
 * local development from needing a cloud project.
 *
 * The token is verified against Google's public keys on every request; the
 * result is cached by the SDK, so this is not a network round trip per call.
 */
@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthFilter.class);

    private static final String BEARER = "Bearer ";

    /**
     * Paths that must stay reachable without a user token.
     *   /api/health — the dashboard reads it to render the "daemon unreachable"
     *                 state, which has to work when nobody is signed in.
     *   /api/admin  — vendor-side, guarded by its own admin token instead.
     */
    private static final Set<String> OPEN_PREFIXES = Set.of("/api/health", "/api/admin");

    private final FirebaseConfig firebase;

    public FirebaseAuthFilter(FirebaseConfig firebase) {
        this.firebase = firebase;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        return OPEN_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // No Firebase project: run open, as before.
        if (!firebase.isAvailable()) {
            chain.doFilter(request, response);
            return;
        }

        // CORS preflight carries no Authorization header by design.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, "Sign in to use this API");
            return;
        }

        try {
            FirebaseToken token = FirebaseAuth.getInstance().verifyIdToken(header.substring(BEARER.length()).trim());
            CurrentUser.set(new AuthenticatedUser(
                token.getUid(),
                token.getEmail(),
                token.getName(),
                token.getPicture(),
                token.isEmailVerified()
            ));
            chain.doFilter(request, response);

        } catch (FirebaseAuthException e) {
            log.debug("Rejected an invalid ID token: {}", e.getMessage());
            reject(response, "Your session has expired. Sign in again.");
        } finally {
            // The thread returns to the pool; never leak the caller onto the next request.
            CurrentUser.clear();
        }
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
