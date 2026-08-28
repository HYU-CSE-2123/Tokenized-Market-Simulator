package com.pricetrack.exchange.blockchain;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.utils.Numeric;

import static java.util.Collections.emptyList;

/** 컨트랙트 ABI의 읽기 전용 함수만 호출하는 Phase 3.1 gateway. */
public class ContractGateway {
    private final Web3j web3j;

    public ContractGateway(Web3j web3j) { this.web3j = web3j; }

    public BigInteger balanceOf(String contract, String account) {
        return singleUint(contract, new Function("balanceOf",
                List.of(new Address(account)), List.of(new TypeReference<Uint256>() {})));
    }

    public BigInteger allowance(String token, String owner, String spender) {
        return singleUint(token, new Function("allowance",
                List.of(new Address(owner), new Address(spender)),
                List.of(new TypeReference<Uint256>() {})));
    }

    public BigInteger feeBps(String vault) {
        return singleUint(vault, new Function("feeBps", emptyList(),
                List.of(new TypeReference<Uint256>() {})));
    }

    public OraclePrice getPrice(String oracle) {
        List<Type> values = call(oracle, new Function("getPrice", emptyList(), List.of(
                new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {})));
        return new OraclePrice(asUint(values, 0), asUint(values, 1));
    }

    public Quote quoteBuy(String vault, BigInteger krwAmount) { return quote(vault, "quoteBuy", krwAmount); }
    public Quote quoteSell(String vault, BigInteger tokenAmount) { return quote(vault, "quoteSell", tokenAmount); }

    public String encodeBuy(BigInteger krwAmount) {
        return FunctionEncoder.encode(new Function("buy", List.of(new Uint256(krwAmount)),
                List.of(new TypeReference<Uint256>() {})));
    }

    public String encodeSell(BigInteger tokenAmount) {
        return FunctionEncoder.encode(new Function("sell", List.of(new Uint256(tokenAmount)),
                List.of(new TypeReference<Uint256>() {})));
    }

    public String encodeApprove(String spender, BigInteger amount) {
        return FunctionEncoder.encode(new Function("approve",
                List.of(new Address(spender), new Uint256(amount)),
                List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {})));
    }

    private Quote quote(String vault, String method, BigInteger amount) {
        List<Type> values = call(vault, new Function(method, List.of(new Uint256(amount)), List.of(
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
