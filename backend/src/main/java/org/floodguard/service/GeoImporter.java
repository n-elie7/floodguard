package org.floodguard.service;

import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.floodguard.repository.SpatialWriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class GeoImporter {
  private static final URI BOUNDARIES =
      URI.create(
          "https://moegis.environment.gov.rw/server/rest/services/Hosted/Administrative_boundaries/FeatureServer/1/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geojson");
  private final SpatialWriteRepository spatial;
  private final ObjectMapper json;
  private final RestClient http = RestClient.create();

  public int importKigaliSectors() {
    JsonNode root = http.get().uri(BOUNDARIES).retrieve().body(JsonNode.class);
    if (root == null || !root.path("features").isArray())
      throw new IllegalStateException("Boundary service did not return GeoJSON features");
    int count = 0;
    for (JsonNode feature : root.path("features")) {
      JsonNode p = feature.path("properties");
      String province = p.path("province").asText("");
      if (!province.toLowerCase().contains("kigali")) {
        continue;
      }
      String id = p.path("sector_id").asText("");
      String name = p.path("sector").asText("");
      String district = p.path("district").asText("");
      if (id.isBlank() || name.isBlank() || feature.path("geometry").isMissingNode()) {
        continue;
      }
      try {
        String geom = json.writeValueAsString(feature.path("geometry"));
        spatial.upsertSector(id, name, district, geom);
        count++;
      } catch (Exception ex) {
        throw new IllegalStateException("Invalid geometry for sector " + id, ex);
      }
    }
    if (count == 0) {
      throw new IllegalStateException("No Kigali sectors found in boundary response");
    }
    return count;
  }
}
