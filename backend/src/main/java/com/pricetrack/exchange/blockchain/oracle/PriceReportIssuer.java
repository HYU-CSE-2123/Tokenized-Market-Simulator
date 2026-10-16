package com.pricetrack.exchange.blockchain.oracle;

import com.pricetrack.exchange.blockchain.BlockchainService;
import com.pricetrack.exchange.blockchain.contract.ContractGateway;
import com.pricetrack.exchange.blockchain.support.PriceUnits;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.MarketDataEligibility;
import com.pricetrack.exchange.market.TradingPriceContext;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 최신 시장 스냅샷을 주문 조건에 결합해 30초 유효 EIP-712 가격 보고서를 발급한다. */
@Service
public class PriceReportIssuer {
    static final Duration MAX_OBSERVATION_AGE = Duration.ofSeconds(5);
    static final Duration MAX_FUTURE_SKEW = Duration.ofSeconds(2);
    static final Duration REPORT_TTL = Duration.ofSeconds(30);

    private final MarketPriceService marketPriceService;
    private final BlockchainService blockchainService;
    private final PriceReportSigner signer;
    private final Clock clock;

    @Autowired
    public PriceReportIssuer(MarketPriceService marketPriceService, BlockchainService blockchainService,
            PriceReportSigner signer) {
        this(marketPriceService, blockchainService, signer, Clock.systemUTC());
    }

    PriceReportIssuer(MarketPriceService marketPriceService, BlockchainService blockchainService,
            PriceReportSigner signer, Clock clock) {
        this.marketPriceService = marketPriceService;
        this.blockchainService = blockchainService;
        this.signer = signer;
        this.clock = clock;
    }

    public SignedPriceReport issue(PriceReport.Side side, BigInteger inputAmount) {
        if (side == null) throw new IllegalArgumentException("거래 방향이 필요합니다.");
        if (inputAmount == null || inputAmount.signum() <= 0) {
            throw new IllegalArgumentException("입력 수량은 0보다 커야 합니다.");
        }

        TradingPriceContext context = marketPriceService.requireTradingContext();
        MarketPriceSnapshot snapshot = context.snapshot();
        validateSnapshot(context, clock.instant());
        BigInteger priceE8 = PriceUnits.toPriceE8(snapshot.price());
        ContractGateway.Quote quote = blockchainService.quoteAtPrice(side, inputAmount, priceE8);
        if (quote.outputAmount().signum() <= 0) {
            throw new PriceReportIssuanceException("최소 수령량이 0인 가격 보고서는 발급할 수 없습니다.");
        }

        // RPC 견적·domain 조회가 지연된 경우 만료에 가까운 보고서를 서명하지 않는다.
        BigInteger chainId = blockchainService.chainId();
        String oracleAddress = blockchainService.oracleAddress();
        String executor = blockchainService.operatorAddress();
        validateSnapshot(context, clock.instant());
        if (!MarketDataEligibility.synthetic(snapshot)) {
            // RPC 동안 연결/장 상태가 바뀌었다면 서명하지 않는다. 계산에 사용한 고정 가격은 바꾸지 않는다.
            TradingPriceContext latest = marketPriceService.requireTradingContext();
            if (!context.sameConnection(latest)) {
                throw new PriceReportIssuanceException("견적 발급 중 시세 공급자 또는 연결 세대가 변경되었습니다.");
            }
            validateSnapshot(latest, clock.instant());
        }

        BigInteger observedAt = BigInteger.valueOf(snapshot.observedAt().getEpochSecond());
        PriceReport report = new PriceReport(
                PriceReportEip712.hashText(UUID.randomUUID().toString()),
                PriceReportEip712.hashText(snapshot.symbol()),
                priceE8,
                observedAt,
                observedAt.add(BigInteger.valueOf(REPORT_TTL.toSeconds())),
                side,
                inputAmount,
                quote.outputAmount(),
                executor);
        byte[] digest = PriceReportEip712.digest(report, chainId, oracleAddress);
        return new SignedPriceReport(report, signer.sign(digest), quote.fee());
    }

    public void validateSignerConfiguration() {
        String configured = signer.signerAddress();
        String onChain = blockchainService.oraclePriceSigner();
        if (!configured.equalsIgnoreCase(onChain)) {
            throw new PriceReportIssuanceException(
                    "PRICE_SIGNER_PRIVATE_KEY 주소가 PriceOracle.priceSigner와 일치하지 않습니다.");
        }
    }

    private void validateSnapshot(TradingPriceContext context, Instant now) {
        MarketPriceSnapshot snapshot=context.snapshot();
        // 신규 서명 발급 경계만 강화한다. 기존 quote 소비·SIGNED 복구·receipt 정산과 DB-only는 변경하지 않는다.
        if (!context.allowed()) {
            throw new PriceReportIssuanceException("신규 서명 보고서는 OPEN + LIVE 또는 키움 현재 연결의 정상 장중 체결 근거가 필요합니다.");
        }
        if (!MarketPriceService.SYMBOL.equals(snapshot.symbol())) {
            throw new PriceReportIssuanceException("지원하지 않는 가격 종목입니다: " + snapshot.symbol());
        }
        Duration age = Duration.between(snapshot.observedAt(), now);
        if (age.compareTo(MAX_OBSERVATION_AGE) > 0) {
            throw new PriceReportIssuanceException("가격 관측 후 5초가 지나 보고서를 발급할 수 없습니다.");
        }
        if (age.compareTo(MAX_FUTURE_SKEW.negated()) < 0) {
            throw new PriceReportIssuanceException("가격 관측 시각이 허용 범위보다 미래입니다.");
        }
    }
}
