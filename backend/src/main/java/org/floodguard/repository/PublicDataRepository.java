package org.floodguard.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read models for the public map and officer dashboard. Spatial projections require native SQL. */
@Repository
@RequiredArgsConstructor
public class PublicDataRepository {
  private final JdbcTemplate jdbc;

  public List<Map<String, Object>> sectorsWithCurrentRisk() {
    return jdbc.queryForList(
        """
        SELECT s.id, s.name, s.district, s.latitude, s.longitude,
          s.moderate_threshold, s.high_threshold, ST_AsGeoJSON(s.boundary) AS boundary,
          risk.score, risk.level, risk.assessed_at,
          (SELECT count(*) FROM roads road WHERE ST_Intersects(road.geom, s.boundary)) AS road_count
        FROM sectors s
        LEFT JOIN LATERAL (
          SELECT score, level, assessed_at FROM risk_assessments
          WHERE sector_id = s.id ORDER BY assessed_at DESC LIMIT 1
        ) risk ON true
        ORDER BY s.district, s.name
        """);
  }

  public List<Map<String, Object>> currentRisk(String sectorId) {
    return jdbc.queryForList(
        """
        SELECT s.id AS sector_id, s.name AS sector_name,
          risk.score, risk.level, risk.assessed_at, risk.inputs::text AS inputs
        FROM sectors s
        LEFT JOIN LATERAL (
          SELECT score, level, assessed_at, inputs FROM risk_assessments
          WHERE sector_id = s.id ORDER BY assessed_at DESC LIMIT 1
        ) risk ON true
        WHERE s.id = ?
        """,
        sectorId);
  }

  public List<Map<String, Object>> riskHistory(String sectorId, LocalDate from, LocalDate to) {
    return jdbc.queryForList(
        """
        SELECT score, level, assessed_at, inputs::text AS inputs
        FROM risk_assessments
        WHERE sector_id = ? AND assessed_at >= ? AND assessed_at < ?
        ORDER BY assessed_at
        """,
        sectorId,
        start(from),
        start(to.plusDays(1)));
  }

  public List<Map<String, Object>> latestForecast(String sectorId) {
    return jdbc.queryForList(
        """
        SELECT observed_at, forecast_rainfall_mm, soil_moisture, baseline_daily_mm,
          precipitation_probability, hourly_forecast::text AS hourly_forecast, source
        FROM weather_snapshots
        WHERE sector_id = ? ORDER BY observed_at DESC LIMIT 1
        """,
        sectorId);
  }

  public List<Map<String, Object>> roadImpacts(String sectorId) {
    return jdbc.queryForList(
        """
        SELECT road.id, road.name, road.road_type, road.vulnerability,
          road.vulnerability_source, ST_AsGeoJSON(road.geom) AS geometry,
          impact.window_start, impact.window_end
        FROM risk_assessments assessment
        JOIN road_impacts impact ON impact.assessment_id = assessment.id
        JOIN roads road ON road.id = impact.road_id
        WHERE assessment.id = (
          SELECT id FROM risk_assessments WHERE sector_id = ?
          ORDER BY assessed_at DESC LIMIT 1
        )
        ORDER BY road.name
        """,
        sectorId);
  }

  public List<Map<String, Object>> recentAlerts() {
    return jdbc.queryForList(
        """
        SELECT alert.id, alert.sector_id, sector.name AS sector_name,
          alert.level, alert.triggered_at, alert.sent_count
        FROM alert_events alert JOIN sectors sector ON sector.id = alert.sector_id
        WHERE alert.triggered_at > now() - interval '24 hours'
        ORDER BY alert.triggered_at DESC LIMIT 100
        """);
  }

  public long activeSubscriberCount() {
    return jdbc.queryForObject("SELECT count(*) FROM subscriptions WHERE active", Long.class);
  }

  public List<Map<String, Object>> recentDeliveries() {
    return jdbc.queryForList(
        """
        SELECT id, alert_id, status, sent_at, updated_at, error
        FROM delivery_records ORDER BY sent_at DESC LIMIT 100
        """);
  }

  public long currentSectorCount() {
    return jdbc.queryForObject(
        """
        SELECT count(DISTINCT sector_id) FROM risk_assessments
        WHERE assessed_at > now() - interval '3 hours'
        """,
        Long.class);
  }

  public long roadCount() {
    return jdbc.queryForObject("SELECT count(*) FROM roads", Long.class);
  }

  public long verifiedRoadCount() {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM roads
        WHERE vulnerability IS NOT NULL AND nullif(vulnerability_source, '') IS NOT NULL
        """,
        Long.class);
  }

  public long verifiedShelterCount() {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM shelters
        WHERE verified AND nullif(source, '') IS NOT NULL
        """,
        Long.class);
  }

  public OffsetDateTime latestAssessmentAt() {
    return jdbc.queryForObject(
        "SELECT max(assessed_at) FROM risk_assessments", OffsetDateTime.class);
  }

  public List<Map<String, Object>> assessmentsOn(LocalDate date) {
    return jdbc.queryForList(
        """
        SELECT sector_id, score, level, assessed_at FROM risk_assessments
        WHERE assessed_at >= ? AND assessed_at < ? ORDER BY assessed_at
        """,
        start(date),
        start(date.plusDays(1)));
  }

  public List<Map<String, Object>> alertsOn(LocalDate date) {
    return jdbc.queryForList(
        """
        SELECT sector_id, level, triggered_at, sent_count FROM alert_events
        WHERE triggered_at >= ? AND triggered_at < ? ORDER BY triggered_at
        """,
        start(date),
        start(date.plusDays(1)));
  }

  public List<Map<String, Object>> roadImpactsOn(LocalDate date) {
    return jdbc.queryForList(
        """
        SELECT assessment.sector_id, road.name AS road_name,
          impact.window_start, impact.window_end, assessment.assessed_at
        FROM road_impacts impact
        JOIN risk_assessments assessment ON assessment.id = impact.assessment_id
        JOIN roads road ON road.id = impact.road_id
        WHERE assessment.assessed_at >= ? AND assessment.assessed_at < ?
        ORDER BY assessment.assessed_at
        """,
        start(date),
        start(date.plusDays(1)));
  }

  private OffsetDateTime start(LocalDate date) {
    return date.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
  }
}
