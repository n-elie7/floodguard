package org.floodguard.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "roads")
@Getter
@Setter
@NoArgsConstructor
public class Road {
  @Id private String id;

  private String name;
  private String roadType;
  private Double vulnerability;
  private String vulnerabilitySource;
}
