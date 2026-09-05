package com.optiq.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Brings up Firestore when credentials are available.
 *
 * Deliberately optional. OptiQuery must keep polling, analyzing and serving the
 * dashboard on an installation that has never seen a Firebase project — so a
 * missing or broken credential is logged once and the app carries on with
 * Firestore-backed features disabled, rather than refusing to start.
 *
 * Credentials are resolved in this order:
 *   1. optiq.firebase.credentials-file  (or OPTIQ_FIREBASE_CREDENTIALS)
 *   2. GOOGLE_APPLICATION_CREDENTIALS   (the Google standard)
 *   3. Application Default Credentials  (set automatically on GCP)
 */
@Component
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    @Value("${optiq.firebase.credentials-file:${OPTIQ_FIREBASE_CREDENTIALS:}}")
    private String credentialsFile;

    @Value("${optiq.firebase.project-id:${OPTIQ_FIREBASE_PROJECT_ID:}}")
    private String projectId;

    private volatile Firestore firestore;
    private volatile String unavailableReason;

    @PostConstruct
    void init() {
        try {
            GoogleCredentials credentials = resolveCredentials();
            if (credentials == null) {
                unavailableReason = "no Firebase credentials configured";
                log.info("Firestore disabled — {}. Set optiq.firebase.credentials-file "
                    + "to the service account JSON to enable hosted trials.", unavailableReason);
                return;
            }

            FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(credentials);
            if (projectId != null && !projectId.isBlank()) {
                options.setProjectId(projectId.trim());
            }

            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(options.build())
                : FirebaseApp.getInstance();

            firestore = FirestoreClient.getFirestore(app);
            log.info("Firestore connected (project: {})", app.getOptions().getProjectId());

        } catch (Exception e) {
            unavailableReason = e.getMessage();
            log.warn("Firestore unavailable — continuing without it: {}", e.getMessage());
        }
    }

    private GoogleCredentials resolveCredentials() throws Exception {
        if (credentialsFile != null && !credentialsFile.isBlank()) {
            Path path = locate(credentialsFile.trim());
            log.info("Loading Firebase credentials from {}", path);
            try (InputStream in = new FileInputStream(path.toFile())) {
                return GoogleCredentials.fromStream(in);
            }
        }
        try {
            return GoogleCredentials.getApplicationDefault();
        } catch (Exception e) {
            return null; // Nothing configured — a normal state, not an error.
        }
    }

    /**
     * Find the service account file whether the app was started from the repo
     * root or from backend/ — the two ways it actually gets run. An absolute
     * path is used as given.
     */
    private static Path locate(String configured) {
        Path given = Path.of(configured);
        if (given.isAbsolute()) {
            if (Files.isReadable(given)) return given;
            throw new IllegalStateException("credentials file not readable: " + given);
        }

        Path cwd = Path.of("").toAbsolutePath();
        List<Path> candidates = List.of(
            cwd.resolve(given),                        // as given
            cwd.getParent() == null ? cwd : cwd.getParent().resolve(given),  // from backend/, path written from root
            cwd.resolve("backend").resolve(given),     // from root, path written from backend/
            cwd.resolve(given.getFileName())           // same directory, name only
        );

        for (Path candidate : candidates) {
            if (candidate != null && Files.isReadable(candidate)) return candidate.normalize();
        }
        throw new IllegalStateException(
            "credentials file not found. Looked for '" + configured + "' relative to " + cwd);
    }

    /** Empty when Firestore is not configured; callers must degrade, not fail. */
    public Optional<Firestore> firestore() {
        return Optional.ofNullable(firestore);
    }

    public boolean isAvailable() {
        return firestore != null;
    }

    public String unavailableReason() {
        return unavailableReason == null ? "" : unavailableReason;
    }
}
