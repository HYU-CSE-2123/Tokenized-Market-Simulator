package com.pricetrack.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 삼성전자 가격 추종 토큰 거래소 백엔드 진입점.
 * 기획서 §10 — 인증, 가격, 견적, 주문, 체결, 포트폴리오, web3j 연동, WebSocket 스트림.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class ExchangeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeApplication.class, args);
    }
}
