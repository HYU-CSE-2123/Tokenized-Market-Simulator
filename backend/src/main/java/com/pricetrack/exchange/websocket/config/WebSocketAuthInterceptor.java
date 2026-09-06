package com.pricetrack.exchange.websocket.config;

import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.websocket.auth.WebSocketPrincipal;

/**
 * STOMP CONNECT의 JWT를 검증하고 공개·개인 구독 경계를 적용한다.
 * HTTP handshake는 SockJS와 네이티브 클라이언트 호환을 위해 열어 두고 실제 신원은
 * STOMP native Authorization 헤더에서 설정한다.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> PUBLIC_DESTINATIONS = Set.of(
            "/topic/markets/mSEC/price",
            "/topic/markets/mSEC/trades");
    private static final Set<String> PRIVATE_DESTINATIONS = Set.of(
            "/user/queue/orders",
            "/user/queue/portfolio");

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    public WebSocketAuthInterceptor(JwtTokenProvider jwtTokenProvider, UserRepository userRepository) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userRepository = userRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;

        if (accessor.getCommand() == StompCommand.CONNECT) authenticate(accessor);
        if (accessor.getCommand() == StompCommand.SUBSCRIBE) authorizeSubscription(accessor);
        if (accessor.getCommand() == StompCommand.SEND) {
            throw rejected("클라이언트 STOMP SEND는 허용하지 않습니다.", null);
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) return;
        if (!authorization.startsWith(BEARER_PREFIX)
                || authorization.length() == BEARER_PREFIX.length()) {
            throw rejected("WebSocket Authorization 헤더 형식이 올바르지 않습니다.", null);
        }

        try {
            Long userId = jwtTokenProvider.getUserId(authorization.substring(BEARER_PREFIX.length()));
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
            accessor.setUser(new WebSocketPrincipal(user.getId(), user.getLoginId(), user.getRole()));
        } catch (RuntimeException exception) {
            throw rejected("WebSocket JWT가 유효하지 않습니다.", exception);
        }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (PUBLIC_DESTINATIONS.contains(destination)) return;
        if (PRIVATE_DESTINATIONS.contains(destination)) {
            if (accessor.getUser() instanceof WebSocketPrincipal) return;
            throw rejected("개인 WebSocket 구독에는 인증이 필요합니다.", null);
        }
        throw rejected("허용되지 않은 WebSocket destination입니다.", null);
    }

    private MessageDeliveryException rejected(String message, Throwable cause) {
        return cause == null ? new MessageDeliveryException(message)
                : new MessageDeliveryException(null, message, cause);
    }
}
