package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import static com.pricetrack.exchange.tradeaudit.RpcAuditChain.same;
import com.pricetrack.exchange.blockchain.contract.*;
import com.pricetrack.exchange.blockchain.oracle.*;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;
import com.pricetrack.exchange.reserve.ReserveModels.Baseline;
import java.math.BigInteger;
import java.util.*;
import org.springframework.stereotype.Component;
import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Pure comparison. Neither settles nor issues/consumes a quote. */
@Component
public class AuditValidator {
    private final ContractEventParser parser;
    public AuditValidator(ContractEventParser parser) { this.parser=parser; }
    public Item inspect(OrderRow o,List<TradeRow> trades,List<TxRow> txs,List<QuoteRow> quotes,
            AuditChain.Session chain,Block cut,Baseline baseline,int confirmations) {
        String key="order:"+o.id();
        if(o.mode().equals("UNKNOWN")) return simple(key,o,"INCONCLUSIVE","LEGACY_EXECUTION_UNKNOWN");
        if(Set.of("REQUESTED","PENDING_ONCHAIN").contains(o.status()) || txs.stream().anyMatch(t->Set.of("CREATED","SIGNED","SUBMITTED","REVIEW_REQUIRED").contains(t.status())))
            return simple(key,o,"INCONCLUSIVE","UNSETTLED_OR_REVIEW");
        var checks=new ArrayList<Check>();
        if(o.mode().equals("DB_ONLY")) {
            check(checks,"DB_ONLY_NO_CHAIN_LINK",true,txs.isEmpty() && o.hash()==null && trades.stream().allMatch(t->t.hash()==null));
            if(o.status().equals("FILLED")) db(o,trades,checks);
            else check(checks,"NONFILLED_NO_TRADE",0,trades.size());
            return item(o,"DB_ONLY",checks,List.of("CHAIN_NOT_APPLICABLE"),null);
        }
        if(!o.mode().equals("ONCHAIN")) return simple(key,o,"INCONCLUSIVE","EXECUTION_UNKNOWN");
        if(o.status().equals("FAILED") && txs.isEmpty() && trades.isEmpty()) return simple(key,o,"INCONCLUSIVE","NOT_EXECUTED_OR_UNPROVEN");
        boolean filled=o.status().equals("FILLED");
        if(!filled && !o.status().equals("FAILED")) return simple(key,o,"INCONCLUSIVE","STATUS_UNSUPPORTED");
        if(filled) db(o,trades,checks); else check(checks,"FAILED_NO_TRADE",0,trades.size());
        check(checks,"ONE_TRANSACTION",1,txs.size());
        if(txs.size()!=1) return item(o,"ONCHAIN",checks,List.of("CHAIN_NOT_INSPECTED"),null);
        TxRow tx=txs.getFirst();
        check(checks,"TRANSACTION_TYPE",o.side(),tx.type());
        check(checks,"TRANSACTION_STATUS",filled?"CONFIRMED":"FAILED",tx.status());
        check(checks,"ORDER_TX_HASH",lower(o.hash()),lower(tx.hash()));
        if(tx.hash()==null) return item(o,"ONCHAIN",checks,List.of("CHAIN_NOT_INSPECTED"),null);
        try {
        if(baseline==null) throw new Unavailable("ENVIRONMENT_NOT_VERIFIABLE");
        AuditChain.Evidence e=chain.evidence(tx.hash());
        var r=e.receipt(); var t=e.transaction();
        if(!same(r.getBlockHash(),e.block().hash()) || !chain.canonical(e.block())) throw new Unavailable("REORG");
        if(r.getBlockNumber().compareTo(cut.number())>0 || cut.number().subtract(r.getBlockNumber()).add(BigInteger.ONE).compareTo(BigInteger.valueOf(confirmations))<0)
            throw new Unavailable("CONFIRMATIONS_OR_CUT_PENDING");
        check(checks,"RPC_TX_HASH",lower(tx.hash()),lower(t.getHash()));
        check(checks,"RECEIPT_TX_HASH",lower(tx.hash()),lower(r.getTransactionHash()));
        check(checks,"RECEIPT_BLOCK",tx.block(),r.getBlockNumber().longValueExact());
        check(checks,"TX_BLOCK_HASH",lower(r.getBlockHash()),lower(t.getBlockHash()));
        check(checks,"TX_BLOCK_NUMBER",r.getBlockNumber(),t.getBlockNumber());
        check(checks,"TX_POSITION",r.getTransactionIndex(),t.getTransactionIndex());
        check(checks,"RPC_NONCE",tx.nonce()==null?null:BigInteger.valueOf(tx.nonce()),t.getNonce());
        check(checks,"DB_SENDER",lower(baseline.chain().identity().operator()),lower(tx.sender()));
        check(checks,"RPC_SENDER",lower(tx.sender()),lower(t.getFrom()));
        check(checks,"RECEIPT_SENDER",lower(tx.sender()),lower(r.getFrom()));
        check(checks,"RPC_DESTINATION",lower(baseline.chain().identity().vault()),lower(t.getTo()));
        check(checks,"RECEIPT_DESTINATION",lower(t.getTo()),lower(r.getTo()));
        check(checks,"TX_VALUE",BigInteger.ZERO,t.getValue());
        check(checks,"RECEIPT_SUCCESS",filled,r.isStatusOK());
        if(!filled) return item(o,"FAILED_ONCHAIN",checks,List.of("FAILED_RECEIPT_NO_ASSET_CORRECTION"),e);
        if(!r.isStatusOK()) return item(o,"ONCHAIN",checks,List.of(),e);
        check(checks,"ONE_CONSUMED_QUOTE",1,quotes.size());
        if(quotes.size()!=1) return item(o,"ONCHAIN",checks,List.of("QUOTE_NOT_INSPECTED"),e);
        QuoteRow q=quotes.getFirst();
        check(checks,"QUOTE_USER",o.userId(),q.userId()); check(checks,"QUOTE_SYMBOL",o.symbol(),q.symbol());
        check(checks,"QUOTE_SIDE",o.side(),q.side()); check(checks,"QUOTE_STATUS","CONSUMED",q.status());
        check(checks,"QUOTE_INPUT",o.input(),q.input());
        check(checks,"QUOTE_EXECUTOR",lower(tx.sender()),lower(q.executor()));
        try {
            PriceReport report=new PriceReport(q.id(),PriceReportEip712.hashText(q.symbol()),q.price(),
                BigInteger.valueOf(q.observed().getEpochSecond()),BigInteger.valueOf(q.expires().getEpochSecond()),PriceReport.Side.valueOf(q.side()),q.input(),q.minimum(),q.executor());
            if(q.signature()==null) throw new Unavailable("SIGNATURE_EVIDENCE_MISSING");
            var signed=new SignedPriceReport(report,q.signature(),BigInteger.ZERO);
            var gateway=new ContractGateway(null);
            String calldata=o.side().equals("BUY")?gateway.encodeBuy(signed):gateway.encodeSell(signed);
            // Persist hashes, not the report signature or executable bytes.
            check(checks,"CALLDATA_REPORT_SIGNATURE",Hash.sha3(calldata),Hash.sha3(t.getInput()));
            var limits=new ArrayList<String>();
            if(tx.raw()!=null) {
                check(checks,"RAW_HASH",lower(tx.hash()),lower(Hash.sha3(tx.raw())));
                var decoded=TransactionDecoder.decode(tx.raw());
                check(checks,"RAW_NONCE",t.getNonce(),decoded.getNonce());
                check(checks,"RAW_DESTINATION",lower(t.getTo()),lower(decoded.getTo()));
                check(checks,"RAW_CALLDATA",Hash.sha3(t.getInput()),Hash.sha3(decoded.getData()));
                if(decoded instanceof SignedRawTransaction sr) {
                    check(checks,"RAW_CHAIN_ID",baseline.chain().identity().chainId(),BigInteger.valueOf(sr.getChainId()));
                    check(checks,"RAW_SENDER",lower(t.getFrom()),lower(sr.getFrom()));
                } else check(checks,"RAW_SIGNED",true,false);
            } else limits.add("RAW_COMPARISON_UNAVAILABLE_RPC_EVIDENCE_USED");
            var event=parser.parse(r,BlockchainTransactionType.valueOf(o.side()),baseline.chain().identity().vault(),baseline.chain().identity().operator(),o.input());
            if(trades.size()==1) {
                TradeRow trade=trades.getFirst();
                check(checks,"EVENT_OUTPUT",o.side().equals("BUY")?trade.base():trade.quote(),event.outputAmount());
                check(checks,"EVENT_FEE",trade.fee(),event.fee()); check(checks,"EVENT_PRICE",trade.price(),event.priceE8());
            }
            check(checks,"REPORT_PRICE",q.price(),event.priceE8());
            check(checks,"MINIMUM_OUTPUT",true,event.outputAmount().compareTo(q.minimum())>=0);
            check(checks,"REPORT_TTL",BigInteger.valueOf(30),report.validUntil().subtract(report.observedAt()));
            check(checks,"EXECUTION_VALIDITY",true,e.block().timestamp().compareTo(report.validUntil())<=0 && report.observedAt().compareTo(e.block().timestamp().add(BigInteger.TWO))<=0);
            String topic=Hash.sha3String("PriceReportConsumed(bytes32,uint256,uint256,uint256,address)");
            var consumed=r.getLogs().stream().filter(l->same(l.getAddress(),baseline.chain().identity().oracle()) && !l.getTopics().isEmpty() && same(l.getTopics().getFirst(),topic)).toList();
            check(checks,"ONE_ORACLE_EVENT",1,consumed.size());
            if(consumed.size()==1) {
                var log=consumed.getFirst();
                if(log.getTopics().size()!=3) throw new IllegalArgumentException();
                check(checks,"ORACLE_QUOTE_ID",lower(q.id()),lower(log.getTopics().get(1)));
                var values=FunctionReturnDecoder.decode(log.getData(),new Function("values",List.of(),List.of(new TypeReference<Uint256>() {},new TypeReference<Uint256>() {},new TypeReference<Uint256>() {})).getOutputParameters());
                if(values.size()!=3) throw new IllegalArgumentException();
                check(checks,"ORACLE_PRICE",q.price(),values.get(0).getValue());
                check(checks,"ORACLE_OBSERVATION",report.observedAt(),values.get(1).getValue());
                check(checks,"ORACLE_EXPIRY",report.validUntil(),values.get(2).getValue());
                if(e.historicalSigner()==null || e.historicalFee()==null) throw new Unavailable("HISTORICAL_CONFIG_UNAVAILABLE");
                String signer="0x"+log.getTopics().get(2).substring(26);
                check(checks,"HISTORICAL_SIGNER",lower(e.historicalSigner()),lower(signer));
                byte[] bytes=Numeric.hexStringToByteArray(q.signature());
                if(bytes.length!=65) throw new IllegalArgumentException();
                var sig=new Sign.SignatureData(bytes[64],Arrays.copyOfRange(bytes,0,32),Arrays.copyOfRange(bytes,32,64));
                String recovered="0x"+Keys.getAddress(Sign.signedMessageHashToKey(PriceReportEip712.digest(report,baseline.chain().identity().chainId(),baseline.chain().identity().oracle()),sig));
                check(checks,"REPORT_RECOVERED_SIGNER",lower(signer),lower(recovered));
                BigInteger gross=o.side().equals("BUY")?o.input():o.input().multiply(q.price()).divide(BigInteger.TEN.pow(8));
                BigInteger fee=gross.multiply(e.historicalFee()).divide(BigInteger.valueOf(10000));
                BigInteger output=o.side().equals("BUY")?gross.subtract(fee).multiply(BigInteger.TEN.pow(8)).divide(q.price()):gross.subtract(fee);
                check(checks,"HISTORICAL_FEE",fee,event.fee()); check(checks,"HISTORICAL_OUTPUT",output,event.outputAmount());
            }
            return item(o,"ONCHAIN",checks,limits,e);
        } catch(Unavailable ex) { throw ex; }
        catch(Exception ex) { check(checks,"REPORT_OR_EVENT_DECODE",true,false); return item(o,"ONCHAIN",checks,List.of(),e); }
        } catch(Unavailable ex) {
            // Missing chain evidence must not erase contradictions already observed in a stable DB cut.
            boolean contradiction=checks.stream().anyMatch(c->!c.matches());
            return new Item(key,o.id(),o.mode(),o.side(),"ONCHAIN",contradiction?"MISMATCH":"INCONCLUSIVE",
                ex.code,o.hash(),null,null,List.copyOf(checks),List.of("CHAIN_EVIDENCE_INCOMPLETE"));
        }
    }
    private void db(OrderRow o,List<TradeRow> ts,List<Check> cs) {
        check(cs,"ONE_TRADE",1,ts.size());
        if(ts.size()!=1) return;
        var t=ts.getFirst();
        check(cs,"TRADE_USER",o.userId(),t.userId()); check(cs,"TRADE_SYMBOL",o.symbol(),t.symbol());
        check(cs,"TRADE_SIDE",o.side(),t.side()); check(cs,"TRADE_MODE",o.mode(),t.mode());
        check(cs,"TRADE_INPUT",o.input(),o.side().equals("BUY")?t.quote():t.base());
        check(cs,"TRADE_HASH",lower(o.hash()),lower(t.hash()));
    }
    static String lower(String s) { return s==null?null:s.toLowerCase(Locale.ROOT); }
    static void check(List<Check> checks,String code,Object expected,Object actual) { checks.add(new Check(code,String.valueOf(expected),String.valueOf(actual),Objects.equals(expected,actual))); }
    private Item item(OrderRow o,String kind,List<Check> checks,List<String> limits,AuditChain.Evidence e) {
        boolean matches=checks.stream().allMatch(Check::matches);
        return new Item("order:"+o.id(),o.id(),o.mode(),o.side(),kind,matches?"MATCH":"MISMATCH",matches?"EXACT_RECORD_MATCH":"RECORD_CONTRADICTION",o.hash(),e==null?null:e.block().number().longValueExact(),e==null?null:e.block().hash(),List.copyOf(checks),limits);
    }
    public static Item simple(String key,OrderRow o,String verdict,String reason) { return new Item(key,o.id(),o.mode(),o.side(),"OBSERVATION",verdict,reason,o.hash(),null,null,List.of(),List.of()); }
}
