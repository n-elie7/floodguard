package org.floodguard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class ApiController {
    private final JdbcTemplate db;
    private final AlertService alerts;
    private final GeoImporter importer;
    private final IngestionService ingestion;
    private final BacktestService backtest;
    private final TwilioWebhookVerifier twilioVerifier;
    ApiController(JdbcTemplate db, AlertService alerts, GeoImporter importer, IngestionService ingestion,BacktestService backtest,TwilioWebhookVerifier twilioVerifier) {
        this.db=db; this.alerts=alerts; this.importer=importer; this.ingestion=ingestion;this.backtest=backtest;this.twilioVerifier=twilioVerifier;
    }

    @GetMapping("/sectors")
    public List<Map<String,Object>> sectors() {
        return db.queryForList("""
          SELECT s.id,s.name,s.district,s.latitude,s.longitude,s.moderate_threshold,s.high_threshold,
            ST_AsGeoJSON(s.boundary) AS boundary,
            r.score,r.level,r.assessed_at,
            (SELECT count(*) FROM roads rd WHERE ST_Intersects(rd.geom,s.boundary)) AS road_count
          FROM sectors s LEFT JOIN LATERAL
            (SELECT score,level,assessed_at FROM risk_assessments WHERE sector_id=s.id ORDER BY assessed_at DESC LIMIT 1) r ON true
          ORDER BY s.district,s.name
        """);
    }

    @GetMapping("/sectors/{id}/risk/current")
    public Map<String,Object> current(@PathVariable String id) {
        return one("""
          SELECT s.id AS sector_id,s.name AS sector_name,r.score,r.level,r.assessed_at,r.inputs::text AS inputs
          FROM sectors s LEFT JOIN LATERAL
          (SELECT score,level,assessed_at,inputs FROM risk_assessments WHERE sector_id=s.id ORDER BY assessed_at DESC LIMIT 1) r ON true
          WHERE s.id=?
        """, id);
    }

    @GetMapping("/sectors/{id}/risk/history")
    public List<Map<String,Object>> history(@PathVariable String id,
        @RequestParam("from") LocalDate from, @RequestParam("to") LocalDate to) {
        if (to.isBefore(from) || to.isAfter(from.plusYears(1))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid date range");
        return db.queryForList("SELECT score,level,assessed_at,inputs::text AS inputs FROM risk_assessments WHERE sector_id=? AND assessed_at>=? AND assessed_at<? ORDER BY assessed_at",
            id, from.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime(), to.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
    }

    @GetMapping("/sectors/{id}/forecast")
    public Map<String,Object> forecast(@PathVariable String id) {
        return one("SELECT observed_at,forecast_rainfall_mm,soil_moisture,baseline_daily_mm,precipitation_probability,hourly_forecast::text AS hourly_forecast,source FROM weather_snapshots WHERE sector_id=? ORDER BY observed_at DESC LIMIT 1",id);
    }

    @GetMapping("/sectors/{id}/roads/impact")
    public List<Map<String,Object>> roads(@PathVariable String id) {
        return db.queryForList("""
          SELECT rd.id,rd.name,rd.road_type,rd.vulnerability,rd.vulnerability_source,
            ST_AsGeoJSON(rd.geom) AS geometry,i.window_start,i.window_end
          FROM risk_assessments a JOIN road_impacts i ON i.assessment_id=a.id
            JOIN roads rd ON rd.id=i.road_id
          WHERE a.id=(SELECT id FROM risk_assessments WHERE sector_id=? ORDER BY assessed_at DESC LIMIT 1)
          ORDER BY rd.name
        """,id);
    }

    @GetMapping("/alerts")
    public List<Map<String,Object>> alertFeed() {
        return db.queryForList("SELECT a.id,a.sector_id,s.name AS sector_name,a.level,a.triggered_at,a.sent_count FROM alert_events a JOIN sectors s ON s.id=a.sector_id WHERE a.triggered_at > now()-interval '24 hours' ORDER BY a.triggered_at DESC LIMIT 100");
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> register(@RequestBody Map<String,String> body) {
        return alerts.register(body.get("sectorId"),body.get("phone"));
    }

    @PostMapping(value="/twilio/inbound",produces=MediaType.APPLICATION_XML_VALUE)
    public String inbound(HttpServletRequest request) {
        if(!twilioVerifier.valid(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid Twilio signature");
        String phone=request.getParameter("From"),message=request.getParameter("Body");
        if(phone==null||message==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing message fields");
        if ("STOP".equalsIgnoreCase(message.trim())) alerts.unsubscribe(phone);
        return "<Response/>";
    }

    @PostMapping("/twilio/status")
    public void delivery(HttpServletRequest request) {
        if(!twilioVerifier.valid(request))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid Twilio signature");
        String sid=request.getParameter("MessageSid"),status=request.getParameter("MessageStatus");
        if(sid==null||status==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Missing delivery fields");
        alerts.updateStatus(sid,status);
    }

    @GetMapping("/officer/summary")
    public Map<String,Object> summary(java.security.Principal principal) {
        return Map.of("subscriberCount",db.queryForObject("SELECT count(*) FROM subscriptions WHERE active",Long.class),
            "mustChangePassword",db.queryForObject("SELECT must_change_password FROM officer_accounts WHERE username=?",Boolean.class,principal.getName()),
            "deliveryRecords",db.queryForList("SELECT id,alert_id,status,sent_at,updated_at,error FROM delivery_records ORDER BY sent_at DESC LIMIT 100"));
    }

    @GetMapping("/officer/data-status")
    public Map<String,Object> dataStatus() {
        Map<String,Object> status=new LinkedHashMap<>();
        status.put("sectorCount",db.queryForObject("SELECT count(*) FROM sectors",Long.class));
        status.put("currentSectorCount",db.queryForObject("SELECT count(DISTINCT sector_id) FROM risk_assessments WHERE assessed_at>now()-interval '3 hours'",Long.class));
        status.put("roadCount",db.queryForObject("SELECT count(*) FROM roads",Long.class));
        status.put("verifiedRoadCount",db.queryForObject("SELECT count(*) FROM roads WHERE vulnerability IS NOT NULL AND nullif(vulnerability_source,'') IS NOT NULL",Long.class));
        status.put("verifiedShelterCount",db.queryForObject("SELECT count(*) FROM shelters WHERE verified AND nullif(source,'') IS NOT NULL",Long.class));
        status.put("latestAssessmentAt",db.queryForObject("SELECT max(assessed_at) FROM risk_assessments",java.time.OffsetDateTime.class));
        return status;
    }

    @GetMapping("/officer/session")
    public Map<String,Object> session(java.security.Principal principal) {
        return Map.of("username",principal.getName(),"mustChangePassword",
            db.queryForObject("SELECT must_change_password FROM officer_accounts WHERE username=?",Boolean.class,principal.getName()));
    }

    @PostMapping("/admin/import/boundaries")
    public Map<String,Object> importBoundaries() { return Map.of("imported",importer.importKigaliSectors()); }

    @PostMapping("/admin/ingest")
    public Map<String,Object> ingest() { return Map.of("processed",ingestion.runCycle()); }

    @GetMapping("/officer/replay")
    public Map<String,Object> replay(@RequestParam LocalDate date){
        var scores=db.queryForList("SELECT sector_id,score,level,assessed_at FROM risk_assessments WHERE assessed_at>=? AND assessed_at<? ORDER BY assessed_at",
            date.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime(),date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
        var events=db.queryForList("SELECT sector_id,level,triggered_at,sent_count FROM alert_events WHERE triggered_at>=? AND triggered_at<? ORDER BY triggered_at",
            date.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime(),date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
        var impacts=db.queryForList("""
            SELECT a.sector_id,r.name AS road_name,i.window_start,i.window_end,a.assessed_at
            FROM road_impacts i JOIN risk_assessments a ON a.id=i.assessment_id JOIN roads r ON r.id=i.road_id
            WHERE a.assessed_at>=? AND a.assessed_at<? ORDER BY a.assessed_at
        """,date.atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime(),date.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toOffsetDateTime());
        return Map.of("date",date,"assessments",scores,"alerts",events,"roadImpacts",impacts);
    }

    @GetMapping("/officer/backtest")
    public Map<String,Object> backtest(@RequestParam String sectorId,@RequestParam LocalDate from,@RequestParam LocalDate to){
        return backtest.run(sectorId,from,to);
    }

    @PutMapping("/admin/sectors/{id}/thresholds")
    public void thresholds(@PathVariable String id,@RequestBody Map<String,Integer> body) {
        Integer m=body.get("moderate"),h=body.get("high");
        if (m==null||h==null||m<1||h>100||m>=h) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid thresholds");
        if(db.update("UPDATE sectors SET moderate_threshold=?,high_threshold=?,updated_at=now() WHERE id=?",m,h,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sector not found");
    }

    private Map<String,Object> one(String sql,Object... args) {
        List<Map<String,Object>> rows=db.queryForList(sql,args);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No data found");
        return rows.getFirst();
    }
}
