package com.pricetrack.exchange.blockchain;

import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.contract.ContractGateway;
import com.pricetrack.exchange.blockchain.support.BlockchainConfigurationException;
import com.pricetrack.exchange.blockchain.support.OperatorNotReadyException;

import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.WalletUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

/**
 * 주문·견적·가격 동기화 코드가 사용하는 블록체인 진입점이다.
 * 설정 검증과 운영자 주소 파생, 컨트랙트 조회 및 거래 준비 상태 확인을 제공하고
 * 하위 코드가 RPC와 ABI 세부 구현에 직접 의존하지 않게 한다.
 */
@Service
public class BlockchainService {
    private final BlockchainProperties properties;
    private final Web3j web3j;
    private final ContractGateway contracts;

    public BlockchainService(BlockchainProperties properties, Web3j web3j, ContractGateway contracts) {
        this.properties = properties;
        this.web3j = web3j;
        this.contracts = contracts;
    }

    public ConnectionStatus connectionStatus() {
        requireEnabled();
        Map<String, String> addresses = configuredContracts();
        String operatorAddress = operatorAddress();
        try {
            BigInteger chainId = web3j.ethChainId().send().getChainId();
            BigInteger latestBlock = web3j.ethBlockNumber().send().getBlockNumber();
            for (Map.Entry<String, String> contract : addresses.entrySet()) {
                String code = web3j.ethGetCode(contract.getValue(), DefaultBlockParameterName.LATEST)
                        .send().getCode();
                if (code == null || code.equals("0x") || code.equals("0x0")) {
                    throw new BlockchainConfigurationException(
                            contract.getKey() + " 주소에 배포된 컨트랙트 코드가 없습니다: " + contract.getValue());
                }
            }
            return new ConnectionStatus(chainId, latestBlock, operatorAddress, addresses);
        } catch (IOException exception) {
            throw new BlockchainConfigurationException("블록체인 RPC 연결에 실패했습니다: " + properties.rpcUrl(), exception);
        }
    }

    public BigInteger latestBlockNumber() {
        requireEnabled();
        try {
            return web3j.ethBlockNumber().send().getBlockNumber();
        } catch (IOException exception) {
            throw new BlockchainConfigurationException("최신 블록 조회에 실패했습니다.", exception);
        }
    }

    public String operatorAddress() {
        String privateKey = required("OPERATOR_PRIVATE_KEY", properties.operatorPrivateKey());
        try {
            return Credentials.create(privateKey).getAddress();
        } catch (RuntimeException exception) {
            throw new BlockchainConfigurationException("OPERATOR_PRIVATE_KEY 형식이 올바르지 않습니다.", exception);
        }
    }

    public ContractSnapshot contractSnapshot() {
        requireEnabled();
        Map<String, String> addresses = configuredContracts();
        String operator = operatorAddress();
        ContractGateway.OraclePrice price = contracts.getPrice(addresses.get("PriceOracle"));
        return new ContractSnapshot(price, contracts.feeBps(addresses.get("ExchangeVault")),
                contracts.balanceOf(addresses.get("MockKRW"), operator),
                contracts.balanceOf(addresses.get("mSEC"), operator),
                contracts.allowance(addresses.get("MockKRW"), operator, addresses.get("ExchangeVault")));
    }

    public ContractGateway.Quote quoteBuy(BigInteger krwAmount) {
        requirePositive(krwAmount);
        return contracts.quoteBuy(configuredContracts().get("ExchangeVault"), krwAmount);
    }

    public ContractGateway.Quote quoteSell(BigInteger tokenAmount) {
        requirePositive(tokenAmount);
        return contracts.quoteSell(configuredContracts().get("ExchangeVault"), tokenAmount);
    }

    public ContractGateway.OraclePrice oraclePrice() {
        requireEnabled();
        return contracts.getPrice(configuredContracts().get("PriceOracle"));
    }

    public String oracleOwner() {
        requireEnabled();
        return contracts.owner(configuredContracts().get("PriceOracle"));
    }

    public String oracleAddress() {
        requireEnabled();
        return configuredContracts().get("PriceOracle");
    }

    public String encodeUpdatePrice(BigInteger priceE8) {
        requirePositive(priceE8);
        return contracts.encodeUpdatePrice(priceE8);
    }

    public BuyReadiness buyReadiness(BigInteger krwAmount) {
        requirePositive(krwAmount);
        Map<String, String> addresses = configuredContracts();
        String operator = operatorAddress();
        BigInteger balance = contracts.balanceOf(addresses.get("MockKRW"), operator);
        BigInteger allowance = contracts.allowance(addresses.get("MockKRW"), operator,
                addresses.get("ExchangeVault"));
        if (balance.compareTo(krwAmount) < 0) {
            throw new OperatorNotReadyException("운영자 온체인 mKRW 잔고가 부족합니다.");
        }
        if (allowance.compareTo(krwAmount) < 0) {
            throw new OperatorNotReadyException("운영자 mKRW의 ExchangeVault allowance가 부족합니다.");
        }
        return new BuyReadiness(contracts.quoteBuy(addresses.get("ExchangeVault"), krwAmount),
                addresses.get("ExchangeVault"));
    }

    public SellReadiness sellReadiness(BigInteger tokenAmount) {
        requirePositive(tokenAmount);
        Map<String, String> addresses = configuredContracts();
        BigInteger balance = contracts.balanceOf(addresses.get("mSEC"), operatorAddress());
        if (balance.compareTo(tokenAmount) < 0) {
            throw new OperatorNotReadyException("운영자 온체인 mSEC 잔고가 부족합니다.");
        }
        return new SellReadiness(contracts.quoteSell(addresses.get("ExchangeVault"), tokenAmount),
                addresses.get("ExchangeVault"));
    }

    public String encodeBuy(BigInteger amount) { return contracts.encodeBuy(amount); }
    public String encodeSell(BigInteger amount) { return contracts.encodeSell(amount); }

    private Map<String, String> configuredContracts() {
        Map<String, String> addresses = new LinkedHashMap<>();
        addresses.put("MockKRW", requiredAddress("MOCK_KRW_ADDRESS", properties.mockKrwAddress()));
        addresses.put("mSEC", requiredAddress("MSEC_ADDRESS", properties.mSecAddress()));
        addresses.put("PriceOracle", requiredAddress("PRICE_ORACLE_ADDRESS", properties.priceOracleAddress()));
        addresses.put("ExchangeVault", requiredAddress("EXCHANGE_VAULT_ADDRESS", properties.exchangeVaultAddress()));
        return Map.copyOf(addresses);
    }

    private String requiredAddress(String name, String value) {
        String address = required(name, value);
        if (!WalletUtils.isValidAddress(address)) {
            throw new BlockchainConfigurationException(name + " 형식이 올바르지 않습니다: " + address);
        }
        return address;
    }

    private String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new BlockchainConfigurationException(name + " 환경 변수가 필요합니다.");
        }
        return value.trim();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new BlockchainConfigurationException(
                    "블록체인 연동이 비활성화되어 있습니다. BLOCKCHAIN_ENABLED=true로 설정하세요.");
        }
    }

    private void requirePositive(BigInteger amount) {
        requireEnabled();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("조회 수량은 0보다 커야 합니다.");
        }
    }

    public record ConnectionStatus(BigInteger chainId, BigInteger latestBlock, String operatorAddress,
            Map<String, String> contractAddresses) {}

    public record ContractSnapshot(ContractGateway.OraclePrice oracle, BigInteger feeBps,
            BigInteger operatorKrwBalance, BigInteger operatorMsecBalance, BigInteger vaultAllowance) {}
    public record BuyReadiness(ContractGateway.Quote quote, String vaultAddress) {}
    public record SellReadiness(ContractGateway.Quote quote, String vaultAddress) {}
}
