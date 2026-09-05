package com.optiq.repository;

import com.optiq.model.AnalysisStatus;
import com.optiq.model.SlowQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SlowQueryRepository extends JpaRepository<SlowQuery, Long> {

    /** Find by fingerprint for idempotent detection. */
    Optional<SlowQuery> findByQueryFingerprint(String queryFingerprint);

    /** Dashboard: list all queries sorted by mean exec time descending. */
    Page<SlowQuery> findAllByOrderByMeanExecTimeMsDesc(Pageable pageable);

    /** Dashboard: filter by status. */
    Page<SlowQuery> findAllByStatusOrderByMeanExecTimeMsDesc(AnalysisStatus status, Pageable pageable);

    /**
     * Dashboard: rank by total time burned across all calls.
     * This is the cost a team actually pays, so it is the default ordering:
     * a 20 ms query called 50,000 times outranks a 900 ms query called twice.
     */
    Page<SlowQuery> findAllByOrderByTotalExecTimeMsDesc(Pageable pageable);

    /** Dashboard: rank by total time burned, filtered by status. */
    Page<SlowQuery> findAllByStatusOrderByTotalExecTimeMsDesc(AnalysisStatus status, Pageable pageable);

    /** Aggregate total time burned across every non-dismissed query. */
    @Query("SELECT COALESCE(SUM(sq.totalExecTimeMs), 0) FROM SlowQuery sq WHERE sq.status <> com.optiq.model.AnalysisStatus.DISMISSED")
    double sumTotalExecTimeMs();

    /** Dashboard: search queries by SQL text. */
    @Query("SELECT sq FROM SlowQuery sq WHERE LOWER(sq.rawQuery) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY sq.meanExecTimeMs DESC")
    Page<SlowQuery> searchByRawQuery(@Param("searchTerm") String searchTerm, Pageable pageable);

    /** Count queries that need analysis (pending status). */
    long countByStatus(AnalysisStatus status);


}
