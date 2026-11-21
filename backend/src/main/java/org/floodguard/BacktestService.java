package org.floodguard;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BacktestService {
    private final JdbcTemplate db;
    private final RiskModel model;
    private final RestClient http=RestClient.create();
    BacktestService(JdbcTemplate db,RiskModel model){this.db=db;this.model=model;}

    public Map<String,Object> run(String id,LocalDate from,LocalDate to){
        if(to.isBefore(from)||to.isAfter(from.plusDays(31)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a range of up to 31 days");
        List<Map<String,Object>> sectors=db.queryForList("SELECT latitude,longitude,moderate_threshold,high_threshold FROM sectors WHERE id=?",id);
        if(sectors.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sector not found");
        Map<String,Object> sector=sectors.getFirst();
        String location="latitude="+sector.get("latitude")+"&longitude="+sector.get("longitude");
        JsonNode hourly=http.get().uri(URI.create("https://historical-forecast-api.open-meteo.com/v1/forecast?"+location+
            "&start_date="+from+"&end_date="+to+"&hourly=precipitation,soil_moisture_0_to_1cm&timezone=UTC")).retrieve().body(JsonNode.class);
        JsonNode daily=http.get().uri(URI.create("https://archive-api.open-meteo.com/v1/archive?"+location+
            "&start_date="+from.minusDays(30)+"&end_date="+to+"&daily=precipitation_sum&timezone=UTC")).retrieve().body(JsonNode.class);
        JsonNode times=hourly.path("hourly").path("time"),rain=hourly.path("hourly").path("precipitation"),
            soil=hourly.path("hourly").path("soil_moisture_0_to_1cm"),days=daily.path("daily").path("time"),
            sums=daily.path("daily").path("precipitation_sum");
        if(!times.isArray()||!rain.isArray()||!soil.isArray()||!days.isArray())
            throw new IllegalStateException("Incomplete historical response");
        List<Map<String,Object>> series=new ArrayList<>();
        for(int i=0;i+11<times.size();i++){
            if(!soil.get(i).isNumber())continue;
            LocalDate date=LocalDate.parse(times.get(i).asText().substring(0,10));
            double baseline=0;int count=0;
            for(int d=0;d<days.size();d++){
                LocalDate day=LocalDate.parse(days.get(d).asText());
                if(day.isBefore(date)&&!day.isBefore(date.minusDays(30))&&sums.get(d).isNumber()){
                    baseline+=sums.get(d).asDouble();count++;
                }
            }
            if(count<20)continue;
            double rainfall=0;
            for(int j=i;j<i+12;j++)if(rain.get(j).isNumber())rainfall+=rain.get(j).asDouble();
            int score=model.score(rainfall,soil.get(i).asDouble(),baseline/count);
            String level=model.level(score,((Number)sector.get("moderate_threshold")).intValue(),((Number)sector.get("high_threshold")).intValue());
            series.add(Map.of("time",times.get(i).asText(),"score",score,"level",level,"rainfall12hMm",rainfall));
        }
        return Map.of("sectorId",id,"from",from.toString(),"to",to.toString(),"series",series,
            "method","Hindcast using stitched historical forecasts and archive baseline; not a fixed-lead-time prediction validation");
    }
}
