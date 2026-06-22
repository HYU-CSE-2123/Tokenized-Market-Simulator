package com.pricetrack.exchange.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API (기획서 §12.1).
 * TODO(Phase 2): 실제 회원가입/로그인 로직과 UserService 연동.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    public record SignupRequest(String email, String password, String nickname) {}

    public record LoginRequest(String email, String password) {}

    public record TokenResponse(String accessToken) {}

    @PostMapping("/auth/signup")
    public TokenResponse signup(@RequestBody SignupRequest request) {
        // TODO: User 생성, 비밀번호 해시, 지갑 주소 발급
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@RequestBody LoginRequest request) {
        // TODO: 자격 검증 후 JwtTokenProvider.createToken
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }

    @GetMapping("/me")
    public Object me() {
        // TODO: SecurityContext 의 사용자 반환
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }
}
