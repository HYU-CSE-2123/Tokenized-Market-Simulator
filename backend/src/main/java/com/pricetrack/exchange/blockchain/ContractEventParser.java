package com.pricetrack.exchange.blockchain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

@Component
public class ContractEventParser {
    private static final Event BOUGHT = new Event("Bought",
            List.of(new TypeReference<Address>(true) {}, new TypeReference<Uint256>() {},
                    new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {},
                    new TypeReference<Uint256>() {}));
    private static final Event SOLD = new Event("Sold",
            List.of(new TypeReference<Address>(true) {}, new TypeReference<Uint256>() {},
                    new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {},
                    new TypeReference<Uint256>() {}));

    public SettlementEvent parse(TransactionReceipt receipt, BlockchainTransactionType type,
            String vaultAddress, String operatorAddress, BigInteger expectedInput) {
        Event expectedEvent = switch (type) {
            case BUY -> BOUGHT;
            case SELL -> SOLD;
            default -> throw new EventValidationException("정산할 수 없는 트랜잭션 종류입니다: " + type);
        };
        List<SettlementEvent> matches = new ArrayList<>();
        for (Log log : receipt.getLogs()) {
            if (!sameAddress(log.getAddress(), vaultAddress) || log.getTopics().isEmpty()
                    || !EventEncoder.encode(expectedEvent).equalsIgnoreCase(log.getTopics().getFirst())) continue;
            matches.add(decode(log, type, expectedEvent));
        }
        if (matches.size() != 1) {
            throw new EventValidationException("예상한 Vault 체결 이벤트 수가 1개가 아닙니다: " + matches.size());
        }
        SettlementEvent result = matches.getFirst();
        if (!sameAddress(result.user(), operatorAddress)) {
            throw new EventValidationException("체결 이벤트의 사용자가 운영자 주소와 다릅니다.");
        }
        if (!result.inputAmount().equals(expectedInput)) {
            throw new EventValidationException("체결 이벤트 입력 수량이 주문과 다릅니다.");
        }
        return result;
    }

    private SettlementEvent decode(Log log, BlockchainTransactionType type, Event event) {
        if (log.getTopics().size() < 2) throw new EventValidationException("이벤트 indexed user가 없습니다.");
        Type userType = FunctionReturnDecoder.decodeIndexedValue(log.getTopics().get(1),
                new TypeReference<Address>() {});
        List<Type> values = FunctionReturnDecoder.decode(log.getData(), event.getNonIndexedParameters());
        if (values.size() != 4) throw new EventValidationException("이벤트 ABI 반환값 수가 올바르지 않습니다.");
        return new SettlementEvent(type, userType.getValue().toString(), uint(values, 0),
                uint(values, 1), uint(values, 2), uint(values, 3));
    }

    private BigInteger uint(List<Type> values, int index) {
        Object value = values.get(index).getValue();
        if (!(value instanceof BigInteger number)) {
            throw new EventValidationException("이벤트 uint256 값이 올바르지 않습니다.");
        }
        return number;
    }

    private boolean sameAddress(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    public record SettlementEvent(BlockchainTransactionType type, String user,
            BigInteger inputAmount, BigInteger outputAmount, BigInteger fee, BigInteger priceE8) {}

    public static class EventValidationException extends RuntimeException {
        public EventValidationException(String message) { super(message); }
    }
}
