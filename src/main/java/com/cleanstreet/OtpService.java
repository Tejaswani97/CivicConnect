package com.cleanstreet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phone verification providers:
 *   dev   = fixed code 123456 for local/offline demos
 *   twilio = Twilio Verify SMS
 *   wakit = real WhatsApp OTP via Wakit's REST API
 *
 * Keep provider credentials in environment variables, never in source control.
 */
@Service
public class OtpService {
  @Value("${app.otp.provider:dev}") String provider;
  @Value("${TWILIO_ACCOUNT_SID:}") String sid;
  @Value("${TWILIO_AUTH_TOKEN:}") String token;
  @Value("${TWILIO_VERIFY_SERVICE_SID:}") String service;
  @Value("${WAKIT_API_KEY:}") String wakitApiKey;
  @Value("${app.otp.cooldown-seconds:30}") int cooldown;
  @Value("${app.otp.max-attempts:5}") int maxAttempts;

  private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
  private final Map<String, Integer> attempts = new ConcurrentHashMap<>();
  private final HttpClient http = HttpClient.newHttpClient();
  private final ObjectMapper json = new ObjectMapper();

  /**
   * Starts an OTP request. For Wakit this returns the provider message id,
   * which must be supplied again when verifying the code.
   */
  public String start(String phone) {
    Instant last = lastSent.get(phone);
    if (last != null && Instant.now().isBefore(last.plusSeconds(cooldown))) {
      long wait = Math.max(1, last.plusSeconds(cooldown).getEpochSecond() - Instant.now().getEpochSecond());
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Please wait " + wait + " seconds before requesting another code");
    }

    String requestId;
    if ("dev".equalsIgnoreCase(provider)) {
      requestId = null;
    } else if ("wakit".equalsIgnoreCase(provider)) {
      requestId = wakitStart(phone);
    } else if ("twilio".equalsIgnoreCase(provider)) {
      callTwilio("/Verifications", "To=" + enc(phone) + "&Channel=sms");
      requestId = null;
    } else {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "Unknown OTP provider: " + provider);
    }

    lastSent.put(phone, Instant.now());
    attempts.remove(phone);
    return requestId;
  }

  public void check(String phone, String code, String requestId) {
    int used = attempts.merge(phone, 1, Integer::sum);
    if (used > maxAttempts) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
          "Too many incorrect attempts. Request a new code.");
    }

    boolean ok;
    if ("dev".equalsIgnoreCase(provider)) {
      ok = "123456".equals(code);
    } else if ("wakit".equalsIgnoreCase(provider)) {
      ok = wakitVerify(requestId, code);
    } else if ("twilio".equalsIgnoreCase(provider)) {
      ok = "approved".equals(callTwilio("/VerificationCheck",
          "To=" + enc(phone) + "&Code=" + enc(code)));
    } else {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
          "Unknown OTP provider: " + provider);
    }

    if (!ok) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Incorrect or expired code");
    }
    attempts.remove(phone);
  }

  private String wakitStart(String phone) {
    requireWakitKey();
    try {
      String body = json.writeValueAsString(Map.of("to", phone, "code_length", 6));
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://wakit.in/api/v1/otp/send"))
          .header("Authorization", "Bearer " + wakitApiKey.trim())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(), HttpResponse.BodyHandlers.ofString());

      if (r.statusCode() == 401 || r.statusCode() == 403)
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Wakit API key is invalid or not authorized");
      if (r.statusCode() == 429)
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "WhatsApp OTP limit reached. Try again later.");
      if (r.statusCode() >= 400)
        throw providerError(r.body(), "Could not send the WhatsApp verification code");

      JsonNode root = json.readTree(r.body());
      String id = root.path("data").path("id").asText("");
      if (!root.path("success").asBoolean(false) || id.isBlank())
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "WhatsApp provider returned an invalid OTP response");
      return id;
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "WhatsApp OTP provider is unavailable. Try again shortly.");
    }
  }

  private boolean wakitVerify(String requestId, String code) {
    requireWakitKey();
    if (requestId == null || requestId.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification session expired. Request a new code.");
    try {
      String body = json.writeValueAsString(Map.of("id", requestId, "code", code));
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://wakit.in/api/v1/otp/verify"))
          .header("Authorization", "Bearer " + wakitApiKey.trim())
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build(), HttpResponse.BodyHandlers.ofString());

      if (r.statusCode() == 401 || r.statusCode() == 403)
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Wakit API key is invalid or not authorized");
      if (r.statusCode() == 429)
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many OTP verification attempts. Try again later.");
      if (r.statusCode() >= 400)
        return false;

      JsonNode root = json.readTree(r.body());
      return root.path("success").asBoolean(false) && root.path("data").path("verified").asBoolean(false);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "WhatsApp OTP provider is unavailable. Try again shortly.");
    }
  }

  private void requireWakitKey() {
    if (wakitApiKey == null || wakitApiKey.isBlank())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "WhatsApp OTP is not configured. Set WAKIT_API_KEY or use OTP_PROVIDER=dev.");
  }

  private ResponseStatusException providerError(String body, String fallback) {
    try {
      JsonNode root = json.readTree(body);
      String message = root.path("error").path("message").asText("");
      if (message.isBlank()) message = root.path("message").asText("");
      if (!message.isBlank()) return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    } catch (Exception ignored) { }
    return new ResponseStatusException(HttpStatus.BAD_GATEWAY, fallback);
  }

  /** Returns the Twilio status string; maps provider failures to user-friendly errors. */
  private String callTwilio(String path, String form) {
    if (sid.isBlank() || token.isBlank() || service.isBlank())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SMS verification is not configured on this server");
    try {
      String auth = Base64.getEncoder().encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8));
      HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://verify.twilio.com/v2/Services/" + service + path))
          .header("Authorization", "Basic " + auth).header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() == 404) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Code expired. Request a new one.");
      if (r.statusCode() == 429) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
      if (r.statusCode() >= 400) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not send the SMS. Check the number and try again.");
      return json.readTree(r.body()).path("status").asText();
    } catch (ResponseStatusException e) { throw e;
    } catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SMS provider is unavailable. Try again shortly."); }
  }

  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
