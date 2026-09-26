package com.pricetrack.exchange.blockchain.contract;

import com.pricetrack.exchange.blockchain.oracle.PriceReport;
import com.pricetrack.exchange.blockchain.oracle.SignedPriceReport;

import com.pricetrack.exchange.blockchain.support.BlockchainConfigurationException;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.StaticStruct;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import static java.util.Collections.emptyList;

/**
 * Solidity ABI와 web3j RPC 사이의 경계다.
 * 읽기 함수는 eth_call 결과를 Java 값으로 변환하고, 쓰기 함수는 서명·전송 계층이
 * 사용할 calldata만 생성한다. 이 클래스는 개인키나 nonce를 관리하지 않는다.
 */
public class ContractGateway {
    private final Web3j web3j;

    public ContractGateway(Web3j web3j) { this.web3j = web3j; }

    /** ERC-20 balanceOf를 eth_call로 실행한다. */
    public BigInteger balanceOf(String contract, String account) {
        return singleUint(contract, new Function("balanceOf",
                List.of(new Address(account)), List.of(new TypeReference<Uint256>() {})));
    }

    /** ERC-20 owner가 spender에게 허용한 사용량을 조회한다. */
    public BigInteger allowance(String token, String owner, String spender) {
        return singleUint(token, new Function("allowance",
                List.of(new Address(owner), new Address(spender)),
                List.of(new TypeReference<Uint256>() {})));
    }

    /** Vault 수수료율을 basis point 단위로 조회한다. */
    public BigInteger feeBps(String vault) {
        return singleUint(vault, new Function("feeBps", emptyList(),
                List.of(new TypeReference<Uint256>() {})));
    }

    /** Ownable 컨트랙트의 관리자 주소를 조회한다. */
    public String owner(String contract) {
        List<Type> values = call(contract, new Function("owner", emptyList(),
                List.of(new TypeReference<Address>() {})));
        if (values.size() != 1 || !(values.getFirst().getValue() instanceof String address)) {
            throw new BlockchainConfigurationException("owner() 반환값 ABI가 예상과 다릅니다.");
        }
        return address;
    }

    /** Oracle에 등록된 EIP-712 가격 보고서 서명자 주소를 조회한다. */
    public String priceSigner(String oracle) {
        List<Type> values = call(oracle, new Function("priceSigner", emptyList(),
                List.of(new TypeReference<Address>() {})));
        if (values.size() != 1 || !(values.getFirst().getValue() instanceof String address)) {
            throw new BlockchainConfigurationException("priceSigner() 반환값 ABI가 예상과 다릅니다.");
        }
        return address;
    }

    /** Oracle의 priceE8과 마지막 갱신 블록 시각을 조회한다. */
    public OraclePrice getPrice(String oracle) {
        List<Type> values = call(oracle, new Function("getPrice", emptyList(), List.of(
                new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {})));
        return new OraclePrice(asUint(values, 0), asUint(values, 1));
    }

    /** 상태를 변경하지 않고 buy 실행 예상 출력량과 수수료를 조회한다. */
    public Quote quoteBuy(String vault, BigInteger krwAmount) { return quote(vault, "quoteBuy", krwAmount); }
    /** 상태를 변경하지 않고 sell 실행 예상 출력량과 수수료를 조회한다. */
    public Quote quoteSell(String vault, BigInteger tokenAmount) { return quote(vault, "quoteSell", tokenAmount); }

    /** 서명 보고서의 가격으로 매수 최소 수령량과 수수료를 계산한다. */
    public Quote quoteBuyAtPrice(String vault, BigInteger krwAmount, BigInteger priceE8) {
        return quoteAtPrice(vault, "quoteBuyAtPrice", krwAmount, priceE8);
    }

    /** 서명 보고서의 가격으로 매도 최소 수령량과 수수료를 계산한다. */
    public Quote quoteSellAtPrice(String vault, BigInteger tokenAmount, BigInteger priceE8) {
        return quoteAtPrice(vault, "quoteSellAtPrice", tokenAmount, priceE8);
    }

    /** 서명·전송 계층이 사용할 Vault.buy(PriceReport,bytes) calldata를 생성한다. */
    public String encodeBuy(SignedPriceReport signed) {
        return FunctionEncoder.encode(new Function("buy", List.of(
                        new PriceReportStruct(signed.report()), signature(signed.signature())),
                List.of(new TypeReference<Uint256>() {})));
    }

    /** 서명·전송 계층이 사용할 Vault.sell(PriceReport,bytes) calldata를 생성한다. */
    public String encodeSell(SignedPriceReport signed) {
        return FunctionEncoder.encode(new Function("sell", List.of(
                        new PriceReportStruct(signed.report()), signature(signed.signature())),
                List.of(new TypeReference<Uint256>() {})));
    }

    private DynamicBytes signature(String value) {
        byte[] bytes = Numeric.hexStringToByteArray(value);
        if (bytes.length != 65) throw new IllegalArgumentException("가격 보고서 서명은 65바이트여야 합니다.");
        return new DynamicBytes(bytes);
    }

    /** Solidity PriceReport tuple과 필드 순서·정수 폭이 동일한 정적 ABI 구조체다. */
    static final class PriceReportStruct extends StaticStruct {
        PriceReportStruct(PriceReport report) {
            super(
                    bytes32(report.quoteId(), "quoteId"),
                    bytes32(report.symbolHash(), "symbolHash"),
                    new Uint256(report.priceE8()),
                    new Uint256(report.observedAt()),
                    new Uint256(report.validUntil()),
                    new Uint8(BigInteger.valueOf(report.side().code())),
                    new Uint256(report.inputAmount()),
                    new Uint256(report.minimumOutput()),
                    new Address(report.executor()));
        }

        private static Bytes32 bytes32(String value, String field) {
            byte[] bytes = Numeric.hexStringToByteArray(value);
            if (bytes.length != 32) throw new IllegalArgumentException(field + "는 32바이트여야 합니다.");
            return new Bytes32(bytes);
        }
    }

    /** 서명·전송 계층이 사용할 ERC-20 approve calldata를 생성한다. */
    public String encodeApprove(String spender, BigInteger amount) {
        return FunctionEncoder.encode(new Function("approve",
                List.of(new Address(spender), new Uint256(amount)),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {})));
    }

    /** 서명·전송 계층이 사용할 Oracle.updatePrice calldata를 생성한다. */
    public String encodeUpdatePrice(BigInteger priceE8) {
        return FunctionEncoder.encode(new Function("updatePrice", List.of(new Uint256(priceE8)), emptyList()));
    }

    private Quote quote(String vault, String method, BigInteger amount) {
        List<Type> values = call(vault, new Function(method, List.of(new Uint256(amount)), List.of(
                new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {})));
        return new Quote(asUint(values, 0), asUint(values, 1));
    }

    private Quote quoteAtPrice(String vault, String method, BigInteger amount, BigInteger priceE8) {
        List<Type> values = call(vault, new Function(method,
                List.of(new Uint256(amount), new Uint256(priceE8)), List.of(
                        new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {})));
        return new Quote(asUint(values, 0), asUint(values, 1));
    }

    private BigInteger singleUint(String contract, Function function) { return asUint(call(contract, function), 0); }

    private List<Type> call(String contract, Function function) {
        String data = FunctionEncoder.encode(function);
        try {
            EthCall response = web3j.ethCall(Transaction.createEthCallTransaction(null, contract, data),
                    DefaultBlockParameterName.LATEST).send();
            if (response.hasError()) {
                throw new BlockchainConfigurationException(
                        "컨트랙트 조회 실패(" + function.getName() + "): " + response.getError().getMessage());
            }
            if (response.getValue() == null || Numeric.cleanHexPrefix(response.getValue()).isEmpty()) {
                throw new BlockchainConfigurationException(
                        "컨트랙트 조회 결과가 비어 있습니다(" + function.getName() + "): " + contract);
            }
            return FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        } catch (IOException exception) {
            throw new BlockchainConfigurationException("RPC 호출에 실패했습니다: " + function.getName(), exception);
        }
    }

    private BigInteger asUint(List<Type> values, int index) {
        if (values.size() <= index || !(values.get(index).getValue() instanceof BigInteger value)) {
            throw new BlockchainConfigurationException("컨트랙트 반환값 ABI가 예상과 다릅니다.");
        }
        return value;
    }

    public record OraclePrice(BigInteger priceE8, BigInteger updatedAt) {}
    public record Quote(BigInteger outputAmount, BigInteger fee) {}
}
