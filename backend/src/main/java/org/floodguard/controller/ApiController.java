package org.floodguard.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.floodguard.model.OfficerAccount;
import org.floodguard.model.Sector;
import org.floodguard.repository.OfficerAccountRepository;
import org.floodguard.repository.PublicDataRepository;
import org.floodguard.repository.SectorRepository;
import org.floodguard.repository.SubscriptionRepository;
import org.floodguard.service.AlertService;
import org.floodguard.service.BacktestService;
import org.floodguard.service.GeoImporter;
import org.floodguard.service.IngestionService;
import org.floodguard.service.RiskModel;
import org.floodguard.service.TwilioWebhookVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ApiController {
  private final PublicDataRepository data;
  private final AlertService alerts;
  private final GeoImporter importer;
  private final IngestionService ingestion;
  private final BacktestService backtest;
  private final TwilioWebhookVerifier twilioVerifier;
  private final SectorRepository sectors;
  private final OfficerAccountRepository accounts;
  private final RiskModel riskModel;
  private final SubscriptionRepository subscriptions;

  @GetMapping("/sectors")
  public List<Map<String, Object>> sectors() {
    return data.sectorsWithCurrentRisk();
  }

  @GetMapping("/sectors/{id}/risk/current")
  public Map<String, Object> current(@PathVariable String id) {
    return firstOrNotFound(data.currentRisk(id));
  }

  @GetMapping("/sectors/{id}/risk/history")
  public List<Map<String, Object>> history(
      @PathVariable String id,
      @RequestParam("from") LocalDate from,
      @RequestParam("to") LocalDate to) {
    if (to.isBefore(from) || to.isAfter(from.plusYears(1))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date range");
    }
    return data.riskHistory(id, from, to);
  }

  @GetMapping("/sectors/{id}/forecast")
  public Map<String, Object> forecast(@PathVariable String id) {
    return firstOrNotFound(data.latestForecast(id));
  }

  @GetMapping("/sectors/{id}/roads/impact")
  public List<Map<String, Object>> roads(@PathVariable String id) {
    return data.roadImpacts(id);
  }

  @GetMapping("/alerts")
  public List<Map<String, Object>> alertFeed() {
    return data.recentAlerts();
  }

  @PostMapping("/subscriptions")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> register(@RequestBody Map<String, String> body) {
    return alerts.register(body.get("sectorId"), body.get("phone"));
  }

  @PostMapping(value = "/twilio/inbound", produces = MediaType.APPLICATION_XML_VALUE)
  public String inbound(HttpServletRequest request) {
    if (!twilioVerifier.valid(request)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid Twilio signature");
    }
    String phone = request.getParameter("From");
    String message = request.getParameter("Body");
    if (phone == null || message == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing message fields");
    }
    if ("STOP".equalsIgnoreCase(message.trim())) {
      alerts.unsubscribe(phone);
    }
    return "<Response/>";
  }

  @PostMapping("/twilio/status")
  public void delivery(HttpServletRequest request) {
    if (!twilioVerifier.valid(request)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid Twilio signature");
    }
    String sid = request.getParameter("MessageSid");
    String status = request.getParameter("MessageStatus");
    if (sid == null || status == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing delivery fields");
    }
    alerts.updateStatus(sid, status);
  }

  @GetMapping("/officer/summary")
  public Map<String, Object> summary(Principal principal) {
    return Map.of(
        "subscriberCount", data.activeSubscriberCount(),
        "mustChangePassword", officer(principal).isMustChangePassword(),
        "deliveryRecords", data.recentDeliveries());
  }

  @GetMapping("/officer/data-status")
  public Map<String, Object> dataStatus() {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("sectorCount", sectors.count());
    status.put("currentSectorCount", data.currentSectorCount());
    status.put("roadCount", data.roadCount());
    status.put("verifiedRoadCount", data.verifiedRoadCount());
    status.put("verifiedShelterCount", data.verifiedShelterCount());
    status.put("latestAssessmentAt", data.latestAssessmentAt());
    return status;
  }

  @GetMapping("/officer/sectors/{id}/subscribers")
  public Map<String, Object> sectorSubscribers(@PathVariable String id) {
    if (!sectors.existsById(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sector not found");
    }
    return Map.of("sectorId", id, "activeSubscribers", subscriptions.countBySectorIdAndActiveTrue(id));
  }

  @GetMapping("/officer/session")
  public Map<String, Object> session(Principal principal) {
    return Map.of(
        "username", principal.getName(),
        "mustChangePassword", officer(principal).isMustChangePassword());
  }

  @PostMapping("/admin/import/boundaries")
  public Map<String, Object> importBoundaries() {
    return Map.of("imported", importer.importKigaliSectors());
  }

  @PostMapping("/admin/ingest")
  public Map<String, Object> ingest() {
    return Map.of("processed", ingestion.runCycle());
  }

  @GetMapping("/officer/replay")
  public Map<String, Object> replay(@RequestParam LocalDate date) {
    return Map.of(
        "date", date,
        "assessments", data.assessmentsOn(date),
        "alerts", data.alertsOn(date),
        "roadImpacts", data.roadImpactsOn(date));
  }

  @GetMapping("/officer/backtest")
  public Map<String, Object> backtest(
      @RequestParam String sectorId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return backtest.run(sectorId, from, to);
  }

  @PostMapping("/officer/risk-preview")
  public Map<String, Object> riskPreview(@RequestBody Map<String, Object> body) {
    Object sectorId = body.get("sectorId");
    Object rain = body.get("rainfallMm");
    Object soil = body.get("soilMoisture");
    Object baseline = body.get("baselineDailyMm");
    if (!(sectorId instanceof String id)
        || !(rain instanceof Number rainfall)
        || !(soil instanceof Number moisture)
        || !(baseline instanceof Number daily)
        || !Double.isFinite(rainfall.doubleValue())
        || !Double.isFinite(moisture.doubleValue())
        || !Double.isFinite(daily.doubleValue())
        || rainfall.doubleValue() < 0
        || rainfall.doubleValue() > 500
        || moisture.doubleValue() < 0
        || moisture.doubleValue() > 1
        || daily.doubleValue() < 0
        || daily.doubleValue() > 500) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid preview inputs");
    }
    Sector sector =
        sectors
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sector not found"));
    int score = riskModel.score(rainfall.doubleValue(), moisture.doubleValue(), daily.doubleValue());
    return Map.of(
        "sectorName", sector.getName(),
        "score", score,
        "level", riskModel.level(score, sector.getModerateThreshold(), sector.getHighThreshold()),
        "moderateThreshold", sector.getModerateThreshold(),
        "highThreshold", sector.getHighThreshold(),
        "simulation", true);
  }

  @PutMapping("/admin/sectors/{id}/thresholds")
  public void thresholds(@PathVariable String id, @RequestBody Map<String, Integer> body) {
    Integer moderate = body.get("moderate");
    Integer high = body.get("high");
    if (moderate == null || high == null || moderate < 1 || high > 100 || moderate >= high) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid thresholds");
    }
    Sector sector =
        sectors
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sector not found"));
    sector.setModerateThreshold(moderate);
    sector.setHighThreshold(high);
    sector.setUpdatedAt(OffsetDateTime.now());
    sectors.save(sector);
  }

  private OfficerAccount officer(Principal principal) {
    return accounts.findById(principal.getName()).orElseThrow();
  }

  private Map<String, Object> firstOrNotFound(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No data found");
    }
    return rows.getFirst();
  }
}
