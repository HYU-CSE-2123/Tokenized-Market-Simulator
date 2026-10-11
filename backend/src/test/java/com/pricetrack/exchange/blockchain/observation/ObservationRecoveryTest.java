package com.pricetrack.exchange.blockchain.observation;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.pricetrack.exchange.blockchain.config.*;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.blockchain.settlement.*;
import com.pricetrack.exchange.blockchain.reconciliation.BlockchainReconciliationService;
import com.pricetrack.exchange.order.OrderRepository;
import org.web3j.protocol.Web3j;

class ObservationRecoveryTest {
    @Test void recorderFailureCannotReplaceRecoveryAndGeneralErrorDoesNotClaimEthFailure(){
        var config=mock(BlockchainProperties.class);when(config.enabled()).thenReturn(true);
        var repo=mock(BlockchainTransactionRepository.class);var sender=mock(BlockchainTransactionSender.class);
        var record=mock(ObservationRecorder.class);Instant at=Instant.now();when(record.start()).thenReturn(at);
        var tx=new BlockchainTransaction();tx.setId(9L);tx.setStatus(BlockchainTransactionStatus.SIGNED);
        when(repo.findAllByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(tx));
        doThrow(new IllegalStateException("observation unavailable")).when(record).record(any(),any(),any(),any());
        var service=new BlockchainReconciliationService(config,new BlockchainReconciliationProperties(1000,1000,1),repo,sender,
            mock(OrderRepository.class),mock(ContractEventParser.class),mock(OnchainSettlementService.class),mock(OraclePriceSettlementService.class),mock(Web3j.class),record);
        service.reconcilePendingTransactions();verify(sender).recoverSigned(tx);
        verify(record).record(9L,at,ObservationRecorder.Phase.SIGNED_RECOVERY,ObservationRecorder.Result.SUBMISSION_RECORDED);
        doThrow(new IllegalStateException("RPC response lost / secret")).when(sender).recoverSigned(tx);
        service.reconcilePendingTransactions();verify(record).record(9L,at,ObservationRecorder.Phase.SIGNED_RECOVERY,ObservationRecorder.Result.ATTEMPT_UNAVAILABLE);
        verify(sender,times(2)).recoverSigned(tx);verifyNoMoreInteractions(sender);
        doThrow(new IllegalStateException("observation start unavailable")).when(record).start();
        service.reconcilePendingTransactions();verify(sender,times(3)).recoverSigned(tx);
    }
}
