package org.floodguard.controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.floodguard.model.OfficerAccount;
import org.floodguard.repository.OfficerAccountRepository;
import org.floodguard.repository.RoadRepository;
import org.floodguard.repository.SpatialWriteRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DataAdminController {
  private final SpatialWriteRepository spatial;
  private final RoadRepository roads;
  private final ObjectMapper json;
  private final PasswordEncoder encoder;
  private final OfficerAccountRepository accounts;

  @PostMapping("/admin/roads/import")
  public Map<String, Integer> roads(@RequestBody JsonNode collection) {
    if (!collection.path("features").isArray())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "GeoJSON FeatureCollection required");
    int count = 0;
    for (JsonNode feature : collection.path("features")) {
      JsonNode p = feature.path("properties");
      String id = p.path("osm_id").asText(p.path("id").asText(""));
      if (id.isBlank() || feature.path("geometry").isMissingNode()) {
        continue;
      }
      String name = p.path("name").asText("Unnamed road");
      String type = p.path("fclass").asText(p.path("highway").asText("unknown"));
      String geometry = json.writeValueAsString(feature.path("geometry"));
      count += spatial.upsertRoad(id, name, type, geometry);
    }
    return Map.of("imported", count);
  }

  @PutMapping("/admin/roads/{id}/vulnerability")
  public void vulnerability(@PathVariable String id, @RequestBody Map<String, Object> body) {
    Object score = body.get("score");
    String source = (String) body.get("source");
    if (!(score instanceof Number)
        || ((Number) score).doubleValue() < 0
        || ((Number) score).doubleValue() > 1
        || source == null
        || source.isBlank())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Score 0–1 and source are required");
    if (roads.setVulnerability(id, ((Number) score).doubleValue(), source) == 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Road not found");
    }
  }

  @PostMapping("/admin/roads/vulnerability/batch")
  public Map<String, Integer> vulnerabilityBatch(@RequestBody List<Map<String, Object>> rows) {
    if (rows.size() > 1000)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Upload at most 1000 rows per batch");
    int updated = 0;
    for (Map<String, Object> row : rows) {
      String id = (String) row.get("roadId"), source = (String) row.get("source");
      Object score = row.get("score");
      if (id == null
          || source == null
          || source.isBlank()
          || !(score instanceof Number)
          || ((Number) score).doubleValue() < 0
          || ((Number) score).doubleValue() > 1)
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Each row needs roadId, score 0–1 and source");
      updated += roads.setVulnerability(id, ((Number) score).doubleValue(), source);
    }
    return Map.of("updated", updated);
  }

  @PostMapping("/admin/shelters")
  public Map<String, Object> shelter(@RequestBody Map<String, Object> body) {
    String sector = (String) body.get("sectorId"),
        name = (String) body.get("name"),
        source = (String) body.get("source");
    Object lat = body.get("latitude"), lon = body.get("longitude");
    if (sector == null
        || name == null
        || source == null
        || source.isBlank()
        || !(lat instanceof Number)
        || !(lon instanceof Number))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Sector, name, location and source are required");
    long id =
        spatial.insertShelter(
            sector,
            name,
            ((Number) lon).doubleValue(),
            ((Number) lat).doubleValue(),
            Boolean.TRUE.equals(body.get("verified")),
            source);
    return Map.of("id", id);
  }

  @PutMapping("/officer/password")
  public void password(Principal principal, @RequestBody Map<String, String> body) {
    String current = body.get("currentPassword"), next = body.get("newPassword");
    if (current == null || next == null || next.length() < 14)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A new password of at least 14 characters is required");
    OfficerAccount account =
        accounts
            .findById(principal.getName())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Officer not found"));
    if (!encoder.matches(current, account.getPasswordHash()))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current password is wrong");
    account.setPasswordHash(encoder.encode(next));
    account.setMustChangePassword(false);
    accounts.save(account);
  }
}
