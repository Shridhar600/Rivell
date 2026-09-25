package com.rivell.workbench.review;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the review aggregate. Implementations live in platform and must
 * enforce organization scoping in SQL; cross-organization access is indistinguishable
 * from missing.
 */
public interface ReviewRepository {

    void create(Review review, ReviewSnapshot snapshot, List<ReviewPlanCheck> checks);

    Optional<Review> find(UUID organizationId, UUID reviewId);

    Optional<ReviewSnapshot> findSnapshot(UUID organizationId, UUID reviewId);

    List<ReviewPlanCheck> findPlanChecks(UUID organizationId, UUID reviewId);
}
