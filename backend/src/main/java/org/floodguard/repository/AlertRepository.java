package org.floodguard.repository;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.floodguard.dto.Subscriber;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Alert persistence and the small set of spatial lookups used to compose messages. */
@Repository
@RequiredArgsConstructor
public class AlertRepository {
  private final JdbcTemplate jdbc;

  public void upsertSubscription(String sectorId, String phoneHash, String phoneCiphertext) {
    jdbc.update(
        """
        INSERT INTO subscriptions(sector_id, phone_hash, phone_ciphertext, active)
        VALUES (?, ?, ?, true)
        ON CONFLICT (sector_id, phone_hash) DO UPDATE SET
          phone_ciphertext = excluded.phone_ciphertext, active = true
        """,
        sectorId,
        phoneHash,
        phoneCiphertext);
  }

  public void unsubscribe(String phoneHash) {
    jdbc.update("UPDATE subscriptions SET active = false WHERE phone_hash = ?", phoneHash);
  }

  public void updateDeliveryStatus(String providerId, String status) {
    jdbc.update(
        """
        UPDATE delivery_records SET status = ?, updated_at = now()
        WHERE provider_message_id = ?
        """,
        status,
        providerId);
  }

  public String highestRiskRoad(long assessmentId) {
    List<String> names =
        jdbc.query(
            """
            SELECT road.name FROM road_impacts impact
            JOIN roads road ON road.id = impact.road_id
            WHERE impact.assessment_id = ?
            ORDER BY road.vulnerability DESC LIMIT 1
            """,
            (result, row) -> result.getString(1),
            assessmentId);
    return names.isEmpty() ? null : names.getFirst();
  }

  public String nearestVerifiedShelter(String sectorId) {
    List<String> names =
        jdbc.query(
            """
            SELECT name FROM shelters WHERE verified
            ORDER BY ST_Distance(
              location::geography,
              (SELECT ST_PointOnSurface(boundary)::geography FROM sectors WHERE id = ?)
            ) LIMIT 1
            """,
            (result, row) -> result.getString(1),
            sectorId);
    return names.isEmpty() ? null : names.getFirst();
  }

  public long createEvent(String sectorId, long assessmentId, String level, String message) {
    return jdbc.queryForObject(
        """
        INSERT INTO alert_events(sector_id, assessment_id, level, message)
        VALUES (?, ?, ?, ?) RETURNING id
        """,
        Long.class,
        sectorId,
        assessmentId,
        level,
        message);
  }

  public List<Subscriber> activeSubscribers(String sectorId) {
    return jdbc.query(
        """
        SELECT id, phone_ciphertext FROM subscriptions
        WHERE sector_id = ? AND active
        """,
        (result, row) -> new Subscriber(result.getLong(1), result.getString(2)),
        sectorId);
  }

  public boolean deliveredRecently(long subscriptionId, String sectorId) {
    Long count =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM delivery_records delivery
            JOIN alert_events alert ON alert.id = delivery.alert_id
            WHERE delivery.subscription_id = ? AND alert.sector_id = ?
              AND alert.triggered_at > now() - interval '4 hours'
            """,
            Long.class,
            subscriptionId,
            sectorId);
    return count != null && count > 0;
  }

  public void recordDelivery(long alertId, long subscriptionId, String providerId, String status) {
    jdbc.update(
        """
        INSERT INTO delivery_records(alert_id, subscription_id, provider_message_id, status)
        VALUES (?, ?, ?, ?)
        """,
        alertId,
        subscriptionId,
        providerId,
        status);
  }

  public void recordFailure(long alertId, long subscriptionId, String error) {
    jdbc.update(
        """
        INSERT INTO delivery_records(alert_id, subscription_id, status, error)
        VALUES (?, ?, 'failed', ?)
        """,
        alertId,
        subscriptionId,
        error);
  }

  public void updateSentCount(long alertId, int count) {
    jdbc.update("UPDATE alert_events SET sent_count = ? WHERE id = ?", count, alertId);
  }
}
