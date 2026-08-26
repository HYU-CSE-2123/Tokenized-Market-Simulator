package com.pricetrack.exchange.wallet;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.wallet.WalletService.FaucetResult;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/faucet")
    public FaucetResult faucet(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.faucet(user.userId());
    }
}
