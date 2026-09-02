package com.pricetrack.exchange.blockchain.config;

import com.pricetrack.exchange.blockchain.contract.ContractGateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/** 애플리케이션 전체가 공유하고 종료 시 정리되는 web3j RPC 클라이언트를 구성한다. */
@Configuration
public class BlockchainConfig {
    @Bean(destroyMethod = "shutdown")
    Web3j web3j(BlockchainProperties properties) {
        return Web3j.build(new HttpService(properties.rpcUrl()));
    }

    @Bean
    ContractGateway contractGateway(Web3j web3j) {
        return new ContractGateway(web3j);
    }
}
