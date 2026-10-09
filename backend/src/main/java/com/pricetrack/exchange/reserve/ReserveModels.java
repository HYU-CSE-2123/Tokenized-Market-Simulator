package com.pricetrack.exchange.reserve;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public diagnostics never contain signed reports, raw transactions or private configuration. */
public final class ReserveModels {
    private ReserveModels() {}
    public record Block(BigInteger number, String hash) {}
    public record Identity(BigInteger chainId, String operator, String krw, String token,
            String vault, String oracle, String priceSigner, Map<String,String> codeHashes) {}
    public record Chain(Identity identity, Block block, BigInteger operatorKrw, BigInteger operatorSec,
            BigInteger vaultKrw, BigInteger vaultSec, BigInteger krwSupply, BigInteger secSupply,
            BigInteger allowance, BigInteger feeBps, boolean rolesValid, String operatorEthWei) {}
    public record Balance(long userId,String symbol,BigInteger amount,BigInteger locked) {}
    public record OrderRow(long id,long userId,String side,String mode,String status,
            BigInteger input,String hash,Instant updatedAt) {}
    public record TradeRow(long id,long orderId,long userId,String symbol,String mode,String side,BigInteger base,
            BigInteger quote,BigInteger fee,BigInteger priceE8,String hash,Instant createdAt) {}
    public record TxRow(long id,Long orderId,String type,String status,String hash,String sender,
            Long nonce,Long block) {}
    public record Grant(String id,long userId,BigInteger amount,Instant createdAt) {}
    public record DbCut(Instant at,List<Balance> balances,List<OrderRow> orders,
            List<TradeRow> trades,List<TxRow> transactions,List<Grant> grants) {}
    public record Baseline(String id,String executionId,Instant dbAt,long actorId,Chain chain) {}
    public record Check(String name,String expected,String actual,String difference,boolean matches) {}
    public record Liquidity(String operatorBuyFunds,String buyAllowance,String operatorSellTokens,
            String vaultSellFunds,String userKrw,String userSec,String operatorAllocationReference,
            String explanation,String operatorEthWei,String gasAssessment) {}
    public record Result(String id,String status,String reason,Instant createdAt,long actorId,
            Baseline baseline,Instant dbAt,Chain chain,List<Check> checks,Liquidity liquidity) {}
}
