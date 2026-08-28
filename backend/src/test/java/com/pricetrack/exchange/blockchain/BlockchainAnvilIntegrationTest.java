package com.pricetrack.exchange.blockchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/** Anvil과 배포된 실제 컨트랙트가 준비됐을 때 명시적으로 실행하는 연동 테스트. */
@EnabledIfEnvironmentVariable(named = "BLOCKCHAIN_INTEGRATION_TESTS", matches = "true")
class BlockchainAnvilIntegrationTest {

    @Test
    void readsChainContractsAndQuotesThroughWeb3j() {
        BlockchainProperties properties = new BlockchainProperties(true,
                required("RPC_URL"), required("MOCK_KRW_ADDRESS"), required("MSEC_ADDRESS"),
                required("PRICE_ORACLE_ADDRESS"), required("EXCHANGE_VAULT_ADDRESS"),
                required("OPERATOR_PRIVATE_KEY"));

        Web3j web3j = Web3j.build(new HttpService(properties.rpcUrl()));
        try {
            ContractGateway gateway = new ContractGateway(web3j);
            BlockchainService service = new BlockchainService(properties, web3j, gateway);

            BlockchainService.ConnectionStatus status = service.connectionStatus();
            BlockchainService.ContractSnapshot snapshot = service.contractSnapshot();
            ContractGateway.Quote quote = service.quoteBuy(new BigInteger("750000000000000000000000"));

            assertThat(status.chainId()).isEqualTo(BigInteger.valueOf(31337));
            assertThat(status.latestBlock()).isPositive();
            assertThat(snapshot.oracle().priceE8()).isEqualTo(new BigInteger("7500000000000"));
            assertThat(snapshot.feeBps()).isEqualTo(BigInteger.TEN);
            assertThat(quote.outputAmount()).isEqualTo(new BigInteger("9990000000000000000"));
            assertThat(quote.fee()).isEqualTo(new BigInteger("750000000000000000000"));
        } finally {
            web3j.shutdown();
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
