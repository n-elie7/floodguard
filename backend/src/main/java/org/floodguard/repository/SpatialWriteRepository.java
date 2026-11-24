package org.floodguard.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Native PostGIS writes whose geometry conversion is not represented by the JPA entities. */
@Repository
@RequiredArgsConstructor
public class SpatialWriteRepository {
  private final JdbcTemplate jdbc;

  public int upsertRoad(String id, String name, String type, String geoJson) {
    return jdbc.update(
        """
        INSERT INTO roads(id, name, road_type, geom)
        SELECT ?, ?, ?, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))
        WHERE EXISTS (
          SELECT 1 FROM sectors
          WHERE ST_Intersects(boundary, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))
        )
        ON CONFLICT (id) DO UPDATE SET
          name = excluded.name, road_type = excluded.road_type, geom = excluded.geom
        """,
        id,
        name,
        type,
        geoJson,
        geoJson);
  }

  public long insertShelter(
      String sectorId,
      String name,
      double longitude,
      double latitude,
      boolean verified,
      String source) {
    return jdbc.queryForObject(
        """
        INSERT INTO shelters(sector_id, name, location, verified, source)
        VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?)
        RETURNING id
        """,
        Long.class,
        sectorId,
        name,
        longitude,
        latitude,
        verified,
        source);
  }

  public void upsertSector(String id, String name, String district, String geoJson) {
    jdbc.update(
        """
        INSERT INTO sectors(id, name, district, boundary, latitude, longitude)
        SELECT ?, ?, ?, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)),
          ST_Y(ST_PointOnSurface(ST_GeomFromGeoJSON(?))),
          ST_X(ST_PointOnSurface(ST_GeomFromGeoJSON(?)))
        ON CONFLICT (id) DO UPDATE SET
          name = excluded.name, district = excluded.district,
          boundary = excluded.boundary, latitude = excluded.latitude,
          longitude = excluded.longitude, updated_at = now()
        """,
        id,
        name,
        district,
        geoJson,
        geoJson,
        geoJson);
  }
}
