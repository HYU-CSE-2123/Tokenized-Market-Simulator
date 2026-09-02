package com.pricetrack.exchange.blockchain;

import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.contract.ContractGateway;

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
            BigInteger input = new BigInteger("750000000000000000000000");
            BigInteger expectedFee = input.multiply(snapshot.feeBps()).divide(BigInteger.valueOf(10_000));
            BigInteger expectedOutput = input.subtract(expectedFee).multiply(BigInteger.valueOf(100_000_000))
                    .divide(snapshot.oracle().priceE8());

            assertThat(status.chainId()).isEqualTo(BigInteger.valueOf(31337));
            assertThat(status.latestBlock()).isPositive();
            assertThat(snapshot.oracle().priceE8()).isPositive();
            assertThat(snapshot.feeBps()).isEqualTo(BigInteger.TEN);
            assertThat(quote.outputAmount()).isEqualTo(expectedOutput);
            assertThat(quote.fee()).isEqualTo(expectedFee);
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
