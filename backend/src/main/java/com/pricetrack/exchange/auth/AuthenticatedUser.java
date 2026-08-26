package com.pricetrack.exchange.auth;

/** JWT 검증 후 Spring SecurityContext에 저장되는 최소 사용자 정보. */
public record AuthenticatedUser(Long userId, String loginId) {
}
