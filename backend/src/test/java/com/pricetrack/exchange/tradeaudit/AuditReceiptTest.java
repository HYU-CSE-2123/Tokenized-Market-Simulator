package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.blockchain.contract.*;
import com.pricetrack.exchange.blockchain.oracle.*;
import com.pricetrack.exchange.reserve.ReserveModels;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.web3j.crypto.*;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;

/** Constructed cryptographic/ABI evidence, not claims of impossible events on an actual EVM. */
class AuditReceiptTest {
    final BigInteger z=BigInteger.ZERO,chainId=BigInteger.valueOf(31337),input=BigInteger.TEN.pow(23),price=BigInteger.valueOf(75000).multiply(BigInteger.TEN.pow(8));
    final String operator=Credentials.create(ECKeyPair.create(BigInteger.valueOf(7))).getAddress(),signer=Credentials.create(ECKeyPair.create(BigInteger.valueOf(17))).getAddress();
    final String vault="0x"+"a".repeat(40),oracle="0x"+"b".repeat(40),hash="0x"+"c".repeat(64),blockHash="0x"+"d".repeat(64),quoteId="0x"+"1".repeat(64);
    final AuditValidator validator=new AuditValidator(new ContractEventParser());
    Item fixture(String defect,String side) throws Exception {
        BigInteger fee=(side.equals("BUY")?input:input.multiply(price).divide(BigInteger.TEN.pow(8))).divide(BigInteger.valueOf(1000));
        BigInteger output=side.equals("BUY")?input.subtract(fee).multiply(BigInteger.TEN.pow(8)).divide(price):input.multiply(price).divide(BigInteger.TEN.pow(8)).subtract(fee);
        var report=new PriceReport(quoteId,PriceReportEip712.hashText("mSEC"),price,BigInteger.valueOf(100),BigInteger.valueOf(130),PriceReport.Side.valueOf(side),input,output,operator);
        var sig=Sign.signMessage(PriceReportEip712.digest(report,chainId,oracle),ECKeyPair.create(BigInteger.valueOf(17)),false);
        byte[] bytes=new byte[65];System.arraycopy(sig.getR(),0,bytes,0,32);System.arraycopy(sig.getS(),0,bytes,32,32);bytes[64]=sig.getV()[0];String signature=Numeric.toHexString(bytes);
        var q=new QuoteRow(quoteId,1L,defect.equals("OWNER")?3:2,"mSEC",side,"CONSUMED",price,input,output,operator,Instant.ofEpochSecond(100),Instant.ofEpochSecond(130),AuditStore.digest(signature),signature);
        var order=new OrderRow(1,2,"mSEC",side,"ONCHAIN",defect.equals("FAILED")?"FAILED":"FILLED",input,hash,Instant.EPOCH);
        var trade=new TradeRow(1,1,defect.equals("USER_RECEIPT_MISSING")?3:2,"mSEC",side,"ONCHAIN",side.equals("BUY")?output:input,side.equals("BUY")?input:output,defect.equals("FEE") || defect.equals("FEE_HISTORY_MISSING")?fee.add(BigInteger.ONE):fee,price,hash,Instant.EPOCH);
        var tx=new TxRow(1,1L,side,defect.equals("FAILED")?"FAILED":"CONFIRMED",hash,operator,1L,2L,null,null);
        var identity=new ReserveModels.Identity(chainId,operator,vault,vault,vault,oracle,signer,Map.of());
        var baseline=new ReserveModels.Baseline("fixture","fixture",Instant.EPOCH,1,new ReserveModels.Chain(identity,new ReserveModels.Block(BigInteger.ONE,"anchor"),z,z,z,z,z,z,z,z,true,null));
        Transaction rpcTx=mock(Transaction.class);
        String data=side.equals("BUY")?new ContractGateway(null).encodeBuy(new SignedPriceReport(report,signature,fee)):new ContractGateway(null).encodeSell(new SignedPriceReport(report,signature,fee));
        when(rpcTx.getInput()).thenReturn(defect.equals("CALLDATA")?"0x00":data);when(rpcTx.getHash()).thenReturn(hash);
        when(rpcTx.getBlockNumber()).thenReturn(BigInteger.TWO);when(rpcTx.getTransactionIndex()).thenReturn(z);
        when(rpcTx.getBlockHash()).thenReturn(blockHash);when(rpcTx.getFrom()).thenReturn(operator);when(rpcTx.getTo()).thenReturn(defect.equals("DESTINATION")?oracle:vault);
        when(rpcTx.getNonce()).thenReturn(defect.equals("NONCE")?BigInteger.TWO:BigInteger.ONE);when(rpcTx.getValue()).thenReturn(z);
        TransactionReceipt receipt=new TransactionReceipt();receipt.setStatus(defect.equals("FAILED")?"0x0":"0x1");receipt.setTransactionHash(hash);receipt.setBlockHash(blockHash);receipt.setBlockNumber("0x2");receipt.setTransactionIndex("0x0");receipt.setFrom(operator);receipt.setTo(vault);
        Log bought=new Log();bought.setAddress(vault);bought.setTopics(List.of(Hash.sha3String(side.equals("BUY")?"Bought(address,uint256,uint256,uint256,uint256)":"Sold(address,uint256,uint256,uint256,uint256)"),topicAddress(operator)));bought.setData(uints(input,output,fee,price));
        Log consumed=new Log();consumed.setAddress(oracle);consumed.setTopics(List.of(Hash.sha3String("PriceReportConsumed(bytes32,uint256,uint256,uint256,address)"),defect.equals("QUOTE_ID")?"0x"+"2".repeat(64):quoteId,topicAddress(signer)));consumed.setData(uints(price,BigInteger.valueOf(100),BigInteger.valueOf(130)));
        receipt.setLogs(defect.equals("MISSING_ORACLE")?List.of(bought):defect.equals("DUPLICATE_ORACLE")?List.of(bought,consumed,consumed):defect.equals("DUPLICATE_VAULT")?List.of(bought,bought,consumed):List.of(bought,consumed));
        Block block=new Block(BigInteger.TWO,blockHash,BigInteger.valueOf(defect.equals("EXECUTION_EXPIRED")?131:120));
        var evidence=new AuditChain.Evidence(rpcTx,receipt,block,defect.equals("HISTORY_MISSING") || defect.equals("FEE_HISTORY_MISSING")?null:defect.equals("SIGNER")?operator:signer,defect.equals("FEE_HISTORY")?BigInteger.valueOf(20):BigInteger.TEN,List.of());
        var session=mock(AuditChain.Session.class);when(session.evidence(hash)).thenReturn(evidence);when(session.canonical(any())).thenReturn(true);
        if(defect.endsWith("RECEIPT_MISSING")) when(session.evidence(hash)).thenThrow(new Unavailable("RECEIPT_OR_TRANSACTION_MISSING"));
        return validator.inspect(order,defect.equals("FAILED") || defect.equals("TRADE_RECEIPT_MISSING")?List.of():List.of(trade),List.of(tx),List.of(q),session,new Block(BigInteger.TWO,blockHash,BigInteger.valueOf(120)),baseline,1);
    }
    @ParameterizedTest @ValueSource(strings={"BUY","SELL"}) void exactEvidenceIgnoresCurrentWallClock(String side) throws Exception {
        var item=fixture("VALID",side);assertThat(item.verdict()).as(item.checks().toString()).isEqualTo("MATCH");assertThat(item.limitations()).contains("RAW_COMPARISON_UNAVAILABLE_RPC_EVIDENCE_USED");
    }
    @ParameterizedTest @ValueSource(strings={"OWNER","FEE","CALLDATA","DESTINATION","NONCE","QUOTE_ID","MISSING_ORACLE","DUPLICATE_ORACLE","DUPLICATE_VAULT","EXECUTION_EXPIRED","SIGNER","FEE_HISTORY"})
    void observedContradictions(String defect) throws Exception {assertThat(fixture(defect,"BUY").verdict()).isEqualTo("MISMATCH");}
    @Test void missingHistoricalSignerCannotBeGuessed() throws Exception {var item=fixture("HISTORY_MISSING","BUY");assertThat(item.verdict()).isEqualTo("INCONCLUSIVE");assertThat(item.reason()).isEqualTo("HISTORICAL_CONFIG_UNAVAILABLE");}
    @ParameterizedTest @ValueSource(strings={"TRADE_RECEIPT_MISSING","USER_RECEIPT_MISSING","FEE_HISTORY_MISSING"})
    void contradictionsSurviveUnavailableEvidence(String defect) throws Exception {
        var item=fixture(defect,"BUY");assertThat(item.verdict()).isEqualTo("MISMATCH");assertThat(item.checks()).anyMatch(c->!c.matches());assertThat(item.limitations()).contains("CHAIN_EVIDENCE_INCOMPLETE");
    }
    @Test void failedReceiptMatchesFailedRecordWithoutTrade() throws Exception {assertThat(fixture("FAILED","BUY").verdict()).isEqualTo("MATCH");}
    private String topicAddress(String address) {return "0x"+"0".repeat(24)+Numeric.cleanHexPrefix(address);}
    private String uints(BigInteger... numbers) {var s=new StringBuilder("0x");for(var n:numbers) s.append(String.format("%064x",n));return s.toString();}
}
