package com.pricetrack.exchange.websocket.auth;

import java.security.Principal;

import com.pricetrack.exchange.user.UserRole;

/** JWT 검증 후 하나의 STOMP session에 연결되는 사용자 신원이다. */
public record WebSocketPrincipal(Long userId, String loginId, UserRole role) implements Principal {

    /** Spring user destination이 변경되지 않는 사용자 DB 식별자로 session을 구분하게 한다. */
    @Override
    public String getName() {
        return userId.toString();
    }
}
