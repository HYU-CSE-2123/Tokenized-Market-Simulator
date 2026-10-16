package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.reserve.ReserveModels;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuditUnitTest {
    private final AuditValidator validator=new AuditValidator(new ContractEventParser());
    private OrderRow order(String mode,String status) { return new OrderRow(1,2,"mSEC","BUY",mode,status,BigInteger.TEN,null,Instant.EPOCH); }
    private TradeRow trade(long uid,String symbol,String side,String mode,BigInteger input,String hash) { return new TradeRow(1,1,uid,symbol,side,mode,BigInteger.ONE,input,BigInteger.ZERO,BigInteger.ONE,hash,Instant.EPOCH); }
    @Test void legacyNeverInferred() {
        assertThat(validator.inspect(order("UNKNOWN","FILLED"),List.of(),List.of(),List.of(),null,null,null,1).reason()).isEqualTo("LEGACY_EXECUTION_UNKNOWN");
    }
    @Test void dbOnlyMatchIsNotChainMatch() {
        var item=validator.inspect(order("DB_ONLY","FILLED"),List.of(trade(2,"mSEC","BUY","DB_ONLY",BigInteger.TEN,null)),List.of(),List.of(),null,null,null,1);
        assertThat(item.verdict()).isEqualTo("MATCH");assertThat(item.limitations()).contains("CHAIN_NOT_APPLICABLE");
    }
    @ParameterizedTest @ValueSource(strings={"USER","SYMBOL","SIDE","MODE","INPUT","HASH","MISSING","DUPLICATE"})
    void dbContradictions(String mismatch) {
        var t=trade(mismatch.equals("USER")?3:2,mismatch.equals("SYMBOL")?"bad":"mSEC",mismatch.equals("SIDE")?"SELL":"BUY",mismatch.equals("MODE")?"ONCHAIN":"DB_ONLY",mismatch.equals("INPUT")?BigInteger.ONE:BigInteger.TEN,mismatch.equals("HASH")?"0xbad":null);
        var rows=mismatch.equals("MISSING")?List.<TradeRow>of():mismatch.equals("DUPLICATE")?List.of(t,t):List.of(t);
        assertThat(validator.inspect(order("DB_ONLY","FILLED"),rows,List.of(),List.of(),null,null,null,1).verdict()).isEqualTo("MISMATCH");
    }
    @Test void preSubmissionFailureIsNotMissingSuccessfulTrade() {
        assertThat(validator.inspect(order("ONCHAIN","FAILED"),List.of(),List.of(),List.of(),null,null,null,1).reason()).isEqualTo("NOT_EXECUTED_OR_UNPROVEN");
    }
    @ParameterizedTest @ValueSource(strings={"REQUESTED","PENDING_ONCHAIN"})
    void pendingNotExecuted(String state) {
        assertThat(validator.inspect(order("ONCHAIN",state),List.of(),List.of(),List.of(),null,null,null,1).verdict()).isEqualTo("INCONCLUSIVE");
    }
    @Test void missingConfirmedTransactionIsStructuralMismatch() {
        assertThat(validator.inspect(order("ONCHAIN","FILLED"),List.of(),List.of(),List.of(),null,null,null,1).verdict()).isEqualTo("MISMATCH");
    }
    @Test void secretsAreNotSerializedIntoManifest() throws Exception {
        var tx=new TxRow(1,1L,"BUY","CONFIRMED","hash","sender",1L,1L,"digest","raw-secret");
        var q=new QuoteRow("quote",1L,2,"mSEC","BUY","CONSUMED",BigInteger.ONE,BigInteger.ONE,BigInteger.ONE,"executor",Instant.EPOCH,Instant.EPOCH,"digest","signature-secret");
        String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(new Cut(Instant.EPOCH,"snapshot",null,List.of(),List.of(),List.of(tx),List.of(q)));
        assertThat(json).doesNotContain("raw-secret","signature-secret");assertThat(json).contains("rawHash","signatureHash");
    }
    @Test void jdbcTimestampUsesExplicitUtcCalendar() throws Exception {
        var row=mock(java.sql.ResultSet.class);Instant instant=Instant.parse("2026-10-10T00:00:00Z");
        when(row.getTimestamp(eq(1),any(Calendar.class))).thenAnswer(c->{assertThat(((Calendar)c.getArgument(1)).getTimeZone().getID()).isEqualTo("UTC");return java.sql.Timestamp.from(instant);});
        assertThat(AuditStore.utc(row,1)).isEqualTo(instant);verify(row,never()).getTimestamp(1);
    }
    @Test void apiMetadataDoesNotDumpSourceRows() {
        AuditStore store=mock(AuditStore.class);var cut=new Cut(Instant.EPOCH,"snapshot",null,List.of(order("DB_ONLY","FILLED")),List.of(),List.of(),List.of());
        var run=new Run("id",1,"COMPLETED","MATCH","done",Instant.EPOCH,Instant.EPOCH,null,null,"fixture","digest",cut,new Coverage(true,true,true,true,1,0,1,0),1,0,0);
        when(store.detail("id")).thenReturn(Optional.of(run));var service=new AuditService(store,mock(AuditChain.class),validator,"fixture",1,30);
        try {var view=service.detail("id");assertThat(view.manifest().orders()).isEmpty();assertThat(view.coverage().sourceOrders()).isEqualTo(1);assertThat(view.manifestHash()).isEqualTo("digest");}finally {service.stop();}
    }
    @ParameterizedTest @ValueSource(strings={"MATCH","MISMATCH","INCONCLUSIVE","SCAN_FAILED","ENVIRONMENT","CUT_CHANGED","DEADLINE","DB_MISMATCH_RPC_MISSING"})
    void aggregateCoverageIsSeparate(String scenario) throws Exception {
        AuditStore store=mock(AuditStore.class); AuditChain chain=mock(AuditChain.class);
        AuditChain.Session session=mock(AuditChain.Session.class); AuditValidator compare=mock(AuditValidator.class);
        var baseline=mock(ReserveModels.Baseline.class); var state=mock(ReserveModels.Chain.class);
        when(baseline.executionId()).thenReturn("fixture");when(baseline.chain()).thenReturn(state);
        when(state.block()).thenReturn(new ReserveModels.Block(BigInteger.ZERO,"anchor"));
        var cut=new Cut(Instant.EPOCH,"snapshot",baseline,List.of(order("ONCHAIN","FILLED")),List.of(),List.of(),List.of());
        when(store.cut()).thenReturn(cut);when(store.fingerprint(any())).thenReturn("stable");
        when(chain.session()).thenReturn(session);when(session.head()).thenReturn(new Block(BigInteger.ONE,"block",BigInteger.ONE));
        when(session.environment(any())).thenReturn(!scenario.equals("ENVIRONMENT"));when(session.canonical(any())).thenReturn(!scenario.equals("CUT_CHANGED"));
        when(session.scan(any(),any())).thenReturn(List.of());
        if(scenario.equals("SCAN_FAILED")) when(session.scan(any(),any())).thenThrow(new Unavailable("CHAIN_SCAN_LIMIT"));
        if(scenario.equals("DEADLINE")) when(session.head()).thenThrow(new Unavailable("RUN_DEADLINE"));
        String individual=Set.of("MATCH","MISMATCH","INCONCLUSIVE").contains(scenario)?scenario:"MATCH";
        when(compare.inspect(any(),anyList(),anyList(),anyList(),any(),any(),any(),anyInt())).thenReturn(AuditValidator.simple("order:1",order("ONCHAIN","FILLED"),individual,"fixture"));
        if(scenario.equals("DB_MISMATCH_RPC_MISSING")) when(compare.inspect(any(),anyList(),anyList(),anyList(),any(),any(),any(),anyInt())).thenReturn(new Item("order:1",1L,"ONCHAIN","BUY","ONCHAIN","MISMATCH","RECEIPT_OR_TRANSACTION_MISSING",null,null,null,List.of(new Check("ONE_TRADE","1","0",false)),List.of("CHAIN_EVIDENCE_INCOMPLETE")));
        List<Run> saved=new java.util.concurrent.CopyOnWriteArrayList<>();doAnswer(c->{saved.add(c.getArgument(0));return null;}).when(store).finish(any(),anyList());
        var service=new AuditService(store,chain,compare,"fixture",1,30);
        try {
            service.start(1);
            long end=System.nanoTime()+3_000_000_000L;while(saved.isEmpty() && System.nanoTime()<end) Thread.sleep(5);
            assertThat(saved).hasSize(1);
            assertThat(saved.getFirst().verdict()).isEqualTo(Set.of("MATCH","MISMATCH").contains(scenario)?scenario:"INCONCLUSIVE");
            if(scenario.equals("SCAN_FAILED")) assertThat(saved.getFirst().coverage().chainComplete()).isFalse();
            if(scenario.equals("DB_MISMATCH_RPC_MISSING")) {assertThat(saved.getFirst().mismatch()).isEqualTo(1);assertThat(saved.getFirst().coverage().chainComplete()).isFalse();assertThat(saved.getFirst().lifecycle()).isEqualTo("INCOMPLETE");}
            verify(store,times(1)).finish(any(),anyList());
        } finally {service.stop();}
    }
}
