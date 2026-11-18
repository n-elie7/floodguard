package org.floodguard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.Map;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class DataAdminController {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final PasswordEncoder encoder;
    DataAdminController(JdbcTemplate db,ObjectMapper json,PasswordEncoder encoder){this.db=db;this.json=json;this.encoder=encoder;}

    @PostMapping("/admin/roads/import")
    public Map<String,Integer> roads(@RequestBody JsonNode collection){
        if(!collection.path("features").isArray())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"GeoJSON FeatureCollection required");
        int count=0;
        for(JsonNode feature:collection.path("features")){
            JsonNode p=feature.path("properties");
            String id=p.path("osm_id").asText(p.path("id").asText(""));
            if(id.isBlank()||feature.path("geometry").isMissingNode())continue;
            String name=p.path("name").asText("Unnamed road");
            String type=p.path("fclass").asText(p.path("highway").asText("unknown"));
            String geometry=json.writeValueAsString(feature.path("geometry"));
            count+=db.update("""
              INSERT INTO roads(id,name,road_type,geom)
              SELECT ?,?,?,ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?),4326))
              WHERE EXISTS(SELECT 1 FROM sectors WHERE ST_Intersects(boundary,ST_SetSRID(ST_GeomFromGeoJSON(?),4326)))
              ON CONFLICT(id) DO UPDATE SET name=excluded.name,road_type=excluded.road_type,geom=excluded.geom
            """,id,name,type,geometry,geometry);
        }
        return Map.of("imported",count);
    }

    @PutMapping("/admin/roads/{id}/vulnerability")
    public void vulnerability(@PathVariable String id,@RequestBody Map<String,Object> body){
        Object score=body.get("score");String source=(String)body.get("source");
        if(!(score instanceof Number)||((Number)score).doubleValue()<0||((Number)score).doubleValue()>1||source==null||source.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Score 0–1 and source are required");
        if(db.update("UPDATE roads SET vulnerability=?,vulnerability_source=? WHERE id=?",((Number)score).doubleValue(),source,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Road not found");
    }

    @PostMapping("/admin/roads/vulnerability/batch")
    public Map<String,Integer> vulnerabilityBatch(@RequestBody List<Map<String,Object>> rows){
        if(rows.size()>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Upload at most 1000 rows per batch");
        int updated=0;
        for(Map<String,Object> row:rows){
            String id=(String)row.get("roadId"),source=(String)row.get("source");
            Object score=row.get("score");
            if(id==null||source==null||source.isBlank()||!(score instanceof Number)||((Number)score).doubleValue()<0||((Number)score).doubleValue()>1)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Each row needs roadId, score 0–1 and source");
            updated+=db.update("UPDATE roads SET vulnerability=?,vulnerability_source=? WHERE id=?",
                ((Number)score).doubleValue(),source,id);
        }
        return Map.of("updated",updated);
    }

    @PostMapping("/admin/shelters")
    public Map<String,Object> shelter(@RequestBody Map<String,Object> body){
        String sector=(String)body.get("sectorId"),name=(String)body.get("name"),source=(String)body.get("source");
        Object lat=body.get("latitude"),lon=body.get("longitude");
        if(sector==null||name==null||source==null||source.isBlank()||!(lat instanceof Number)||!(lon instanceof Number))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Sector, name, location and source are required");
        long id=db.queryForObject("INSERT INTO shelters(sector_id,name,location,verified,source) VALUES (?,?,ST_SetSRID(ST_MakePoint(?,?),4326),?,?) RETURNING id",
            Long.class,sector,name,((Number)lon).doubleValue(),((Number)lat).doubleValue(),Boolean.TRUE.equals(body.get("verified")),source);
        return Map.of("id",id);
    }

    @PutMapping("/officer/password")
    public void password(Principal principal,@RequestBody Map<String,String> body){
        String current=body.get("currentPassword"),next=body.get("newPassword");
        if(current==null||next==null||next.length()<14)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A new password of at least 14 characters is required");
        String hash=db.queryForObject("SELECT password_hash FROM officer_accounts WHERE username=?",String.class,principal.getName());
        if(!encoder.matches(current,hash))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Current password is wrong");
        db.update("UPDATE officer_accounts SET password_hash=?,must_change_password=false WHERE username=?",encoder.encode(next),principal.getName());
    }
}
