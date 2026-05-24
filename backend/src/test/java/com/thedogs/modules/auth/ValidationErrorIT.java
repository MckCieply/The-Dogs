package com.thedogs.modules.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for AC-6 of AUTH-01: validation errors on POST /api/v1/auth/login carry an
 * {@code errors[].code} field in the problem+json response body.
 *
 * <p>Uses the TC JDBC URL from application-test.yml (Docker required). Named *IT so Maven Failsafe
 * picks it up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ValidationErrorIT {

  @Autowired private MockMvc mockMvc;

  // ---------------------------------------------------------------------------
  // AC-6: missing email field → 400 with errors[0].code = "field_required"
  // ---------------------------------------------------------------------------

  @Test
  void login_withMissingEmail_returns400() throws Exception {
    // JSON body omits the "email" field entirely — only "password" is supplied
    String body = "{\"password\":\"some-password\"}";

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  @Test
  void login_withMissingEmail_responseContentTypeIsProblemJson() throws Exception {
    String body = "{\"password\":\"some-password\"}";

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void login_withMissingEmail_errorsArrayContainsFieldRequiredCode() throws Exception {
    String body = "{\"password\":\"some-password\"}";

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[0].code").value("field_required"));
  }

  @Test
  void login_withMissingEmail_errorsArrayContainsFieldName() throws Exception {
    String body = "{\"password\":\"some-password\"}";

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("email"));
  }

  // ---------------------------------------------------------------------------
  // AC-6: missing password field → 400 with errors[0].code = "field_required"
  // ---------------------------------------------------------------------------

  @Test
  void login_withMissingPassword_returns400WithFieldRequiredCode() throws Exception {
    String body = "{\"email\":\"valid@example.com\"}";

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[0].code").value("field_required"));
  }

  // ---------------------------------------------------------------------------
  // AC-6: blank email value (not omitted, but empty string) → 400 field_required
  // ---------------------------------------------------------------------------

  @Test
  void login_withBlankEmail_returns400WithFieldRequiredCode() throws Exception {
    String body = "{\"email\":\"\",\"password\":\"some-password\"}";

    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[0].code").value("field_required"));
  }
}
