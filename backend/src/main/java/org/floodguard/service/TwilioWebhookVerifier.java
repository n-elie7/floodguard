package org.floodguard.service;

import com.twilio.security.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TwilioWebhookVerifier {
  @Value("${floodguard.twilio-auth-token}")
  private final String authToken;

  @Value("${floodguard.public-base-url}")
  private final String publicBaseUrl;

  public boolean valid(HttpServletRequest request) {
    String signature = request.getHeader("X-Twilio-Signature");
    if (authToken.isBlank()
        || publicBaseUrl.isBlank()
        || signature == null
        || signature.isBlank()
        || request.getQueryString() != null) return false;
    Map<String, String> fields = new HashMap<>();
    for (var entry : request.getParameterMap().entrySet()) {
      if (entry.getValue().length != 1) {
        return false;
      }
      fields.put(entry.getKey(), entry.getValue()[0]);
    }
    String url = publicBaseUrl.replaceAll("/+$", "") + request.getRequestURI();
    return new RequestValidator(authToken).validate(url, fields, signature);
  }
}
