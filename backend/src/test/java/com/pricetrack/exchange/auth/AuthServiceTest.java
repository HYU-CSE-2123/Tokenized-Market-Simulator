package com.pricetrack.exchange.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pricetrack.exchange.auth.AuthController.LoginRequest;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.common.exception.DuplicateLoginIdException;
import com.pricetrack.exchange.common.exception.InvalidCredentialsException;
import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtTokenProvider);
    }

    @Test
    void signupNormalizesLoginIdAndHashesPassword() {
        when(userRepository.existsByLoginId("kyobin_21")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return user;
        });
        when(jwtTokenProvider.createToken(1L, "kyobin_21")).thenReturn("access-token");
        when(jwtTokenProvider.getValiditySeconds()).thenReturn(3600L);

        var response = authService.signup(new SignupRequest(" Kyobin_21 ", "password123", " 교빈 "));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getLoginId()).isEqualTo("kyobin_21");
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(saved.getNickname()).isEqualTo("교빈");
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
    }

    @Test
    void signupRejectsDuplicateLoginId() {
        when(userRepository.existsByLoginId("kyobin21")).thenReturn(true);

        assertThatThrownBy(() -> authService.signup(
                new SignupRequest("Kyobin21", "password123", "교빈")))
                .isInstanceOf(DuplicateLoginIdException.class);
    }

    @Test
    void loginReturnsTokenForMatchingPassword() {
        User user = localUser();
        when(userRepository.findByLoginId("kyobin21")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded-password")).thenReturn(true);
        when(jwtTokenProvider.createToken(1L, "kyobin21")).thenReturn("access-token");
        when(jwtTokenProvider.getValiditySeconds()).thenReturn(3600L);

        var response = authService.login(new LoginRequest("KYOBIN21", "password123"));

        assertThat(response.accessToken()).isEqualTo("access-token");
    }

    @Test
    void loginDoesNotRevealWhetherIdOrPasswordWasWrong() {
        when(userRepository.findByLoginId("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(new LoginRequest("missing", "password123")))
                .isInstanceOf(InvalidCredentialsException.class);

        User user = localUser();
        when(userRepository.findByLoginId("kyobin21")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);
        assertThatThrownBy(() -> authService.login(new LoginRequest("kyobin21", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    private User localUser() {
        User user = new User();
        user.setId(1L);
        user.setLoginId("kyobin21");
        user.setPasswordHash("encoded-password");
        user.setNickname("교빈");
        return user;
    }
}
