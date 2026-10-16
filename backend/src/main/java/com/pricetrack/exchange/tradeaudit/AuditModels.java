package com.pricetrack.exchange.tradeaudit;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.pricetrack.exchange.reserve.ReserveModels.Baseline;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

/** Immutable observations. Signatures and signed transactions never leave internal memory. */
public final class AuditModels {
    private AuditModels() {}
    public record OrderRow(long id,long userId,String symbol,String side,String mode,String status,
            BigInteger input,String hash,Instant updated) {}
    public record TradeRow(long id,long orderId,long userId,String symbol,String side,String mode,
            BigInteger base,BigInteger quote,BigInteger fee,BigInteger price,String hash,Instant created) {}
    public record TxRow(long id,Long orderId,String type,String status,String hash,String sender,Long nonce,Long block,
            String rawHash,@JsonIgnore String raw) {}
    public record QuoteRow(String id,Long orderId,long userId,String symbol,String side,String status,
            BigInteger price,BigInteger input,BigInteger minimum,String executor,Instant observed,Instant expires,
            String signatureHash,@JsonIgnore String signature) {}
    public record Cut(Instant at,String snapshot,Baseline baseline,List<OrderRow> orders,List<TradeRow> trades,
            List<TxRow> transactions,List<QuoteRow> quotes) {}
    public record Block(BigInteger number,String hash,BigInteger timestamp) {}
    public record Check(String code,String expected,String actual,boolean matches) {}
    public record Item(String key,Long orderId,String mode,String side,String kind,String verdict,String reason,
            String txHash,Long receiptBlock,String receiptHash,List<Check> checks,List<String> limitations) {}
    public record Coverage(boolean dbComplete,boolean chainComplete,boolean cutStable,boolean environmentVerified,
            int sourceOrders,int scannedEvents,int inspected,int rpcCalls) {}
    public record Run(String id,long actorId,String lifecycle,String verdict,String reason,Instant startedAt,
            Instant completedAt,Block block,String baselineId,String executionId,String manifestHash,
            Cut manifest,Coverage coverage,int match,int mismatch,int inconclusive) {}
    public record Page<T>(List<T> items,String nextBefore) {}
    public static final class Unavailable extends RuntimeException {
        public final String code;
        public Unavailable(String code) { super(code); this.code=code; }
    }
}
