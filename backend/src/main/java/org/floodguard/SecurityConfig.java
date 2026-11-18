package org.floodguard;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService users(JdbcTemplate db) {
        return name -> db.query("SELECT password_hash FROM officer_accounts WHERE username=?", rs -> {
            if (!rs.next()) throw new org.springframework.security.core.userdetails.UsernameNotFoundException(name);
            return User.withUsername(name).password(rs.getString(1)).roles("OFFICER").build();
        }, name);
    }

    @Bean SecurityFilterChain filterChain(HttpSecurity http,JdbcTemplate db) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/officer/session", "/api/v1/officer/password").authenticated()
                .requestMatchers("/api/v1/admin/**", "/api/v1/officer/**").access((authentication,context)->{
                    var account=authentication.get();
                    if(!account.isAuthenticated()||"anonymousUser".equals(account.getName()))return new AuthorizationDecision(false);
                    Boolean mustChange=db.queryForObject("SELECT must_change_password FROM officer_accounts WHERE username=?",Boolean.class,account.getName());
                    return new AuthorizationDecision(Boolean.FALSE.equals(mustChange));
                })
                .anyRequest().permitAll())
            .httpBasic(org.springframework.security.config.Customizer.withDefaults());
        return http.build();
    }

    private UrlBasedCorsConfigurationSource corsSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("http://localhost:5173", "http://localhost:8080"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
