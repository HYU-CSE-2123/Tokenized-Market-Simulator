package com.pricetrack.exchange.auth;

import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.auth.AuthController.LoginRequest;
import com.pricetrack.exchange.auth.AuthController.MeResponse;
import com.pricetrack.exchange.auth.AuthController.SignupRequest;
import com.pricetrack.exchange.auth.AuthController.TokenResponse;
import com.pricetrack.exchange.common.exception.DuplicateLoginIdException;
import com.pricetrack.exchange.common.exception.InvalidCredentialsException;
import com.pricetrack.exchange.common.exception.UserNotFoundException;
import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.wallet.WalletService;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final WalletService walletService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            WalletService walletService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.walletService = walletService;
    }

    @Transactional
    public TokenResponse signup(SignupRequest request) {
        String loginId = normalizeLoginId(request.loginId());
        if (userRepository.existsByLoginId(loginId)) {
            throw new DuplicateLoginIdException();
        }

        User user = new User();
        user.setLoginId(loginId);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname().trim());
        user = userRepository.save(user);
        walletService.initializeBalances(user.getId());

        return token(user);
    }

    public TokenResponse login(LoginRequest request) {
        String loginId = normalizeLoginId(request.loginId());
        User user = userRepository.findByLoginId(loginId)
                .filter(User::supportsLocalLogin)
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        return token(user);
    }

    public MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(UserNotFoundException::new);
        return new MeResponse(
                user.getId(),
                user.getLoginId(),
                user.getNickname(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getWalletAddress(),
                user.getRole(),
                user.getCreatedAt());
    }

    private TokenResponse token(User user) {
        return new TokenResponse(
                jwtTokenProvider.createToken(user.getId(), user.getLoginId()),
                "Bearer",
                jwtTokenProvider.getValiditySeconds());
    }

    static String normalizeLoginId(String loginId) {
        return loginId.trim().toLowerCase(Locale.ROOT);
    }
}
