package com.pricetrack.exchange.blockchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthCall;

class ContractGatewayTest {
    private Web3j web3j;
    private Request<?, EthCall> request;
    private ContractGateway gateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        web3j = mock(Web3j.class);
        request = mock(Request.class);
        doReturn(request).when(web3j).ethCall(any(), any());
        gateway = new ContractGateway(web3j);
    }

    @Test
    void decodesOraclePriceTuple() throws Exception {
        respond(uints(BigInteger.valueOf(7_500_000_000_000L), BigInteger.valueOf(1234)));

        ContractGateway.OraclePrice result = gateway.getPrice(address());

        assertThat(result.priceE8()).isEqualTo("7500000000000");
        assertThat(result.updatedAt()).isEqualTo("1234");
    }

    @Test
    void decodesBuyQuoteTuple() throws Exception {
        respond(uints(BigInteger.valueOf(999), BigInteger.ONE));

        ContractGateway.Quote result = gateway.quoteBuy(address(), BigInteger.valueOf(1000));

        assertThat(result.outputAmount()).isEqualTo("999");
        assertThat(result.fee()).isEqualTo("1");
    }

    private void respond(String value) throws Exception {
        EthCall response = new EthCall();
        response.setResult(value);
        when(request.send()).thenReturn(response);
    }

    private String uints(BigInteger... values) {
        StringBuilder encoded = new StringBuilder("0x");
        for (BigInteger value : values) encoded.append(String.format("%064x", value));
        return encoded.toString();
    }

    private String address() {
        return "0x5FbDB2315678afecb367f032d93F642f64180aa3";
    }
}
