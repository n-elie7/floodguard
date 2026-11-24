package org.floodguard.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.floodguard.dto.Subscriber;
import org.floodguard.repository.AlertRepository;
import org.floodguard.repository.SectorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class AlertService {
  private final AlertRepository alertRepository;
  private final SectorRepository sectors;
  private final JavaMailSender mail;
  private final MeterRegistry metrics;
  private final SecureRandom random = new SecureRandom();
  private final RestClient http = RestClient.create();

  @Value("${floodguard.phone-key}")
  private String key;

  @Value("${floodguard.sms-enabled}")
  private boolean smsEnabled;

  @Value("${floodguard.twilio-account-sid}")
  private String sid;

  @Value("${floodguard.twilio-auth-token}")
  private String token;

  @Value("${floodguard.twilio-from}")
  private String from;

  @Value("${floodguard.public-base-url}")
  private String publicBaseUrl;

  @Value("${floodguard.smtp-from}")
  private String smtpFrom;

  @Value("${floodguard.officer-email}")
  private String officerEmail;

  @Value("${floodguard.admin-email}")
  private String adminEmail;

  public Map<String, Object> register(String sectorId, String phone) {
    if (sectorId == null || phone == null || !phone.matches("\\+[1-9][0-9]{7,14}"))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "Use an international phone number such as +2507...");
    if (!sectors.existsById(sectorId))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown sector");
    String hash = hash(phone);
    String encrypted = encrypt(phone);
    alertRepository.upsertSubscription(sectorId, hash, encrypted);
    String name = sectors.findById(sectorId).orElseThrow().getName();
    send(
        phone,
        "FloodGuard: alerts on for "
            + name
            + ". Reply STOP to leave. Forecast only; follow REMA advice.");
    return Map.of(
        "sectorId", sectorId, "registered", true, "deliveryMode", smsEnabled ? "live" : "disabled");
  }

  public void unsubscribe(String phone) {
    alertRepository.unsubscribe(hash(phone));
  }

  public void updateStatus(String providerSid, String status) {
    alertRepository.updateDeliveryStatus(providerSid, status);
  }

  public void evaluate(
      String sectorId,
      String sectorName,
      long assessmentId,
      String previous,
      String level,
      int score) {
    if ("HIGH".equals(level) && !"HIGH".equals(previous)) {
      String road = alertRepository.highestRiskRoad(assessmentId);
      String shelter = alertRepository.nearestVerifiedShelter(sectorId);
      String message = compose(sectorName, road, shelter);
      long alertId = alertRepository.createEvent(sectorId, assessmentId, level, message);
      int count = 0;
      for (Subscriber subscriber : alertRepository.activeSubscribers(sectorId)) {
        long subId = subscriber.id();
        // A downward transition rearms alerts; otherwise suppress repeat within four hours.
        if (previous != null && !"LOW".equals(previous) && !"MODERATE".equals(previous)) {
          continue;
        }
        if (previous == null && alertRepository.deliveredRecently(subId, sectorId)) {
          continue;
        }
        try {
          String providerId = send(decrypt(subscriber.phoneCiphertext()), message);
          alertRepository.recordDelivery(
              alertId, subId, providerId, smsEnabled ? "pending" : "disabled");
          if (smsEnabled) {
            count++;
          }
        } catch (Exception ex) {
          alertRepository.recordFailure(alertId, subId, ex.getMessage());
          metrics.counter("floodguard_sms_delivery_total", "status", "failed").increment();
        }
      }
      alertRepository.updateSentCount(alertId, count);
    } else if ("MODERATE".equals(level) && "LOW".equals(previous)) {
      alertRepository.createEvent(sectorId, assessmentId, level, "Moderate risk advisory");
      email(
          officerEmail,
          "FloodGuard moderate risk: " + sectorName,
          "Risk index " + score + ". Review the dashboard. Forecast only; follow REMA advice.");
    }
  }

  private String compose(String sector, String road, String shelter) {
    String suffix = " Move to safety. Forecast only; follow REMA advice.";
    String prefix = "FloodGuard HIGH: " + sector + ".";
    String detail =
        (road == null ? " Check local roads." : " Avoid " + road + ".")
            + (shelter == null ? " Check official shelter advice." : " Shelter: " + shelter + ".");
    if ((prefix + detail + suffix).length() > 160)
      detail = shelter == null ? "" : " Shelter: " + shelter + ".";
    if ((prefix + detail + suffix).length() > 160) {
      detail = "";
    }
    if ((prefix + suffix).length() > 160)
      prefix = prefix.substring(0, 160 - suffix.length() - 3) + "...";
    return prefix + detail + suffix;
  }

  private String send(String to, String body) {
    if (!smsEnabled) {
      return null;
    }
    if (sid.isBlank() || token.isBlank() || from.isBlank())
      throw new IllegalStateException("Twilio is not configured");
    if (!publicBaseUrl.startsWith("https://") || publicBaseUrl.contains("localhost"))
      throw new IllegalStateException(
          "Live SMS requires a public HTTPS base URL for signed callbacks");
    var form = new LinkedMultiValueMap<String, String>();
    form.add("To", to);
    form.add("From", from);
    form.add("Body", body);
    form.add("StatusCallback", publicBaseUrl.replaceAll("/+$", "") + "/api/v1/twilio/status");
    JsonNode response =
        http.post()
            .uri("https://api.twilio.com/2010-04-01/Accounts/" + sid + "/Messages.json")
            .header(
                HttpHeaders.AUTHORIZATION,
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8)))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(JsonNode.class);
    metrics.counter("floodguard_sms_delivery_total", "status", "submitted").increment();
    return response == null ? null : response.path("sid").asText(null);
  }

  public void reportIngestionFailure(String sector, Exception error) {
    email(adminEmail, "FloodGuard ingestion failed: " + sector, error.toString());
  }

  private void email(String recipient, String subject, String body) {
    if (recipient == null || recipient.isBlank()) {
      return;
    }
    try {
      SimpleMailMessage message = new SimpleMailMessage();
      if (smtpFrom != null && !smtpFrom.isBlank()) {
        message.setFrom(smtpFrom);
      }
      message.setTo(recipient);
      message.setSubject(subject);
      message.setText(body);
      mail.send(message);
    } catch (Exception ignored) {
      metrics.counter("floodguard_email_failures_total").increment();
    }
  }

  private byte[] secret() {
    byte[] raw = Base64.getDecoder().decode(key);
    if (raw.length != 32)
      throw new IllegalStateException("PHONE_ENCRYPTION_KEY must be a base64 encoded 32-byte key");
    return raw;
  }

  private String hash(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret(), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String encrypt(String value) {
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE, new SecretKeySpec(secret(), "AES"), new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] output = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, output, 0, iv.length);
      System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
      return Base64.getEncoder().encodeToString(output);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String decrypt(String value) {
    try {
      byte[] input = Base64.getDecoder().decode(value);
      byte[] iv = java.util.Arrays.copyOfRange(input, 0, 12);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, new SecretKeySpec(secret(), "AES"), new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(input, 12, input.length - 12), StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }
}
