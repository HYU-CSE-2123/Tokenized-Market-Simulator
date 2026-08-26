package com.pricetrack.exchange.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.pricetrack.exchange.user.User;
import com.pricetrack.exchange.user.UserRepository;
import com.pricetrack.exchange.user.UserRole;
import com.pricetrack.exchange.wallet.WalletService;

@ExtendWith(MockitoExtension.class)
class AdminAccountInitializerTest {
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock WalletService walletService;

    @Test
    void failsWhenConfiguredAdminIdBelongsToRegularUser() {
        User regularUser = new User();
        regularUser.setLoginId("admin");
        regularUser.setRole(UserRole.USER);
        when(userRepository.findByLoginId("admin")).thenReturn(Optional.of(regularUser));
        AdminAccountInitializer initializer = new AdminAccountInitializer(
                new AdminProperties("admin", "admin-password-123", "관리자"),
                userRepository, passwordEncoder, walletService);

        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("충돌");
        verify(passwordEncoder, never()).encode("admin-password-123");
    }
}
