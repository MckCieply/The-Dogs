package com.thedogs.modules.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the real client IP address from an HTTP request.
 *
 * <p>Trust chain (most to least trusted):
 *
 * <ol>
 *   <li>{@code CF-Connecting-IP} — set by Cloudflare Tunnel; trustworthy because Cloudflare strips
 *       any client-supplied header of this name before forwarding.
 *   <li>{@code X-Forwarded-For} leftmost entry — set by load-balancers / reverse proxies in front
 *       of the application. NOTE: only safe when the deployment topology is known and the
 *       immediately upstream proxy is trusted to not pass client-supplied headers through.
 *   <li>{@code request.getRemoteAddr()} — direct TCP peer; always present but may be the proxy
 *       address rather than the client in reverse-proxy deployments.
 * </ol>
 *
 * <p>This trust assumption is acceptable for the current single-node Cloudflare Tunnel deployment
 * (ADR-0011). Re-evaluate if the deployment topology changes to multi-hop proxies.
 */
public final class IpAddressExtractor {

  private IpAddressExtractor() {}

  /**
   * Returns the raw IP string of the originating client. Never null.
   *
   * @param request the current HTTP request
   * @return raw IP address string (not masked)
   */
  public static String extract(HttpServletRequest request) {
    // 1. Cloudflare sets this header; it cannot be spoofed by the client when behind CF Tunnel.
    String cfIp = request.getHeader("CF-Connecting-IP");
    if (cfIp != null && !cfIp.isBlank()) {
      return cfIp.trim();
    }

    // 2. Standard proxy header — take the leftmost (client-originating) entry.
    String xForwardedFor = request.getHeader("X-Forwarded-For");
    if (xForwardedFor != null && !xForwardedFor.isBlank()) {
      String first = xForwardedFor.split(",")[0];
      return first.trim();
    }

    // 3. Direct TCP peer address — always available.
    return request.getRemoteAddr();
  }
}
