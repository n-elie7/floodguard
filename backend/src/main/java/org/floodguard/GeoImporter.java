package org.floodguard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class GeoImporter {
    private static final URI BOUNDARIES = URI.create("https://moegis.environment.gov.rw/server/rest/services/Hosted/Administrative_boundaries/FeatureServer/1/query?where=1%3D1&outFields=*&returnGeometry=true&outSR=4326&f=geojson");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RestClient http=RestClient.create();
    GeoImporter(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }

    public int importKigaliSectors() {
        JsonNode root=http.get().uri(BOUNDARIES).retrieve().body(JsonNode.class);
        if(root==null||!root.path("features").isArray()) throw new IllegalStateException("Boundary service did not return GeoJSON features");
        int count=0;
        for(JsonNode feature:root.path("features")) {
            JsonNode p=feature.path("properties");
            String province=p.path("province").asText("");
            if(!province.toLowerCase().contains("kigali")) continue;
            String id=p.path("sector_id").asText("");
            String name=p.path("sector").asText("");
            String district=p.path("district").asText("");
            if(id.isBlank()||name.isBlank()||feature.path("geometry").isMissingNode()) continue;
            try {
                String geom=json.writeValueAsString(feature.path("geometry"));
                db.update("""
                  INSERT INTO sectors(id,name,district,boundary,latitude,longitude)
                  SELECT ?,?,?,ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326)),
                    ST_Y(ST_PointOnSurface(ST_GeomFromGeoJSON(?))),ST_X(ST_PointOnSurface(ST_GeomFromGeoJSON(?)))
                  ON CONFLICT(id) DO UPDATE SET name=excluded.name,district=excluded.district,
                    boundary=excluded.boundary,latitude=excluded.latitude,longitude=excluded.longitude,updated_at=now()
                """,id,name,district,geom,geom,geom);
                count++;
            } catch(Exception ex) { throw new IllegalStateException("Invalid geometry for sector "+id,ex); }
        }
        if(count==0) throw new IllegalStateException("No Kigali sectors found in boundary response");
        return count;
    }
}
