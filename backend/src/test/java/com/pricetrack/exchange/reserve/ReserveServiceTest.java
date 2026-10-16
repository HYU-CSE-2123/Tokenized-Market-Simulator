package com.pricetrack.exchange.reserve;

import static com.pricetrack.exchange.reserve.ReserveModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class ReserveServiceTest {
    static BigInteger n(long value) { return BigInteger.valueOf(value).multiply(BigInteger.TEN.pow(18)); }
    static final Block BLOCK=new Block(BigInteger.valueOf(3),"block-hash");
    static final Identity ID=new Identity(BigInteger.valueOf(31337),"operator","krw","sec","vault","oracle","signer",Map.of("krw","code"));
    static Chain state(BigInteger k,BigInteger s,BigInteger vk,BigInteger supply) {
        return new Chain(ID,BLOCK,k,s,vk,BigInteger.ZERO,n(10000000),supply,n(10000000),BigInteger.TEN,true,n(1).toString());
    }
    static final Chain INITIAL=state(n(5000000),BigInteger.ZERO,n(5000000),BigInteger.ZERO);
    static DbCut empty() { return new DbCut(Instant.EPOCH,List.of(),List.of(),List.of(),List.of(),List.of()); }
    ReserveStore store; ReserveChainReader reader; ReserveService service;
    @BeforeEach void setup() {
        store=mock(ReserveStore.class); reader=mock(ReserveChainReader.class);
        service=new ReserveService(store,reader,"test-execution");
        when(store.baseline()).thenReturn(Optional.of(new Baseline("anchor","test-execution",Instant.EPOCH,1,INITIAL)));
        when(store.cut()).thenReturn(empty()); when(reader.head()).thenReturn(BLOCK);
        when(reader.read(BLOCK)).thenReturn(INITIAL); when(reader.canonical(any())).thenReturn(true);
        when(reader.receiptMatches(any(),any(),any(),any())).thenReturn(true);
        when(store.save(any())).thenAnswer(i->i.getArgument(0));
        var mapper=new ObjectMapper().findAndRegisterModules();
        when(store.encode(any())).thenAnswer(i->mapper.writeValueAsString(i.getArgument(0)));
    }
    static DbCut funded(long user,long amount) {
        return new DbCut(Instant.EPOCH,List.of(new Balance(user,"mKRW",n(amount),BigInteger.ZERO),
            new Balance(user,"mSEC",BigInteger.ZERO,BigInteger.ZERO)),List.of(),List.of(),List.of(),
            List.of(new Grant("grant",user,n(amount),Instant.EPOCH)));
    }
    @Test void faucetAndUnequalOperatorBalanceAreNormal() {
        when(store.cut()).thenReturn(funded(9,1000000));
        var result=service.run(1); assertThat(result.status()).isEqualTo("MATCH");
        assertThat(result.liquidity().operatorAllocationReference()).isEqualTo("5.0000");
    }
    @Test void matchCanHaveInsufficientOperatorFunds() {
        when(store.cut()).thenReturn(funded(9,6000000));
        var r=service.run(1); assertThat(r.status()).isEqualTo("MATCH");
        assertThat(r.liquidity().operatorAllocationReference()).isEqualTo("0.8333");
    }
    @Test void unexplainedUserBalanceIsMismatch() {
        when(store.cut()).thenReturn(new DbCut(Instant.EPOCH,List.of(new Balance(9,"mKRW",n(5),BigInteger.ZERO)),List.of(),List.of(),List.of(),List.of()));
        assertThat(service.run(1).status()).isEqualTo("MISMATCH");
    }
    @Test void externalOperatorLossIsMismatch() {
        when(reader.read(BLOCK)).thenReturn(state(n(4999999),BigInteger.ZERO,n(5000000),BigInteger.ZERO));
        assertThat(service.run(1).status()).isEqualTo("MISMATCH");
    }
    @Test void stableUnexpectedTransfersAreNotSilentlyExplained() {
        when(reader.hasUnknownTransfers(any(),any(),any())).thenReturn(true);
        assertThat(service.run(1).checks()).anyMatch(c->c.name().equals("UNEXPECTED_CHAIN_MOVEMENT") && !c.matches());
    }
    @Test void blockMovementWinsOverProvisionalMismatch() {
        when(reader.read(BLOCK)).thenReturn(state(n(1),BigInteger.ZERO,n(5000000),BigInteger.ZERO));
        when(reader.head()).thenReturn(BLOCK,new Block(BigInteger.valueOf(4),"new"));
        var r=service.run(1); assertThat(r.status()).isEqualTo("INCONCLUSIVE"); assertThat(r.reason()).isEqualTo("CUT_CHANGED");
        assertThat(r.checks()).isEmpty();
    }
    @Test void databaseMovementIsInconclusive() {
        when(store.cut()).thenReturn(empty(),funded(9,1));
        assertThat(service.run(1).reason()).isEqualTo("CUT_CHANGED");
    }
    @Test void pendingOrderNotComparedAsCorruption() {
        var order=new OrderRow(1,9,"BUY","ONCHAIN","PENDING_ONCHAIN",n(1),"tx",Instant.EPOCH);
        when(store.cut()).thenReturn(new DbCut(Instant.EPOCH,List.of(),List.of(order),List.of(),List.of(),List.of()));
        assertThat(service.run(1).reason()).isEqualTo("UNSETTLED_ORDER_OR_REVIEW");
    }
    @Test void legacyUnknownNeverInferredFromHash() {
        var order=new OrderRow(1,9,"BUY","UNKNOWN","FAILED",n(1),"tx",Instant.EPOCH);
        when(store.cut()).thenReturn(new DbCut(Instant.EPOCH,List.of(),List.of(order),List.of(),List.of(),List.of()));
        assertThat(service.run(1).reason()).isEqualTo("UNKNOWN_ORDER_EXECUTION");
    }
    @Test void reviewRequiredDoesNotUnlockOrSettle() {
        var tx=new TxRow(1,1L,"BUY","REVIEW_REQUIRED","tx","operator",1L,3L);
        when(store.cut()).thenReturn(new DbCut(Instant.EPOCH,List.of(),List.of(),List.of(),List.of(tx),List.of()));
        assertThat(service.run(1).status()).isEqualTo("INCONCLUSIVE");
        verify(store,never()).createBaseline(any());
    }
    @Test void dependencyFailureIsSanitizedAndRecorded() {
        when(reader.head()).thenThrow(new RuntimeException("secret-private-url"));
        var r=service.run(1); assertThat(r.status()).isEqualTo("UNAVAILABLE");
        assertThat(r.reason()).isEqualTo("DEPENDENCY_READ_FAILED");
        assertThat(store.encode(r)).doesNotContain("secret-private-url");
    }
    @Test void limitsAreInconclusive() {
        when(store.cut()).thenThrow(new ReserveStore.CutLimitException());
        assertThat(service.run(1).reason()).isEqualTo("BOUNDED_READ_LIMIT");
    }
    @Test void immutableAnchorCannotBeRecreated() {
        assertThatThrownBy(()->service.createBaseline(1)).hasMessageContaining("IMMUTABLE_BASELINE_EXISTS");
        verify(store,never()).createBaseline(any());
    }
    @Test void freshAnchorRejectsExistingCredit() {
        when(store.baseline()).thenReturn(Optional.empty()); when(store.cut()).thenReturn(funded(9,1));
        assertThatThrownBy(()->service.createBaseline(1)).hasMessageContaining("FRESH_ZERO_LEDGER_REQUIRED");
    }
    @Test void environmentChangeIsNotMismatch() {
        service=new ReserveService(store,reader,"another-execution");
        assertThat(service.run(1).reason()).isEqualTo("BASELINE_ENVIRONMENT_NOT_VERIFIABLE");
    }
    @Test void missingBaselineIsInconclusiveWithoutChainUse() {
        when(store.baseline()).thenReturn(Optional.empty());
        assertThat(service.run(1).reason()).isEqualTo("BASELINE_REQUIRED"); verifyNoInteractions(reader);
    }
    @Test void lockedAmountIsNotAddedToTotalAndTerminalLockIsMismatch() {
        var db=funded(9,1000000);
        var locked=new DbCut(db.at(),List.of(new Balance(9,"mKRW",n(1000000),n(10))),db.orders(),db.trades(),db.transactions(),db.grants());
        var checks=ReserveCalculator.calculate(locked,INITIAL,INITIAL);
        assertThat(checks).anyMatch(c->c.name().equals("USER_9:mKRW") && c.matches());
        assertThat(checks).anyMatch(c->c.name().equals("LOCK_9:mKRW") && !c.matches());
    }
    @Test void exactBuySellFeeAndSupplyConservation() {
        BigInteger buyIn=n(750000),qty=new BigInteger("9990000000000000000"),sellNet=new BigInteger("798400800000000000000000");
        var t1=new TradeRow(1,1,9,"mSEC","ONCHAIN","BUY",qty,buyIn,n(750),new BigInteger("7500000000000"),"buy",Instant.EPOCH);
        var t2=new TradeRow(2,2,9,"mSEC","ONCHAIN","SELL",qty,sellNet,new BigInteger("799200000000000000000"),new BigInteger("8000000000000"),"sell",Instant.EPOCH);
        var db=new DbCut(Instant.EPOCH,List.of(new Balance(9,"mKRW",n(1000000).subtract(buyIn).add(sellNet),BigInteger.ZERO),
            new Balance(9,"mSEC",BigInteger.ZERO,BigInteger.ZERO)),List.of(),List.of(t1,t2),List.of(),List.of(new Grant("f",9,n(1000000),Instant.EPOCH)));
        var delta=sellNet.subtract(buyIn);
        assertThat(ReserveCalculator.calculate(db,INITIAL,state(n(5000000).add(delta),BigInteger.ZERO,n(5000000).subtract(delta),BigInteger.ZERO)))
            .allMatch(Check::matches);
    }
    DbCut filledBuy(String mode,String txType) {
        String hash=mode.equals("ONCHAIN")?"buy":null;
        var order=new OrderRow(1,9,"BUY",mode,"FILLED",n(1000),hash,Instant.EPOCH);
        var trade=new TradeRow(1,1,9,"mSEC",mode,"BUY",n(1),n(1000),n(1),BigInteger.valueOf(100000000000L),hash,Instant.EPOCH);
        return new DbCut(Instant.EPOCH,List.of(new Balance(9,"mKRW",n(9000),BigInteger.ZERO),new Balance(9,"mSEC",n(1),BigInteger.ZERO)),
            List.of(order),List.of(trade),txType==null?List.of():List.of(new TxRow(1,1L,txType,"CONFIRMED","buy","operator",1L,3L)),
            List.of(new Grant("grant",9,n(10000),Instant.EPOCH)));
    }
    @Test void oppositeTransactionDirectionIsInconclusive() {
        when(store.cut()).thenReturn(filledBuy("ONCHAIN","SELL"));
        when(reader.read(BLOCK)).thenReturn(state(n(4999000),n(1),n(5001000),n(1)));
        assertThat(service.run(1).status()).isEqualTo("INCONCLUSIVE");
    }
    @Test void dbOnlyTradeCannotHaveConfirmedChainTransaction() {
        when(store.cut()).thenReturn(filledBuy("DB_ONLY","BUY"));
        assertThat(service.run(1).status()).isEqualTo("INCONCLUSIVE");
    }
    @Test void dbOnlyTradeChangesLedgerButNotChainExpectations() {
        when(store.cut()).thenReturn(filledBuy("DB_ONLY",null));
        assertThat(service.run(1).status()).isEqualTo("MATCH");
        verify(reader,never()).receiptMatches(any(),any(),any(),any());
    }
    @Test void unverifiableReceiptCannotBeMatchOrMismatch() {
        when(store.cut()).thenReturn(filledBuy("ONCHAIN","BUY"));
        when(reader.receiptMatches(any(),any(),any(),any())).thenReturn(false);
        assertThat(service.run(1).reason()).isEqualTo("RECEIPT_NOT_VERIFIABLE");
    }
    @Test void contractRoleChangeAtStableCutIsMismatch() {
        var c=INITIAL;
        when(reader.read(BLOCK)).thenReturn(new Chain(c.identity(),c.block(),c.operatorKrw(),c.operatorSec(),c.vaultKrw(),c.vaultSec(),
            c.krwSupply(),c.secSupply(),c.allowance(),c.feeBps(),false,c.operatorEthWei()));
        assertThat(service.run(1).status()).isEqualTo("MISMATCH");
    }
    @Test void changedCanonicalHashIsInconclusive() {
        when(reader.canonical(BLOCK)).thenReturn(true,false);
        assertThat(service.run(1).reason()).isEqualTo("CUT_CHANGED");
    }
    @Test void zeroOrUnknownEthDoesNotPromiseGasSufficiencyOrCorruptLedgerVerdict() {
        var c=INITIAL;
        for(String eth:new String[]{"0",null}) {
            when(reader.read(BLOCK)).thenReturn(new Chain(c.identity(),c.block(),c.operatorKrw(),c.operatorSec(),c.vaultKrw(),c.vaultSec(),
                c.krwSupply(),c.secSupply(),c.allowance(),c.feeBps(),true,eth));
            var result=service.run(1); assertThat(result.status()).isEqualTo("MATCH");
            assertThat(result.liquidity().operatorEthWei()).isEqualTo(eth);
            assertThat(result.liquidity().gasAssessment()).isEqualTo(eth==null?"BALANCE_UNAVAILABLE_NOT_ESTIMATED":"READ_ONLY_NOT_ESTIMATED");
        }
    }
}
