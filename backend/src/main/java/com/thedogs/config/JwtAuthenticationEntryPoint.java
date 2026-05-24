package com.thedogs.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Produces RFC 7807 JSON with errors[].code on 401 responses from the security filter chain. */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationEntryPoint.class);

  private final ObjectMapper objectMapper;

  public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    String code = resolveErrorCode(authException);
    String detail = resolveDetail(code);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "https://api.thedogs.app/problems/" + codeToPath(code));
    body.put("title", "Unauthorized");
    body.put("status", HttpStatus.UNAUTHORIZED.value());
    body.put("detail", detail);
    body.put("errors", List.of(Map.of("code", code)));

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), body);
  }

  private String resolveErrorCode(AuthenticationException ex) {
    // Log the full exception chain at DEBUG level for troubleshooting auth failures.
    // This is safe because we never log the token value itself (only class names and
    // sanitised messages). The token is never part of an AuthenticationException message in
    // Spring Security's OAuth2 resource server implementation.
    if (log.isDebugEnabled()) {
      log.debug("Auth exception type={} msg={}", ex.getClass().getName(), ex.getMessage());
      Throwable c = ex.getCause();
      while (c != null) {
        log.debug("  cause type={} msg={}", c.getClass().getName(), c.getMessage());
        c = c.getCause();
      }
    }
    // Walk the cause chain for expiry signals (covers both JJWT and Nimbus paths)
    Throwable cursor = ex;
    while (cursor != null) {
      String msg = cursor.getMessage();
      if (msg != null) {
        // Nimbus: "Jwt expired at ...", Spring Security: "An error occurred while attempting to
        // decode the Jwt: Jwt expired at ..."
        if (msg.contains("expired") || msg.contains("Expired") || msg.contains("EXPIRED")) {
          return "token_expired";
        }
      }
      // JJWT ExpiredJwtException class name check (avoids hard dependency on jjwt in config)
      if (cursor.getClass().getSimpleName().contains("ExpiredJwt")) {
        return "token_expired";
      }
      cursor = cursor.getCause();
    }
    // Any bearer-token-level authentication failure that is not expiry → invalid_token
    if (ex instanceof InvalidBearerTokenException) {
      return "invalid_token";
    }
    String msg = ex.getMessage();
    if (msg != null
        && (msg.contains("JWT")
            || msg.contains("token")
            || msg.contains("Bearer")
            || msg.contains("signature"))) {
      return "invalid_token";
    }
    return "unauthorized";
  }

  private String resolveDetail(String code) {
    return switch (code) {
      case "token_expired" -> "The access token has expired. Please refresh.";
      case "invalid_token" -> "The access token is invalid or has an invalid signature.";
      default -> "Authentication is required to access this resource.";
    };
  }

  private String codeToPath(String code) {
    return code.replace('_', '-');
  }
}
