package com.optiq.controller;

import com.optiq.dto.SlowQueriesResponse;
import com.optiq.dto.SlowQueryDto;
import com.optiq.model.AnalysisStatus;
import com.optiq.model.SlowQuery;
import com.optiq.security.AuthenticatedUser;
import com.optiq.security.CurrentUser;
import com.optiq.service.ActivityService;
import com.optiq.repository.SlowQueryRepository;
import com.optiq.scheduler.SlowQueryPoller;
import com.optiq.service.AnalysisOrchestrator;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for slow query management.
 *
 * Endpoints:
 *   GET  /api/queries/slow              — List slow queries (paginated, sortable)
 *   GET  /api/queries/slow/{id}         — Get slow query details with analysis
 *   POST /api/queries/{id}/dismiss      — Dismiss a slow query
 *   POST /api/queries/{id}/reanalyze    — Re-trigger AI analysis
 *   GET  /api/queries/stats             — Overview stats
 */
@RestController
@RequestMapping("/api/queries")
@CrossOrigin(origins = "*")  // TODO: restrict to frontend origin in production
public class SlowQueryController {

    private final SlowQueryRepository queryRepository;
    private final AnalysisOrchestrator orchestrator;
    private final SlowQueryPoller poller;

    private final ActivityService activity;

    public SlowQueryController(SlowQueryRepository queryRepository, AnalysisOrchestrator orchestrator,
                               SlowQueryPoller poller, ActivityService activity) {
        this.activity = activity;
        this.queryRepository = queryRepository;
        this.orchestrator = orchestrator;
        this.poller = poller;
    }

    /**
     * GET /api/queries/slow
     * List slow queries, ranked by total time burned (descending) by default.
     * Pass sort=meanTime to rank by mean execution time instead.
     * Supports optional status filter.
     */
    @GetMapping("/slow")
    public ResponseEntity<SlowQueriesResponse> listSlowQueries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) AnalysisStatus status,
            @RequestParam(defaultValue = "totalTime") String sort) {

        PageRequest pageRequest = PageRequest.of(page, size);
        boolean byMeanTime = "meanTime".equalsIgnoreCase(sort);
        Page<SlowQuery> results;

        if (status != null) {
            results = byMeanTime
                ? queryRepository.findAllByStatusOrderByMeanExecTimeMsDesc(status, pageRequest)
                : queryRepository.findAllByStatusOrderByTotalExecTimeMsDesc(status, pageRequest);
        } else {
            results = byMeanTime
                ? queryRepository.findAllByOrderByMeanExecTimeMsDesc(pageRequest)
                : queryRepository.findAllByOrderByTotalExecTimeMsDesc(pageRequest);
        }

        var dtos = results.getContent().stream()
            .map(SlowQueryDto::from)
            .toList();

        return ResponseEntity.ok(new SlowQueriesResponse(
            dtos, results.getTotalElements(), page, size));
    }

    /**
     * GET /api/queries/slow/{id}
     * Get detailed slow query with its AI analysis.
     */
    @GetMapping("/slow/{id}")
    public ResponseEntity<SlowQueryDto> getSlowQuery(@PathVariable Long id) {
        return queryRepository.findById(id)
            .map(SlowQueryDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/queries/{id}/dismiss
     * Dismiss a slow query (user reviewed and decided to ignore).
     */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<SlowQueryDto> dismissQuery(@PathVariable Long id,
                                                     @RequestBody(required = false) Map<String, String> body) {
        return orchestrator.dismissQuery(id)
            .map(sq -> {
                activity.record(caller(), id, ActivityService.Action.DISMISSED, noteOf(body));
                return sq;
            })
            .map(SlowQueryDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/queries/{id}/apply
     *
     * Someone ran the suggested fix against the database. OptiQuery does not
     * execute it — a tool that writes to a customer's production schema is a
     * different product — so this records that a person did, and who.
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<?> applyQuery(@PathVariable Long id,
                                        @RequestBody(required = false) Map<String, String> body) {
        return queryRepository.findById(id)
            .map(sq -> {
                sq.setStatus(AnalysisStatus.APPLIED);
                sq.markUpdated();
                SlowQuery saved = queryRepository.save(sq);
                activity.record(caller(), id, ActivityService.Action.APPLIED, noteOf(body));
                return ResponseEntity.ok(SlowQueryDto.from(saved));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/queries/{id}/reopen
     * Undo a dismissal or an apply, putting the query back in the ranked list.
     */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<?> reopenQuery(@PathVariable Long id,
                                         @RequestBody(required = false) Map<String, String> body) {
        return queryRepository.findById(id)
            .map(sq -> {
                // Keep any existing analysis; only the disposition changes.
                sq.setStatus(sq.getAnalysis() != null
                    ? AnalysisStatus.COMPLETED : AnalysisStatus.PENDING);
                sq.markUpdated();
                SlowQuery saved = queryRepository.save(sq);
                activity.record(caller(), id, ActivityService.Action.REOPENED, noteOf(body));
                return ResponseEntity.ok(SlowQueryDto.from(saved));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/queries/{id}/activity
     * What the team has done to this query.
     */
    @GetMapping("/{id}/activity")
    public ResponseEntity<?> activityFor(@PathVariable Long id) {
        List<Map<String, Object>> events = activity.forQuery(caller(), id).stream()
            .map(SlowQueryController::describe)
            .toList();
        return ResponseEntity.ok(Map.of("activity", events));
    }

    /**
     * GET /api/queries/activity
     * The latest action per query, so the list can mark what nobody has touched.
     */
    @GetMapping("/activity")
    public ResponseEntity<?> teamActivity() {
        Map<String, Object> byQuery = new LinkedHashMap<>();
        activity.latestByQuery(caller()).forEach((queryId, e) ->
            byQuery.put(String.valueOf(queryId), describe(e)));
        return ResponseEntity.ok(Map.of("latest", byQuery));
    }

    private static String noteOf(Map<String, String> body) {
        return body == null ? null : body.get("note");
    }

    private static AuthenticatedUser caller() {
        return CurrentUser.get().orElse(null);
    }

    private static Map<String, Object> describe(ActivityService.Event e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.id());
        row.put("queryId", e.queryId());
        row.put("action", e.action().name());
        row.put("phrase", e.action().phrase());
        row.put("actorUid", e.actorUid());
        row.put("actorEmail", e.actorEmail());
        row.put("actorName", e.actorName());
        row.put("note", e.note());
        row.put("at", e.at() != null ? e.at().toString() : null);
        return row;
    }

    /**
     * POST /api/queries/{id}/reanalyze
     * Re-trigger the AI analysis for a specific query.
     */
    @PostMapping("/{id}/reanalyze")
    public ResponseEntity<SlowQueryDto> reanalyzeQuery(@PathVariable Long id) {
        return orchestrator.reanalyzeQuery(id)
            .map(sq -> {
                activity.record(caller(), id, ActivityService.Action.REANALYZED, null);
                return sq;
            })
            .map(SlowQueryDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/queries/poll
     * Manually trigger a poll cycle (for testing).
     */
    @PostMapping("/poll")
    public ResponseEntity<Map<String, Object>> triggerPoll() {
        try {
            poller.pollForSlowQueries();
            long count = queryRepository.count();
            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Poll completed. Total queries in DB: " + count
            ));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                "success", false,
                "message", "Poll failed: " + e.getMessage()
            ));
        }
    }

    /**
     * GET /api/queries/stats
     * Overview statistics for the dashboard.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long total = queryRepository.count();
        long pending = queryRepository.countByStatus(AnalysisStatus.PENDING);
        long completed = queryRepository.countByStatus(AnalysisStatus.COMPLETED);
        long dismissed = queryRepository.countByStatus(AnalysisStatus.DISMISSED);
        long applied = queryRepository.countByStatus(AnalysisStatus.APPLIED);
        long failed = queryRepository.countByStatus(AnalysisStatus.FAILED);

        return ResponseEntity.ok(Map.of(
            "totalQueries", total,
            "pendingAnalysis", pending,
            "completedAnalysis", completed,
            "dismissed", dismissed,
            "applied", applied,
            "failed", failed,
            // The cost the team actually pays: database time burned by every
            // query still under review, in milliseconds.
            "totalTimeBurnedMs", queryRepository.sumTotalExecTimeMs()
        ));
    }
}
