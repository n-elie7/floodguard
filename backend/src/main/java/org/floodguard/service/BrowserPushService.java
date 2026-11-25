package org.floodguard.service;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.security.Security;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.floodguard.dto.PushRegistration;
import org.floodguard.model.PushSubscription;
import org.floodguard.repository.PushSubscriptionRepository;
import org.floodguard.repository.SectorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrowserPushService {
  private final PushSubscriptionRepository subscriptions;
  private final SectorRepository sectors;
  private final ObjectMapper json;
  private final MeterRegistry metrics;

  @Value("${floodguard.vapid-public-key}")
  private String publicKey;

  @Value("${floodguard.vapid-private-key}")
  private String privateKey;

  @Value("${floodguard.public-base-url}")
  private String publicBaseUrl;

  private PushService push;

  @PostConstruct
  void initialize() {
    if (publicKey.isBlank() && privateKey.isBlank()) {
      return;
    }
    if (publicKey.isBlank() || privateKey.isBlank()) {
      throw new IllegalStateException("Both VAPID keys are required for browser alerts");
    }
    try {
      if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
        Security.addProvider(new BouncyCastleProvider());
      }
      push = new PushService(publicKey, privateKey, publicBaseUrl);
    } catch (Exception error) {
      throw new IllegalStateException("Invalid VAPID key pair", error);
    }
  }

  public Map<String, Object> configuration() {
    return Map.of("enabled", push != null, "publicKey", push == null ? "" : publicKey);
  }

  @Transactional
  public void subscribe(PushRegistration request) {
    requireConfigured();
    if (request == null
        || request.sectorId() == null
        || !sectors.existsById(request.sectorId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid sector");
    }
    validateEndpoint(request.endpoint());
    String p256dh = request.keys() == null ? null : request.keys().get("p256dh");
    String auth = request.keys() == null ? null : request.keys().get("auth");
    if (!validKey(p256dh, 65) || !validKey(auth, 16)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid browser push keys");
    }

    PushSubscription subscription =
        subscriptions.findById(request.endpoint()).orElseGet(PushSubscription::new);
    subscription.setEndpoint(request.endpoint());
    subscription.setSectorId(request.sectorId());
    subscription.setP256dh(p256dh);
    subscription.setAuthSecret(auth);
    subscription.setActive(true);
    subscriptions.save(subscription);

    String sectorName = sectors.findById(request.sectorId()).orElseThrow().getName();
    try {
      deliver(subscription, "FloodGuard alerts enabled", "Browser alerts are on for " + sectorName + ".");
    } catch (Exception error) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "Browser push service rejected the test notification", error);
    }
  }

  @Transactional
  public void unsubscribe(String endpoint) {
    validateEndpoint(endpoint);
    subscriptions.deleteById(endpoint);
  }

  public void sendHighRisk(String sectorId, String sectorName, String message) {
    if (push == null) {
      return;
    }
    for (PushSubscription subscription : subscriptions.findBySectorIdAndActiveTrue(sectorId)) {
      try {
        deliver(subscription, "FloodGuard HIGH: " + sectorName, message);
      } catch (Exception error) {
        log.warn("Browser alert failed for sector {}", sectorId, error);
        metrics.counter("floodguard_push_delivery_total", "status", "failed").increment();
      }
    }
  }

  private void deliver(PushSubscription subscription, String title, String body) throws Exception {
    String payload = json.writeValueAsString(Map.of("title", title, "body", body, "url", "/"));
    Notification notification =
        new Notification(
            subscription.getEndpoint(),
            subscription.getP256dh(),
            subscription.getAuthSecret(),
            payload);
    int status = push.send(notification).getStatusLine().getStatusCode();
    if (status == 404 || status == 410) {
      subscriptions.deleteById(subscription.getEndpoint());
    }
    if (status < 200 || status >= 300) {
      throw new IllegalStateException("Push service returned HTTP " + status);
    }
    metrics.counter("floodguard_push_delivery_total", "status", "accepted").increment();
  }

  private void requireConfigured() {
    if (push == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Browser alerts are unavailable");
    }
  }

  private void validateEndpoint(String endpoint) {
    if (endpoint == null || endpoint.length() > 2048) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push endpoint");
    }
    try {
      URI uri = URI.create(endpoint);
      String host = uri.getHost();
      boolean trusted =
          host != null
              && (host.equals("fcm.googleapis.com")
                  || host.equals("updates.push.services.mozilla.com")
                  || host.equals("web.push.apple.com")
                  || host.endsWith(".notify.windows.com"));
      if (!"https".equals(uri.getScheme()) || uri.getPort() != -1 || !trusted) {
        throw new IllegalArgumentException("Unsupported push service");
      }
    } catch (IllegalArgumentException error) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid push endpoint");
    }
  }

  private boolean validKey(String encoded, int expectedLength) {
    if (encoded == null || encoded.length() > 128) {
      return false;
    }
    try {
      return Base64.getUrlDecoder().decode(encoded).length == expectedLength;
    } catch (IllegalArgumentException error) {
      return false;
    }
  }
}
