package org.floodguard.repository;

import java.util.List;
import org.floodguard.model.Sector;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectorRepository extends JpaRepository<Sector, String> {
  List<Sector> findAllByOrderByDistrictAscNameAsc();
}
