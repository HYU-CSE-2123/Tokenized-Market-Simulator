package com.pricetrack.exchange.blockchain.transaction;

import com.pricetrack.exchange.blockchain.config.BlockchainProperties;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.EthTransaction;

/** 체인에서 찾을 수 없는 SIGNED 거래가 동일 raw transaction으로 재전송되는지 검증한다. */
class BlockchainTransactionSenderRecoveryTest {
    @Test
    @SuppressWarnings("unchecked")
    void rebroadcastsSignedTransactionMissingFromChain() throws Exception {
        Web3j web3j = mock(Web3j.class);
        BlockchainTransactionPersistence persistence = mock(BlockchainTransactionPersistence.class);
        Request<?, EthTransaction> lookupRequest = mock(Request.class);
        Request<?, EthSendTransaction> sendRequest = mock(Request.class);
        EthTransaction lookup = new EthTransaction();
        lookup.setResult(null);
        EthSendTransaction send = new EthSendTransaction();
        String txHash = "0x" + "ab".repeat(32);
        send.setResult(txHash);
        when(lookupRequest.send()).thenReturn(lookup);
        when(sendRequest.send()).thenReturn(send);
        doReturn(lookupRequest).when(web3j).ethGetTransactionByHash(txHash);
        doReturn(sendRequest).when(web3j).ethSendRawTransaction(anyString());

        BlockchainTransaction transaction = new BlockchainTransaction();
        transaction.setOrderId(42L);
        transaction.setStatus(BlockchainTransactionStatus.SIGNED);
        transaction.setTxHash(txHash);
        transaction.setRawTransaction("0xsigned");
        BlockchainTransactionSender sender = new BlockchainTransactionSender(properties(), web3j, persistence);

        sender.recoverSigned(transaction);

        verify(web3j).ethSendRawTransaction("0xsigned");
        verify(persistence).markSubmitted(txHash);
    }

    private BlockchainProperties properties() {
        return new BlockchainProperties(true, "http://127.0.0.1:8545", "", "", "", "", "");
    }
}
