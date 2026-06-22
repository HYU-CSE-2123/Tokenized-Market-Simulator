package com.pricetrack.exchange.wallet;

import org.springframework.stereotype.Service;

/**
 * 사용자 지갑 주소 관리 (기획서 §10, §5.1 — 지갑 생성/연결).
 * TODO(Phase 2): 회원가입 시 지갑 주소 발급/연결, mKRW faucet 트리거.
 */
@Service
public class WalletService {

    /** 신규 사용자에게 지갑 주소를 발급/연결한다. */
    public String assignWallet(Long userId) {
        throw new UnsupportedOperationException("not implemented (Phase 2)");
    }
}
