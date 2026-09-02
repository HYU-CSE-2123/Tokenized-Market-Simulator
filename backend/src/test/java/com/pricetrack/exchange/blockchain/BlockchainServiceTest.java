package com.pricetrack.exchange.blockchain;

import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.contract.ContractGateway;
import com.pricetrack.exchange.blockchain.support.BlockchainConfigurationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;

/** 비활성화·누락 설정·잘못된 키 검증과 운영자 주소 파생을 검증한다. */
class BlockchainServiceTest {
    private final Web3j web3j = mock(Web3j.class);
    private final ContractGateway contracts = mock(ContractGateway.class);

    @Test
    void disabledIntegrationFailsWithActionableMessage() {
        BlockchainService service = service(properties(false, "", ""));

        assertThatThrownBy(service::latestBlockNumber)
                .isInstanceOf(BlockchainConfigurationException.class)
                .hasMessageContaining("BLOCKCHAIN_ENABLED=true");
    }

    @Test
    void missingContractAddressIsRejectedBeforeRpcCall() {
        BlockchainService service = service(properties(true, "", validPrivateKey()));

        assertThatThrownBy(service::connectionStatus)
                .isInstanceOf(BlockchainConfigurationException.class)
                .hasMessageContaining("MOCK_KRW_ADDRESS");
    }

    @Test
    void invalidPrivateKeyIsRejected() {
        BlockchainService service = service(properties(true, validAddress(), "not-a-private-key"));

        assertThatThrownBy(service::connectionStatus)
                .isInstanceOf(BlockchainConfigurationException.class)
                .hasMessageContaining("OPERATOR_PRIVATE_KEY");
    }

    @Test
    void derivesOperatorAddressWithoutExposingPrivateKey() {
        BlockchainService service = service(properties(true, validAddress(), validPrivateKey()));

        assertThat(service.operatorAddress())
                .isEqualToIgnoringCase("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266");
    }

    private BlockchainService service(BlockchainProperties properties) {
        return new BlockchainService(properties, web3j, contracts);
    }

    private BlockchainProperties properties(boolean enabled, String address, String privateKey) {
        return new BlockchainProperties(enabled, "http://127.0.0.1:8545", address, address,
                address, address, privateKey);
    }

    private String validAddress() {
        return "0x5FbDB2315678afecb367f032d93F642f64180aa3";
    }

    private String validPrivateKey() {
        return "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    }
}
