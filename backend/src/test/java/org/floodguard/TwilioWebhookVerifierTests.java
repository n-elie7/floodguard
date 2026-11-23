package org.floodguard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class TwilioWebhookVerifierTests {
    private static final String URL = "https://alerts.example.org/api/v1/twilio/inbound";

    @Test
    void acceptsSignedFormAndRejectsTampering() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/twilio/inbound");
        request.addParameter("From", "+250788123456");
        request.addParameter("Body", "STOP");
        request.addHeader("X-Twilio-Signature", sign("BodySTOPFrom+250788123456"));
        var verifier = new TwilioWebhookVerifier("test-secret", "https://alerts.example.org");
        assertTrue(verifier.valid(request));
        request.setParameter("Body", "START");
        assertFalse(verifier.valid(request));
    }

    @Test
    void rejectsUnsignedAndUnconfiguredCallbacks() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/twilio/inbound");
        request.addParameter("From", "+250788123456");
        request.addParameter("Body", "STOP");
        assertFalse(new TwilioWebhookVerifier("test-secret", "https://alerts.example.org").valid(request));
        request.addHeader("X-Twilio-Signature", sign("BodySTOPFrom+250788123456"));
        assertFalse(new TwilioWebhookVerifier("", "https://alerts.example.org").valid(request));
        request.setQueryString("extra=1");
        assertFalse(new TwilioWebhookVerifier("test-secret", "https://alerts.example.org").valid(request));
    }

    private static String sign(String fields) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal((URL + fields).getBytes(StandardCharsets.UTF_8)));
    }
}
