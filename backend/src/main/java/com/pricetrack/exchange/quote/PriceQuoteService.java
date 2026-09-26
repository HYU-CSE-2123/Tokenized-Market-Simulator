package com.pricetrack.exchange.quote;

import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.oracle.PriceReportIssuer;
import com.pricetrack.exchange.blockchain.oracle.PriceReportEip712;
import com.pricetrack.exchange.blockchain.oracle.SignedPriceReport;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.common.exception.UnsupportedSymbolException;
import com.pricetrack.exchange.market.MarketPriceService;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 서명 견적의 사용자 귀속, 영속화, 만료 및 일회성 소비를 관리한다. */
@Service
@Transactional(readOnly = true)
public class PriceQuoteService {
    private final PriceReportIssuer issuer;
    private final PriceQuoteRepository repository;
    private final Clock clock;

    @Autowired
    public PriceQuoteService(PriceReportIssuer issuer, PriceQuoteRepository repository) {
        this(issuer, repository, Clock.systemUTC());
    }

    PriceQuoteService(PriceReportIssuer issuer, PriceQuoteRepository repository, Clock clock) {
        this.issuer = issuer;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public PriceQuote issue(Long userId, String symbol, PriceReport.Side side, BigDecimal inputAmount) {
        if (!MarketPriceService.SYMBOL.equals(symbol)) throw new UnsupportedSymbolException();
        BigInteger inputWei = TokenUnits.toWei(inputAmount);
        SignedPriceReport signed = issuer.issue(side, inputWei);
        PriceReport report = signed.report();

        PriceQuote quote = new PriceQuote();
        quote.setQuoteId(report.quoteId());
        quote.setUserId(userId);
        quote.setSymbol(symbol);
        quote.setSide(side);
        quote.setPriceE8(report.priceE8());
        quote.setInputAmount(report.inputAmount());
        quote.setMinimumOutput(report.minimumOutput());
        quote.setFee(signed.fee());
        quote.setExecutor(report.executor());
        quote.setSignature(signed.signature());
        quote.setObservedAt(epoch(report.observedAt()));
        quote.setValidUntil(epoch(report.validUntil()));
        quote.setCreatedAt(clock.instant());
        return repository.save(quote);
    }

    /** 잠금을 잡고 소유권과 주문 조건을 검증해 견적을 정확히 한 번만 소비한다. */
    @Transactional(noRollbackFor = PriceQuoteUnavailableException.class)
    public PriceQuote consume(Long userId, String quoteId, PriceReport.Side side,
            BigDecimal inputAmount, Long orderId) {
        PriceQuote quote = lockAndValidate(userId, quoteId, side, inputAmount);
        markConsumed(quote, orderId);
        return quote;
    }

    /** 주문 준비 트랜잭션이 견적 행 잠금을 공유할 수 있도록 유효한 견적을 반환한다. */
    @Transactional(noRollbackFor = PriceQuoteUnavailableException.class)
    public PriceQuote lockAndValidate(Long userId, String quoteId, PriceReport.Side side,
            BigDecimal inputAmount) {
        if (quoteId == null || quoteId.isBlank()) {
            throw new PriceQuoteUnavailableException("온체인 주문에는 quoteId가 필요합니다.");
        }
        PriceQuote quote = repository.findForUpdateByQuoteId(quoteId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(PriceQuoteNotFoundException::new);
        Instant now = clock.instant();
        if (quote.getStatus() != PriceQuoteStatus.ISSUED) {
            throw new PriceQuoteUnavailableException("이미 사용되었거나 만료된 가격 견적입니다.");
        }
        if (now.isAfter(quote.getValidUntil())) {
            quote.setStatus(PriceQuoteStatus.EXPIRED);
            throw new PriceQuoteUnavailableException("가격 견적이 만료되었습니다.");
        }
        if (quote.getSide() != side) {
            throw new PriceQuoteUnavailableException("가격 견적의 거래 방향이 주문과 다릅니다.");
        }
        if (!quote.getInputAmount().equals(TokenUnits.toWei(inputAmount))) {
            throw new PriceQuoteUnavailableException("가격 견적의 입력 수량이 주문과 다릅니다.");
        }
        return quote;
    }

    /** 같은 트랜잭션에서 생성된 주문에 검증 완료 견적을 영구 연결한다. */
    public void markConsumed(PriceQuote quote, Long orderId) {
        if (orderId == null) throw new IllegalArgumentException("견적에 연결할 주문 ID가 필요합니다.");
        quote.setStatus(PriceQuoteStatus.CONSUMED);
        quote.setConsumedAt(clock.instant());
        quote.setOrderId(orderId);
    }

    /** DB에 보관한 원본 필드만 사용해 Vault에 전달할 보고서와 서명을 복원한다. */
    public SignedPriceReport signedReport(PriceQuote quote) {
        PriceReport report = new PriceReport(
                quote.getQuoteId(),
                PriceReportEip712.hashText(quote.getSymbol()),
                quote.getPriceE8(),
                BigInteger.valueOf(quote.getObservedAt().getEpochSecond()),
                BigInteger.valueOf(quote.getValidUntil().getEpochSecond()),
                quote.getSide(),
                quote.getInputAmount(),
                quote.getMinimumOutput(),
                quote.getExecutor());
        return new SignedPriceReport(report, quote.getSignature(), quote.getFee());
    }

    private Instant epoch(BigInteger value) {
        try {
            return Instant.ofEpochSecond(value.longValueExact());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("가격 보고서 시각이 Instant 범위를 벗어났습니다.", exception);
        }
    }
}
