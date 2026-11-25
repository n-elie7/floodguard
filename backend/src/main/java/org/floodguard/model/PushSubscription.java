package org.floodguard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {
  @Id
  @Column(columnDefinition = "text")
  private String endpoint;

  @Column(name = "sector_id", nullable = false)
  private String sectorId;

  @Column(nullable = false)
  private String p256dh;

  @Column(name = "auth_secret", nullable = false)
  private String authSecret;

  @Column(nullable = false)
  private boolean active = true;
}
