package org.floodguard.repository;

import org.floodguard.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
  long countBySectorIdAndActiveTrue(String sectorId);
}
