package org.floodguard;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

@Service
public class IngestionService {
    private final JdbcTemplate db;
    private final RiskModel model;
    private final AlertService alerts;
    private final RiskSocket socket;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final DataSource dataSource;
    private final RestClient http=RestClient.create();
    private final java.util.concurrent.locks.ReentrantLock cycleLock=new java.util.concurrent.locks.ReentrantLock();
    @Value("${floodguard.road-vulnerability-threshold}") double roadThreshold;
    @Value("${floodguard.ingest-on-startup}") boolean ingestOnStartup;

    IngestionService(JdbcTemplate db,RiskModel model,AlertService alerts,RiskSocket socket,ObjectMapper json,MeterRegistry metrics,DataSource dataSource) {
        this.db=db;this.model=model;this.alerts=alerts;this.socket=socket;this.json=json;this.metrics=metrics;this.dataSource=dataSource;
    }

    public int runCycle() {
        if(!cycleLock.tryLock()) return 0;
        try {
        int processed=0;
        for(Map<String,Object> sector:db.queryForList("SELECT id,name,latitude,longitude,moderate_threshold,high_threshold FROM sectors")) {
            try {
                // A session advisory lock assigns a sector to one worker in this cycle.
                try(Connection connection=dataSource.getConnection()) {
                    try(PreparedStatement lock=connection.prepareStatement("SELECT pg_try_advisory_lock(447411,hashtext(?))")) {
                        lock.setString(1,(String)sector.get("id"));
                        try(ResultSet result=lock.executeQuery()) {
                            result.next();if(!result.getBoolean(1))continue;
                        }
                    }
                    try { process(sector);processed++; }
                    finally {
                        try(PreparedStatement unlock=connection.prepareStatement("SELECT pg_advisory_unlock(447411,hashtext(?))")) {
                            unlock.setString(1,(String)sector.get("id"));unlock.execute();
                        }
                    }
                }
            }
            catch(Exception ex) {
                metrics.counter("floodguard_ingestion_failures_total").increment();
                alerts.reportIngestionFailure((String)sector.get("name"),ex);
            }
        }
        return processed;
        } finally { cycleLock.unlock(); }
    }

    private void process(Map<String,Object> sector) throws Exception {
        var scoreTimer=io.micrometer.core.instrument.Timer.start(metrics);
        String id=(String)sector.get("id");
        double lat=((Number)sector.get("latitude")).doubleValue();
        double lon=((Number)sector.get("longitude")).doubleValue();
        JsonNode forecast=fetch(URI.create("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&hourly=precipitation,precipitation_probability,soil_moisture_0_to_1cm&forecast_hours=12&timezone=UTC"));
        LocalDate yesterday=LocalDate.now(ZoneOffset.UTC).minusDays(1);
        JsonNode archive=fetch(URI.create("https://archive-api.open-meteo.com/v1/archive?latitude="+lat+"&longitude="+lon+"&start_date="+yesterday.minusDays(29)+"&end_date="+yesterday+"&daily=precipitation_sum&timezone=UTC"));
        JsonNode hours=forecast.path("hourly");
        JsonNode rain=hours.path("precipitation");
        JsonNode moist=hours.path("soil_moisture_0_to_1cm");
        JsonNode probability=hours.path("precipitation_probability");
        JsonNode daily=archive.path("daily").path("precipitation_sum");
        if(!rain.isArray()||rain.size()<12||!moist.isArray()||moist.isEmpty()||!daily.isArray())
            throw new IllegalStateException("Incomplete Open-Meteo response");
        double rain12=0,baseline=0; int days=0;
        for(int i=0;i<12;i++) if(rain.get(i).isNumber()) rain12+=rain.get(i).asDouble();
        for(JsonNode value:daily) if(value.isNumber()) { baseline+=value.asDouble();days++; }
        if(days<20||!moist.get(0).isNumber()) throw new IllegalStateException("Missing soil or historical baseline");
        baseline/=days;
        double soil=moist.get(0).asDouble();
        double chance=probability.isArray()&&probability.size()>0&&probability.get(0).isNumber()?probability.get(0).asDouble():0;
        int m=((Number)sector.get("moderate_threshold")).intValue();
        int h=((Number)sector.get("high_threshold")).intValue();
        int score=model.score(rain12,soil,baseline);
        String level=model.level(score,m,h);
        List<String> previous=db.query("SELECT level FROM risk_assessments WHERE sector_id=? ORDER BY assessed_at DESC LIMIT 1",(rs,n)->rs.getString(1),id);
        String old=previous.isEmpty()?null:previous.getFirst();
        String hourly=json.writeValueAsString(hours);
        long snapshot=db.queryForObject("""
          INSERT INTO weather_snapshots(sector_id,forecast_rainfall_mm,soil_moisture,baseline_daily_mm,precipitation_probability,hourly_forecast)
          VALUES (?,?,?,?,?,?::jsonb) RETURNING id
        """,Long.class,id,rain12,soil,baseline,chance,hourly);
        String inputs=json.writeValueAsString(Map.of("rainfall12hMm",rain12,"soilMoisture",soil,"baselineDailyMm",baseline,
            "weights",Map.of("rainfall",0.5,"soil",0.3,"deviation",0.2),"model","experimental-rule-v1"));
        long assessment=db.queryForObject("INSERT INTO risk_assessments(sector_id,snapshot_id,score,level,inputs) VALUES (?,?,?,?,?::jsonb) RETURNING id",
            Long.class,id,snapshot,score,level,inputs);
        if(!"LOW".equals(level)) db.update("""
          INSERT INTO road_impacts(assessment_id,road_id,window_start,window_end)
          SELECT ?,r.id,CASE WHEN ?='HIGH' THEN now() ELSE NULL END,
            CASE WHEN ?='HIGH' THEN now()+interval '12 hours' ELSE NULL END
          FROM roads r JOIN sectors s ON ST_Intersects(r.geom,s.boundary)
          WHERE s.id=? AND r.vulnerability>=?
        """,assessment,level,level,id,roadThreshold);
        alerts.evaluate(id,(String)sector.get("name"),assessment,old,level,score);
        socket.publish(json.writeValueAsString(Map.of("sectorId",id,"score",score,"level",level,"assessedAt",OffsetDateTime.now(ZoneOffset.UTC).toString())));
        scoreTimer.stop(metrics.timer("floodguard_scoring_duration_seconds"));
    }

    private JsonNode fetch(URI uri) throws InterruptedException {
        Exception last=null;
        for(int attempt=0;attempt<3;attempt++) {
            try { return http.get().uri(uri).retrieve().body(JsonNode.class); }
            catch(Exception ex) { last=ex; if(attempt<2) Thread.sleep((1L<<attempt)*1000); }
        }
        throw new IllegalStateException("Open-Meteo failed after three attempts",last);
    }
}
