package com.pricetrack.exchange.blockchain;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

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
