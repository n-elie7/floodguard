package org.floodguard;

import com.twilio.security.RequestValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TwilioWebhookVerifier {
    private final String authToken;
    private final String publicBaseUrl;

    TwilioWebhookVerifier(@Value("${floodguard.twilio-auth-token}") String authToken,
                          @Value("${floodguard.public-base-url}") String publicBaseUrl) {
        this.authToken = authToken;
        this.publicBaseUrl = publicBaseUrl;
    }

    boolean valid(HttpServletRequest request) {
        String signature = request.getHeader("X-Twilio-Signature");
        if (authToken.isBlank() || publicBaseUrl.isBlank() || signature == null || signature.isBlank()
            || request.getQueryString() != null) return false;
        Map<String,String> fields = new HashMap<>();
        for (var entry : request.getParameterMap().entrySet()) {
            if (entry.getValue().length != 1) return false;
            fields.put(entry.getKey(), entry.getValue()[0]);
        }
        String url = publicBaseUrl.replaceAll("/+$", "") + request.getRequestURI();
        return new RequestValidator(authToken).validate(url, fields, signature);
    }
}
