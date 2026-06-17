package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for IpAddressExtractor (AUTH-02).
 *
 * <p>No Spring context, no DB, no Docker required. Each test mocks only the header(s) relevant to
 * the branch under test.
 */
class IpAddressExtractorTest {

  // ---------------------------------------------------------------------------
  // CF-Connecting-IP is highest-priority — present → return that value
  // ---------------------------------------------------------------------------

  @Test
  void extract_withCfConnectingIpPresent_returnsCfConnectingIpValue() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.1");
    when(request.getHeader("X-Forwarded-For")).thenReturn(null);
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    assertThat(IpAddressExtractor.extract(request)).isEqualTo("203.0.113.1");
  }

  @Test
  void extract_withCfConnectingIpPresentAndXForwardedForAlsoPresent_returnsCfConnectingIp() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.5");
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1, 198.51.100.2");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    // CF-Connecting-IP must win over X-Forwarded-For
    assertThat(IpAddressExtractor.extract(request)).isEqualTo("203.0.113.5");
  }

  @Test
  void extract_withCfConnectingIpPresentWithLeadingAndTrailingSpaces_returnsTrimmedValue() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn("  203.0.113.9  ");
    when(request.getHeader("X-Forwarded-For")).thenReturn(null);
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    assertThat(IpAddressExtractor.extract(request)).isEqualTo("203.0.113.9");
  }

  // ---------------------------------------------------------------------------
  // CF-Connecting-IP absent, X-Forwarded-For present → return leftmost entry
  // ---------------------------------------------------------------------------

  @Test
  void extract_withCfAbsentAndXForwardedForPresent_returnsLeftmostEntry() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn(null);
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.42");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    assertThat(IpAddressExtractor.extract(request)).isEqualTo("198.51.100.42");
  }

  @Test
  void extract_withCfAbsentAndXForwardedForWithMultipleIps_returnsLeftmostIp() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn(null);
    when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8, 9.10.11.12");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    assertThat(IpAddressExtractor.extract(request)).isEqualTo("1.2.3.4");
  }

  @Test
  void extract_withCfAbsentAndXForwardedForWithTwoIpsAndSpaces_returnsFirstTrimmed() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn(null);
    when(request.getHeader("X-Forwarded-For")).thenReturn("  1.2.3.4 , 5.6.7.8");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    assertThat(IpAddressExtractor.extract(request)).isEqualTo("1.2.3.4");
  }

  @Test
  void extract_withCfBlankAndXForwardedForPresent_returnsXForwardedForLeftmost() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn("   "); // blank, not null
    when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 198.51.100.8");
    when(request.getRemoteAddr()).thenReturn("10.0.0.1");

    // Blank CF-Connecting-IP must be treated as absent
    assertThat(IpAddressExtractor.extract(request)).isEqualTo("198.51.100.7");
  }

  // ---------------------------------------------------------------------------
  // Both absent → fall through to remoteAddr
  // ---------------------------------------------------------------------------

  @Test
  void extract_withBothHeadersAbsent_returnsRemoteAddr() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn(null);
    when(request.getHeader("X-Forwarded-For")).thenReturn(null);
    when(request.getRemoteAddr()).thenReturn("192.168.1.100");

    assertThat(IpAddressExtractor.extract(request)).isEqualTo("192.168.1.100");
  }

  @Test
  void extract_withBothHeadersBlank_returnsRemoteAddr() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("CF-Connecting-IP")).thenReturn("");
    when(request.getHeader("X-Forwarded-For")).thenReturn("  ");
    when(request.getRemoteAddr()).thenReturn("172.16.0.5");

    assertThat(IpAddressExtractor.extract(request)).isEqualTo("172.16.0.5");
  }
}
