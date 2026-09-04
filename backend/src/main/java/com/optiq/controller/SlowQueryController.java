package com.optiq.controller;

import com.optiq.dto.SlowQueriesResponse;
import com.optiq.dto.SlowQueryDto;
import com.optiq.model.AnalysisStatus;
import com.optiq.model.SlowQuery;
import com.optiq.repository.SlowQueryRepository;
import com.optiq.service.AnalysisOrchestrator;
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

    public SlowQueryController(SlowQueryRepository queryRepository, AnalysisOrchestrator orchestrator) {
        this.queryRepository = queryRepository;
        this.orchestrator = orchestrator;
    }

    /**
     * GET /api/queries/slow
     * List slow queries, sorted by mean execution time (descending).
     * Supports optional status filter.
     */
    @GetMapping("/slow")
    public ResponseEntity<SlowQueriesResponse> listSlowQueries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) AnalysisStatus status) {

        PageRequest pageRequest = PageRequest.of(page, size);
        Page<SlowQuery> results;

        if (status != null) {
            results = queryRepository.findAllByStatusOrderByMeanExecTimeMsDesc(status, pageRequest);
        } else {
            results = queryRepository.findAllByOrderByMeanExecTimeMsDesc(pageRequest);
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
    public ResponseEntity<SlowQueryDto> dismissQuery(@PathVariable Long id) {
        return orchestrator.dismissQuery(id)
            .map(SlowQueryDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/queries/{id}/reanalyze
     * Re-trigger the AI analysis for a specific query.
     */
    @PostMapping("/{id}/reanalyze")
    public ResponseEntity<SlowQueryDto> reanalyzeQuery(@PathVariable Long id) {
        return orchestrator.reanalyzeQuery(id)
            .map(SlowQueryDto::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
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
            "failed", failed
        ));
    }
}
