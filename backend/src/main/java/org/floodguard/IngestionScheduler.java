package org.floodguard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="floodguard.worker-enabled",havingValue="true")
public class IngestionScheduler {
    private final IngestionService ingestion;
    IngestionScheduler(IngestionService ingestion){this.ingestion=ingestion;}
    @Scheduled(fixedDelay=3600000,initialDelay=60000)
    public void run(){ingestion.runCycle();}
}
