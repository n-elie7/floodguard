package org.floodguard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "sectors")
@Getter
@Setter
@NoArgsConstructor
public class Sector {
  @Id private String id;

  private String name;
  private String district;
  private double latitude;
  private double longitude;

  @Column(name = "moderate_threshold")
  private int moderateThreshold;

  @Column(name = "high_threshold")
  private int highThreshold;

  @Column(name = "updated_at")
  private OffsetDateTime updatedAt;
}
