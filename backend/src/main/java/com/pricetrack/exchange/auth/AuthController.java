package com.pricetrack.exchange.auth;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.pricetrack.exchange.user.UserRole;

/**
 * 자체 로그인 인증 API (기획서 §12.1, Phase 2.1-A).
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record SignupRequest(
            @NotBlank
            @Pattern(regexp = "^[a-zA-Z0-9_-]{4,30}$", message = "아이디는 영문, 숫자, _, - 조합의 4~30자여야 합니다.")
            String loginId,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(min = 2, max = 30) String nickname) {}

    public record LoginRequest(
            @NotBlank String loginId,
            @NotBlank String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    public record MeResponse(
            Long id,
            String loginId,
            String nickname,
            String email,
            boolean emailVerified,
            String walletAddress,
            UserRole role,
            Instant createdAt) {}

    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return authService.me(principal.userId());
    }
}
