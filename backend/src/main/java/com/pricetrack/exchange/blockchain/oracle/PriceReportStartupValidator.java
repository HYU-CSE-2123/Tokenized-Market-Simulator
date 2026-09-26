package com.pricetrack.exchange.blockchain.oracle;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 블록체인 모드 기동 시 로컬 서명 키와 온체인 등록 주소 불일치를 즉시 차단한다. */
@Component
@ConditionalOnProperty(prefix = "app.blockchain.price-report", name = "enabled", havingValue = "true")
public class PriceReportStartupValidator implements ApplicationRunner {
    private final PriceReportIssuer issuer;

    public PriceReportStartupValidator(PriceReportIssuer issuer) {
        this.issuer = issuer;
    }

    @Override
    public void run(ApplicationArguments args) {
        issuer.validateSignerConfiguration();
    }
}
