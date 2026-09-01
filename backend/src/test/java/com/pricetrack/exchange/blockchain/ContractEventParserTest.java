package com.pricetrack.exchange.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import com.pricetrack.exchange.blockchain.ContractEventParser.EventValidationException;

class ContractEventParserTest {
    private final ContractEventParser parser = new ContractEventParser();
    private final String vault = "0xCf7Ed3AccA5a467e9e704C703E8D87F634fB0Fc9";
    private final String operator = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    @Test
    void parsesBoughtEvent() {
        TransactionReceipt receipt = receipt("Bought(address,uint256,uint256,uint256,uint256)",
                BigInteger.valueOf(100_000), BigInteger.valueOf(1_332), BigInteger.valueOf(100),
                BigInteger.valueOf(7_500_000_000_000L));

        var event = parser.parse(receipt, BlockchainTransactionType.BUY, vault, operator,
                BigInteger.valueOf(100_000));

        assertThat(event.type()).isEqualTo(BlockchainTransactionType.BUY);
        assertThat(event.outputAmount()).isEqualTo(BigInteger.valueOf(1_332));
        assertThat(event.priceE8()).isEqualTo(BigInteger.valueOf(7_500_000_000_000L));
    }

    @Test
    void rejectsDifferentInputAmount() {
        TransactionReceipt receipt = receipt("Sold(address,uint256,uint256,uint256,uint256)",
                BigInteger.TEN, BigInteger.ONE, BigInteger.ZERO, BigInteger.ONE);

        assertThatThrownBy(() -> parser.parse(receipt, BlockchainTransactionType.SELL,
                vault, operator, BigInteger.valueOf(11)))
                .isInstanceOf(EventValidationException.class)
                .hasMessageContaining("입력 수량");
    }

    private TransactionReceipt receipt(String signature, BigInteger... values) {
        Log log = new Log();
        log.setAddress(vault);
        log.setTopics(List.of(Hash.sha3String(signature), addressTopic(operator)));
        log.setData(uints(values));
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setLogs(List.of(log));
        return receipt;
    }

    private String addressTopic(String address) {
        return "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(address).toLowerCase();
    }

    private String uints(BigInteger... values) {
        StringBuilder encoded = new StringBuilder("0x");
        for (BigInteger value : values) encoded.append(String.format("%064x", value));
        return encoded.toString();
    }
}
