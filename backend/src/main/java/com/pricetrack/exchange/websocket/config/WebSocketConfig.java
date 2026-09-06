package com.pricetrack.exchange.websocket.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP WebSocket 설정 (기획서 §13).
 * 구독 토픽: /topic/markets/mSEC/price, /topic/markets/mSEC/trades,
 *           /user/queue/orders, /user/queue/portfolio
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor authInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(WebSocketAuthInterceptor authInterceptor,
            @Value("${app.websocket.allowed-origin-patterns:*}") String[] allowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Android 등 네이티브 클라이언트는 표준 WebSocket endpoint를 사용한다.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
        // 브라우저 호환 fallback이 필요한 클라이언트만 SockJS endpoint를 사용한다.
        registry.addEndpoint("/ws-sockjs").setAllowedOriginPatterns(allowedOrigins).withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
