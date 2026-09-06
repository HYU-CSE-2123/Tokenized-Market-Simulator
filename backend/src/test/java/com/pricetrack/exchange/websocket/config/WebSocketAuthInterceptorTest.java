package com.pricetrack.exchange.websocket.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.pricetrack.exchange.auth.JwtTokenProvider;
import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.websocket.auth.WebSocketPrincipal;

/** STOMP JWT 인증과 공개·개인 destination 경계 및 client SEND 차단을 검증한다. */
class WebSocketAuthInterceptorTest {
    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(jwtTokenProvider, userRepository);
    }

    @Test
    void authenticatesConnectWithValidBearerToken() {
        User user = user(15L, "alice");
        when(jwtTokenProvider.getUserId("valid-token")).thenReturn(15L);
        when(userRepository.findById(15L)).thenReturn(Optional.of(user));
        StompHeaderAccessor accessor = accessor(StompCommand.CONNECT, null);
        accessor.addNativeHeader("Authorization", "Bearer valid-token");

        interceptor.preSend(message(accessor), channel);

        assertThat(accessor.getUser()).isEqualTo(new WebSocketPrincipal(15L, "alice", UserRole.USER));
        assertThat(accessor.getUser().getName()).isEqualTo("15");
    }

    @Test
    void permitsAnonymousPublicSubscription() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE, "/topic/markets/mSEC/price");
        assertThat(interceptor.preSend(message(accessor), channel)).isNotNull();
    }

    @Test
    void rejectsAnonymousPrivateSubscription() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE, "/user/queue/orders");
        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("인증");
    }

    @Test
    void permitsAuthenticatedPrivateSubscription() {
        StompHeaderAccessor accessor = accessor(StompCommand.SUBSCRIBE, "/user/queue/portfolio");
        accessor.setUser(new WebSocketPrincipal(15L, "alice", UserRole.USER));
        assertThat(interceptor.preSend(message(accessor), channel)).isNotNull();
    }

    @Test
    void rejectsInvalidTokenAndUnknownDestination() {
        when(jwtTokenProvider.getUserId("invalid-token")).thenThrow(new IllegalArgumentException("invalid"));
        StompHeaderAccessor connect = accessor(StompCommand.CONNECT, null);
        connect.addNativeHeader("Authorization", "Bearer invalid-token");
        assertThatThrownBy(() -> interceptor.preSend(message(connect), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("JWT");

        StompHeaderAccessor subscribe = accessor(StompCommand.SUBSCRIBE, "/topic/unapproved");
        assertThatThrownBy(() -> interceptor.preSend(message(subscribe), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("destination");
    }

    @Test
    void rejectsClientSend() {
        StompHeaderAccessor accessor = accessor(StompCommand.SEND, "/topic/markets/mSEC/price");
        assertThatThrownBy(() -> interceptor.preSend(message(accessor), channel))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("SEND");
    }

    private StompHeaderAccessor accessor(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private Message<byte[]> message(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private User user(Long id, String loginId) {
        User user = new User();
        user.setId(id);
        user.setLoginId(loginId);
        user.setNickname(loginId);
        user.setRole(UserRole.USER);
        return user;
    }
}
