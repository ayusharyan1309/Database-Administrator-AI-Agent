package com.optiq.service;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.optiq.config.FirebaseConfig;
import com.optiq.model.Organization;
import com.optiq.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Who did what to a slow query.
 *
 * A team sharing a database needs to know whether a colleague already ran the
 * index before they run it too, and whether anyone acted at all. Without this
 * the dashboard can say a fix is ready but never that it was taken.
 *
 * Stored in Firestore at {@code organizations/{orgId}/activity/{eventId}} —
 * alongside the team it belongs to, rather than in the monitored database.
 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private static final String ORGS = "organizations";
    private static final String ACTIVITY = "activity";

    /** Reading a long trail is pointless; the last few tell the story. */
    private static final int MAX_PER_QUERY = 50;

    private final FirebaseConfig firebase;
    private final OrganizationService orgs;

    public ActivityService(FirebaseConfig firebase, OrganizationService orgs) {
        this.firebase = firebase;
        this.orgs = orgs;
    }

    /** What somebody did. Named for what a person would say, not the enum. */
    public enum Action {
        APPLIED("applied the fix"),
        DISMISSED("dismissed this query"),
        REOPENED("reopened this query"),
        REANALYZED("ran the analysis again");

        private final String phrase;
        Action(String phrase) { this.phrase = phrase; }
        public String phrase() { return phrase; }
    }

    public record Event(
        String id, long queryId, Action action, String actorUid,
        String actorEmail, String actorName, String note, Instant at
    ) {}

    /**
     * The team this caller acts on behalf of.
     *
     * Queries are still single-tenant, so there is one org per person today.
     * Resolving it here rather than hardcoding means the call sites do not
     * change when projects own their own queries.
     */
    public Optional<String> orgIdFor(AuthenticatedUser user) {
        if (user == null || !firebase.isAvailable()) return Optional.empty();
        List<Organization> mine = orgs.listMine(user.uid());
        return mine.isEmpty() ? Optional.empty() : Optional.of(mine.get(0).id());
    }

    // ── Recording ──────────────────────────────────────────────────────────

    /**
     * Record an action. Never throws: an audit write failing must not undo the
     * action the user actually asked for.
     */
    public void record(AuthenticatedUser actor, long queryId, Action action, String note) {
        if (actor == null) return;                 // Daemon work has no actor.
        Optional<Firestore> db = firebase.firestore();
        Optional<String> orgId = orgIdFor(actor);
        if (db.isEmpty() || orgId.isEmpty()) return;

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("queryId", queryId);
        event.put("action", action.name());
        event.put("actorUid", actor.uid());
        event.put("actorEmail", actor.email());
        event.put("actorName", actor.displayName());
        event.put("note", note == null || note.isBlank() ? null : note.trim());
        event.put("at", Instant.now());

        try {
            db.get().collection(ORGS).document(orgId.get())
                .collection(ACTIVITY).document().set(event).get();
            log.info("{} {} for query #{}", actor.email(), action.phrase(), queryId);
        } catch (Exception e) {
            log.warn("Could not record activity for query #{}: {}", queryId, e.getMessage());
        }
    }

    // ── Reading ────────────────────────────────────────────────────────────

    /** Everything done to one query, newest first. */
    public List<Event> forQuery(AuthenticatedUser caller, long queryId) {
        Optional<Firestore> db = firebase.firestore();
        Optional<String> orgId = orgIdFor(caller);
        if (db.isEmpty() || orgId.isEmpty()) return List.of();

        try {
            var snaps = db.get().collection(ORGS).document(orgId.get())
                .collection(ACTIVITY)
                .whereEqualTo("queryId", queryId)
                .limit(MAX_PER_QUERY)
                .get().get();

            List<Event> events = new ArrayList<>();
            for (DocumentSnapshot d : snaps.getDocuments()) events.add(toEvent(d));
            // Sorted here rather than in the query so no composite index is
            // needed for what is always a short list.
            events.sort(Comparator.comparing(
                (Event e) -> e.at() == null ? Instant.EPOCH : e.at()).reversed());
            return events;
        } catch (Exception e) {
            log.warn("Could not read activity for query #{}: {}", queryId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Query ids the team has already acted on, so the dashboard can mark the
     * rest as untouched in one read instead of one per row.
     */
    public Map<Long, Event> latestByQuery(AuthenticatedUser caller) {
        Optional<Firestore> db = firebase.firestore();
        Optional<String> orgId = orgIdFor(caller);
        if (db.isEmpty() || orgId.isEmpty()) return Map.of();

        try {
            var snaps = db.get().collection(ORGS).document(orgId.get())
                .collection(ACTIVITY).limit(500).get().get();

            Map<Long, Event> latest = new HashMap<>();
            for (DocumentSnapshot d : snaps.getDocuments()) {
                Event e = toEvent(d);
                Event existing = latest.get(e.queryId());
                if (existing == null || after(e.at(), existing.at())) {
                    latest.put(e.queryId(), e);
                }
            }
            return latest;
        } catch (Exception e) {
            log.warn("Could not read team activity: {}", e.getMessage());
            return Map.of();
        }
    }

    private static boolean after(Instant a, Instant b) {
        if (a == null) return false;
        if (b == null) return true;
        return a.isAfter(b);
    }

    private static Event toEvent(DocumentSnapshot d) {
        return new Event(
            d.getId(),
            d.get("queryId") instanceof Number n ? n.longValue() : 0L,
            parseAction(d.getString("action")),
            d.getString("actorUid"),
            d.getString("actorEmail"),
            d.getString("actorName"),
            d.getString("note"),
            instantOf(d.get("at"))
        );
    }

    private static Action parseAction(String raw) {
        if (raw == null) return Action.REANALYZED;
        try {
            return Action.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Action.REANALYZED;
        }
    }

    private static Instant instantOf(Object value) {
        if (value instanceof Timestamp ts) return ts.toDate().toInstant();
        if (value instanceof Date d) return d.toInstant();
        if (value instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        return null;
    }
}
