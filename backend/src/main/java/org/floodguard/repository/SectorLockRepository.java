package org.floodguard.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Holds a PostgreSQL session lock during one sector's ingest cycle. */
@Repository
@RequiredArgsConstructor
public class SectorLockRepository {
  private final DataSource dataSource;

  @FunctionalInterface
  public interface SectorWork {
    void run() throws Exception;
  }

  public boolean runIfAvailable(String sectorId, SectorWork work) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      if (!tryLock(connection, sectorId)) {
        return false;
      }
      try {
        work.run();
        return true;
      } finally {
        unlock(connection, sectorId);
      }
    }
  }

  private boolean tryLock(Connection connection, String sectorId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_lock(447411, hashtext(?))")) {
      statement.setString(1, sectorId);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private void unlock(Connection connection, String sectorId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_unlock(447411, hashtext(?))")) {
      statement.setString(1, sectorId);
      statement.execute();
    }
  }
}
