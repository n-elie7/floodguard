package org.floodguard.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.floodguard.model.Sector;
import org.floodguard.repository.IngestionRepository;
import org.floodguard.repository.SectorLockRepository;
import org.floodguard.repository.SectorRepository;
import org.floodguard.websocket.RiskSocket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class IngestionService {
  private final IngestionRepository ingestionRepository;
  private final SectorRepository sectors;
  private final RiskModel model;
  private final AlertService alerts;
  private final RiskSocket socket;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final SectorLockRepository locks;
  private final RestClient http = RestClient.create();
  private final java.util.concurrent.locks.ReentrantLock cycleLock =
      new java.util.concurrent.locks.ReentrantLock();

  @Value("${floodguard.road-vulnerability-threshold}")
  private double roadThreshold;

  @Value("${floodguard.ingest-on-startup}")
  private boolean ingestOnStartup;

  public int runCycle() {
    if (!cycleLock.tryLock()) {
      return 0;
    }
    try {
      int processed = 0;
      for (Sector sector : sectors.findAll()) {
        try {
          if (locks.runIfAvailable(sector.getId(), () -> process(sector))) {
            processed++;
          }
        } catch (Exception ex) {
          metrics.counter("floodguard_ingestion_failures_total").increment();
          alerts.reportIngestionFailure(sector.getName(), ex);
        }
      }
      return processed;
    } finally {
      cycleLock.unlock();
    }
  }

  private void process(Sector sector) throws Exception {
    var scoreTimer = io.micrometer.core.instrument.Timer.start(metrics);
    String id = sector.getId();
    double lat = sector.getLatitude();
    double lon = sector.getLongitude();
    JsonNode forecast =
        fetch(
            URI.create(
                "https://api.open-meteo.com/v1/forecast?latitude="
                    + lat
                    + "&longitude="
                    + lon
                    + "&hourly=precipitation,precipitation_probability,soil_moisture_0_to_1cm&forecast_hours=12&timezone=UTC"));
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    JsonNode archive =
        fetch(
            URI.create(
                "https://archive-api.open-meteo.com/v1/archive?latitude="
                    + lat
                    + "&longitude="
                    + lon
                    + "&start_date="
                    + yesterday.minusDays(29)
                    + "&end_date="
                    + yesterday
                    + "&daily=precipitation_sum&timezone=UTC"));
    JsonNode hours = forecast.path("hourly");
    JsonNode rain = hours.path("precipitation");
    JsonNode moist = hours.path("soil_moisture_0_to_1cm");
    JsonNode probability = hours.path("precipitation_probability");
    JsonNode daily = archive.path("daily").path("precipitation_sum");
    if (!rain.isArray()
        || rain.size() < 12
        || !moist.isArray()
        || moist.isEmpty()
        || !daily.isArray()) throw new IllegalStateException("Incomplete Open-Meteo response");
    double rain12 = 0, baseline = 0;
    int days = 0;
    for (int i = 0; i < 12; i++) {
      if (rain.get(i).isNumber()) {
        rain12 += rain.get(i).asDouble();
      }
    }
    for (JsonNode value : daily)
      if (value.isNumber()) {
        baseline += value.asDouble();
        days++;
      }
    if (days < 20 || !moist.get(0).isNumber())
      throw new IllegalStateException("Missing soil or historical baseline");
    baseline /= days;
    double soil = moist.get(0).asDouble();
    double chance =
        probability.isArray() && probability.size() > 0 && probability.get(0).isNumber()
            ? probability.get(0).asDouble()
            : 0;
    int m = sector.getModerateThreshold();
    int h = sector.getHighThreshold();
    int score = model.score(rain12, soil, baseline);
    String level = model.level(score, m, h);
    String old = ingestionRepository.latestLevel(id);
    String hourly = json.writeValueAsString(hours);
    long snapshot = ingestionRepository.insertSnapshot(id, rain12, soil, baseline, chance, hourly);
    String inputs =
        json.writeValueAsString(
            Map.of(
                "rainfall12hMm",
                rain12,
                "soilMoisture",
                soil,
                "baselineDailyMm",
                baseline,
                "weights",
                Map.of("rainfall", 0.5, "soil", 0.3, "deviation", 0.2),
                "model",
                "experimental-rule-v1"));
    long assessment = ingestionRepository.insertAssessment(id, snapshot, score, level, inputs);
    if (!"LOW".equals(level)) {
      ingestionRepository.insertRoadImpacts(assessment, level, id, roadThreshold);
    }
    alerts.evaluate(id, sector.getName(), assessment, old, level, score);
    socket.publish(
        json.writeValueAsString(
            Map.of(
                "sectorId",
                id,
                "score",
                score,
                "level",
                level,
                "assessedAt",
                OffsetDateTime.now(ZoneOffset.UTC).toString())));
    scoreTimer.stop(metrics.timer("floodguard_scoring_duration_seconds"));
  }

  private JsonNode fetch(URI uri) throws InterruptedException {
    Exception last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return http.get().uri(uri).retrieve().body(JsonNode.class);
      } catch (Exception ex) {
        last = ex;
        if (attempt < 2) {
          Thread.sleep((1L << attempt) * 1000);
        }
      }
    }
    throw new IllegalStateException("Open-Meteo failed after three attempts", last);
  }
}
