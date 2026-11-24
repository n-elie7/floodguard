package org.floodguard.repository;

import org.floodguard.model.Road;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RoadRepository extends JpaRepository<Road, String> {
  @Modifying
  @Transactional
  @Query(
      "update Road road set road.vulnerability = :score, road.vulnerabilitySource = :source where"
          + " road.id = :id")
  int setVulnerability(
      @Param("id") String id, @Param("score") double score, @Param("source") String source);
}
