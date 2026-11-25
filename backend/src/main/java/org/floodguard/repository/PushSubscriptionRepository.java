package org.floodguard.repository;

import java.util.List;
import org.floodguard.model.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, String> {
  List<PushSubscription> findBySectorIdAndActiveTrue(String sectorId);
}
