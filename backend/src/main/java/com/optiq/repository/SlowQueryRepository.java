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

    /** Dashboard: search queries by SQL text. */
    @Query("SELECT sq FROM SlowQuery sq WHERE LOWER(sq.rawQuery) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY sq.meanExecTimeMs DESC")
    Page<SlowQuery> searchByRawQuery(@Param("searchTerm") String searchTerm, Pageable pageable);

    /** Count queries that need analysis (pending status). */
    long countByStatus(AnalysisStatus status);


}
