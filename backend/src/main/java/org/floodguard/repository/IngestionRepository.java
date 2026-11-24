package org.floodguard.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists immutable weather and risk records; JSONB and spatial joins use native SQL. */
@Repository
@RequiredArgsConstructor
public class IngestionRepository {
  private final JdbcTemplate jdbc;

  public String latestLevel(String sectorId) {
    List<String> levels =
        jdbc.query(
            """
            SELECT level FROM risk_assessments
            WHERE sector_id = ? ORDER BY assessed_at DESC LIMIT 1
            """,
            (result, row) -> result.getString(1),
            sectorId);
    return levels.isEmpty() ? null : levels.getFirst();
  }

  public long insertSnapshot(
      String sectorId,
      double rainfall,
      double soil,
      double baseline,
      double probability,
      String hourlyJson) {
    return jdbc.queryForObject(
        """
        INSERT INTO weather_snapshots(
          sector_id, forecast_rainfall_mm, soil_moisture, baseline_daily_mm,
          precipitation_probability, hourly_forecast
        ) VALUES (?, ?, ?, ?, ?, ?::jsonb) RETURNING id
        """,
        Long.class,
        sectorId,
        rainfall,
        soil,
        baseline,
        probability,
        hourlyJson);
  }

  public long insertAssessment(
      String sectorId, long snapshotId, int score, String level, String inputsJson) {
    return jdbc.queryForObject(
        """
        INSERT INTO risk_assessments(sector_id, snapshot_id, score, level, inputs)
        VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id
        """,
        Long.class,
        sectorId,
        snapshotId,
        score,
        level,
        inputsJson);
  }

  public void insertRoadImpacts(
      long assessmentId, String level, String sectorId, double vulnerabilityThreshold) {
    jdbc.update(
        """
        INSERT INTO road_impacts(assessment_id, road_id, window_start, window_end)
        SELECT ?, road.id,
          CASE WHEN ? = 'HIGH' THEN now() ELSE NULL END,
          CASE WHEN ? = 'HIGH' THEN now() + interval '12 hours' ELSE NULL END
        FROM roads road JOIN sectors sector ON ST_Intersects(road.geom, sector.boundary)
        WHERE sector.id = ? AND road.vulnerability >= ?
        """,
        assessmentId,
        level,
        level,
        sectorId,
        vulnerabilityThreshold);
  }
}
