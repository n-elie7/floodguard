package org.floodguard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Bootstrap implements CommandLineRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    @Value("${floodguard.admin-username}") String username;
    @Value("${floodguard.admin-password}") String password;
    @Value("${floodguard.phone-key}") String phoneKey;
    @Value("${floodguard.reset-admin-password:false}") boolean resetAdminPassword;

    Bootstrap(JdbcTemplate db, PasswordEncoder encoder) { this.db = db; this.encoder = encoder; }

    @Override public void run(String... args) {
        if (password.isBlank() || phoneKey.isBlank())
            throw new IllegalStateException("ADMIN_PASSWORD and PHONE_ENCRYPTION_KEY are required");
        db.update("INSERT INTO officer_accounts(username,password_hash) VALUES (?,?) ON CONFLICT (username) DO NOTHING",
            username, encoder.encode(password));
        if(resetAdminPassword) {
            db.update("UPDATE officer_accounts SET password_hash=?,must_change_password=true WHERE username=?",
                encoder.encode(password),username);
            System.out.println("Officer password reset from ADMIN_PASSWORD; change it on next sign-in.");
            System.exit(0);
        }
    }
}
