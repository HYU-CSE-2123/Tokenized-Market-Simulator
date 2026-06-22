package com.pricetrack.exchange.blockchain;

import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/**
 * web3j 기반 온체인 연동 (기획서 §16 Phase 3).
 * TODO: ExchangeVault.buy/sell 트랜잭션 전송, receipt polling, 이벤트 파싱, 주문 상태 갱신.
 */
@Service
public class BlockchainService {

    private final BlockchainProperties properties;
    private final Web3j web3j;

    public BlockchainService(BlockchainProperties properties) {
        this.properties = properties;
        this.web3j = Web3j.build(new HttpService(properties.rpcUrl()));
    }

    /** 연결 확인용 — 최신 블록 번호. (Phase 3 에서 실제 거래 메서드로 확장) */
    public java.math.BigInteger latestBlockNumber() throws Exception {
        return web3j.ethBlockNumber().send().getBlockNumber();
    }

    public BlockchainProperties properties() {
        return properties;
    }
}
