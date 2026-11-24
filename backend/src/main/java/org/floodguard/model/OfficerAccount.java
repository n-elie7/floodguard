package org.floodguard.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "officer_accounts")
@Getter
@Setter
@NoArgsConstructor
public class OfficerAccount {
  @Id private String username;
  private String passwordHash;
  private boolean mustChangePassword;
}
