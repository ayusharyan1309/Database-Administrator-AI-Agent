package com.optiq.dto;

import java.util.List;

/**
 * Paginated response for the slow queries list endpoint.
 */
public record SlowQueriesResponse(
    List<SlowQueryDto> queries,
    long totalCount,
    int page,
    int size
) {}
