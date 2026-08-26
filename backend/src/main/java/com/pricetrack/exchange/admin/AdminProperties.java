package com.pricetrack.exchange.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String loginId, String password, String nickname) {
    public AdminProperties {
        loginId = loginId == null || loginId.isBlank() ? "admin" : loginId;
        password = password == null ? "" : password;
        nickname = nickname == null || nickname.isBlank() ? "관리자" : nickname;
    }

    public boolean enabled() {
        return !password.isBlank();
    }
}
