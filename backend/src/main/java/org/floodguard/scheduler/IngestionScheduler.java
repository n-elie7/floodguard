package org.floodguard.scheduler;

import lombok.RequiredArgsConstructor;
import org.floodguard.repository.SectorRepository;
import org.floodguard.service.GeoImporter;
import org.floodguard.service.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "floodguard.worker-enabled", havingValue = "true")
@RequiredArgsConstructor
public class IngestionScheduler {
  private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);
  private final IngestionService ingestion;
  private final GeoImporter importer;
  private final SectorRepository sectors;

  @Scheduled(fixedDelay = 3600000, initialDelay = 15000)
  public void run() {
    try {
      long count = sectors.count();
      if (count == 0) {
        int imported = importer.importKigaliSectors();
        log.info("Imported {} Kigali sectors into an empty database", imported);
      }
      ingestion.runCycle();
    } catch (Exception ex) {
      log.error("Scheduled sector import or weather ingestion failed; retrying next cycle", ex);
    }
  }
}
