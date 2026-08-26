package com.pricetrack.exchange.admin;

import java.util.Locale;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.wallet.WalletService;

@Component
public class AdminAccountInitializer implements ApplicationRunner {
    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WalletService walletService;

    public AdminAccountInitializer(AdminProperties properties, UserRepository userRepository,
            PasswordEncoder passwordEncoder, WalletService walletService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.walletService = walletService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) return;

        String loginId = properties.loginId().trim().toLowerCase(Locale.ROOT);
        validate(loginId, properties.password(), properties.nickname());
        User existing = userRepository.findByLoginId(loginId).orElse(null);
        if (existing != null) {
            if (existing.getRole() != UserRole.ADMIN) {
                throw new IllegalStateException("ADMIN_LOGIN_ID가 일반 사용자 계정과 충돌합니다.");
            }
            return;
        }

        User admin = new User();
        admin.setLoginId(loginId);
        admin.setPasswordHash(passwordEncoder.encode(properties.password()));
        admin.setNickname(properties.nickname().trim());
        admin.setRole(UserRole.ADMIN);
        admin = userRepository.save(admin);
        walletService.initializeBalances(admin.getId());
    }

    private void validate(String loginId, String password, String nickname) {
        if (!loginId.matches("^[a-zA-Z0-9_-]{4,30}$")) {
            throw new IllegalStateException("ADMIN_LOGIN_ID는 영문, 숫자, _, - 조합의 4~30자여야 합니다.");
        }
        if (password.length() < 8 || password.length() > 72) {
            throw new IllegalStateException("ADMIN_PASSWORD는 8~72자여야 합니다.");
        }
        if (nickname.trim().length() < 2 || nickname.trim().length() > 30) {
            throw new IllegalStateException("ADMIN_NICKNAME은 2~30자여야 합니다.");
        }
    }
}
