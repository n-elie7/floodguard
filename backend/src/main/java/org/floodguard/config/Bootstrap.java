package org.floodguard.config;

import lombok.RequiredArgsConstructor;
import org.floodguard.model.OfficerAccount;
import org.floodguard.repository.OfficerAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class Bootstrap implements CommandLineRunner {
  private final OfficerAccountRepository accounts;
  private final PasswordEncoder encoder;

  @Value("${floodguard.admin-username}")
  private String username;

  @Value("${floodguard.admin-password}")
  private String password;

  @Value("${floodguard.phone-key}")
  private String phoneKey;

  @Value("${floodguard.reset-admin-password:false}")
  private boolean resetAdminPassword;

  @Override
  public void run(String... args) {
    if (password.isBlank() || phoneKey.isBlank())
      throw new IllegalStateException("ADMIN_PASSWORD and PHONE_ENCRYPTION_KEY are required");
    accounts.insertIfAbsent(username, encoder.encode(password));
    if (resetAdminPassword) {
      OfficerAccount account = accounts.findById(username).orElseThrow();
      account.setPasswordHash(encoder.encode(password));
      account.setMustChangePassword(true);
      accounts.save(account);
      System.out.println("Officer password reset from ADMIN_PASSWORD; change it on next sign-in.");
      System.exit(0);
    }
  }
}
