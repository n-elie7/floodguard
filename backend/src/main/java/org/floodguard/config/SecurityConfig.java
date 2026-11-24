package org.floodguard.config;

import java.util.List;
import org.floodguard.repository.OfficerAccountRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(OfficerAccountRepository accounts) {
    return name ->
        accounts
            .findById(name)
            .map(
                account ->
                    User.withUsername(name)
                        .password(account.getPasswordHash())
                        .roles("OFFICER")
                        .build())
            .orElseThrow(
                () ->
                    new org.springframework.security.core.userdetails.UsernameNotFoundException(
                        name));
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, OfficerAccountRepository accounts)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsSource()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/v1/officer/session", "/api/v1/officer/password")
                    .authenticated()
                    .requestMatchers("/api/v1/admin/**", "/api/v1/officer/**")
                    .access(
                        (authentication, context) -> {
                          var account = authentication.get();
                          if (!account.isAuthenticated()
                              || "anonymousUser".equals(account.getName()))
                            return new AuthorizationDecision(false);
                          boolean mayContinue =
                              accounts
                                  .findById(account.getName())
                                  .map(officer -> !officer.isMustChangePassword())
                                  .orElse(false);
                          return new AuthorizationDecision(mayContinue);
                        })
                    .anyRequest()
                    .permitAll())
        .httpBasic(
            basic ->
                basic.authenticationEntryPoint(
                    (request, response, error) -> {
                      response.setStatus(401);
                      response.setContentType("application/json");
                      response.getWriter().write("{\"error\":\"Officer sign-in required\"}");
                    }));
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
