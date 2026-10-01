package com.pricetrack.exchange.ai.tool.read;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.blockchain.support.PriceUnits;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.market.model.MarketPriceSnapshot;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.quote.PriceQuote;
import com.pricetrack.exchange.trade.Trade;
import com.pricetrack.exchange.wallet.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/** Authorized, non-locking JPA reads only. Entities and recovery/signing material never leave this facade. */
@Transactional(readOnly = true, timeout = 2)
public class ToolReadFacade {
    private final EntityManager em;
    private final MarketPriceService market;
    private final ObjectMapper json;
    private final ToolProperties properties;
    private final Clock clock;

    public ToolReadFacade(EntityManager em, MarketPriceService market, ObjectMapper json,
                          ToolProperties properties, Clock clock) {
        this.em = em; this.market = market; this.json = json; this.properties = properties; this.clock = clock;
    }
    private void authorize(ToolContext ctx) {
        if (ctx == null) throw new ToolFailure("AUTHENTICATION_REQUIRED");
        ctx.requireAuthenticated();
        if (!properties.enabled()) throw new ToolFailure("TOOL_DISABLED");
    }
    private <T> TypedQuery<T> query(String hql, Class<T> type) {
        return em.createQuery(hql, type).setHint("jakarta.persistence.query.timeout", 2000);
    }
    private Order ownedOrder(ToolContext ctx, long id) {
        authorize(ctx);
        var q = query("select o from Order o where o.id=:id" + (ctx.admin() ? "" : " and o.userId=:uid"), Order.class)
                .setParameter("id", id);
        if (!ctx.admin()) q.setParameter("uid", ctx.userId());
        return q.setMaxResults(1).getResultList().stream().findFirst().orElseThrow(() -> new ToolFailure("RESOURCE_NOT_FOUND"));
    }
    public ObjectNode order(ToolContext ctx, long id) {
        Order order = ownedOrder(ctx, id);
        ObjectNode data = orderSummary(order);
        var quotes = query("select q from PriceQuote q where q.orderId=:id and q.userId=:uid", PriceQuote.class)
                .setParameter("id", id).setParameter("uid", order.getUserId()).setMaxResults(1).getResultList();
        data.put("quoteLink", quotes.isEmpty() ? "NOT_LINKED" : "LINKED");
        if (!quotes.isEmpty()) data.put("quoteId", quotes.getFirst().getQuoteId());
        var trades = query("select t from Trade t where t.orderId=:id and t.userId=:uid", Trade.class)
                .setParameter("id", id).setParameter("uid", order.getUserId()).setMaxResults(1).getResultList();
        if (trades.isEmpty()) data.putNull("trade");
        else {
            Trade t = trades.getFirst();
            ObjectNode actual = data.putObject("trade").put("price", decimal(t.getPrice()))
                    .put("baseAmount", decimal(t.getBaseAmount())).put("baseSymbol", WalletService.TOKEN_SYMBOL)
                    .put("quoteAmount", decimal(t.getQuoteAmount())).put("quoteSymbol", WalletService.KRW_SYMBOL)
                    .put("fee", decimal(t.getFee())).put("feeSymbol", WalletService.KRW_SYMBOL);
            time(actual, "createdAt", t.getCreatedAt());
        }
        return data;
    }
    public ObjectNode quote(ToolContext ctx, String id) {
        authorize(ctx);
        var q = query("select q from PriceQuote q where q.quoteId=:id" + (ctx.admin() ? "" : " and q.userId=:uid"), PriceQuote.class)
                .setParameter("id", id);
        if (!ctx.admin()) q.setParameter("uid", ctx.userId());
        PriceQuote quote = q.setMaxResults(1).getResultList().stream().findFirst().orElseThrow(() -> new ToolFailure("RESOURCE_NOT_FOUND"));
        ObjectNode data = fresh().put("quoteId", quote.getQuoteId()).put("symbol", quote.getSymbol())
                .put("side", quote.getSide().name()).put("storedStatus", quote.getStatus().name())
                .put("price", decimal(PriceUnits.fromPriceE8(quote.getPriceE8()))).put("priceSymbol", WalletService.KRW_SYMBOL)
                .put("inputAmount", decimal(TokenUnits.fromWei(quote.getInputAmount())))
                .put("minimumOutputAmount", decimal(TokenUnits.fromWei(quote.getMinimumOutput())))
                .put("fee", decimal(TokenUnits.fromWei(quote.getFee()))).put("feeSymbol", WalletService.KRW_SYMBOL)
                .put("expiredByTime", clock.instant().isAfter(quote.getValidUntil()));
        boolean buy = quote.getSide().name().equals("BUY");
        units(data, buy);
        time(data, "observedAt", quote.getObservedAt()); time(data, "validUntil", quote.getValidUntil());
        time(data, "createdAt", quote.getCreatedAt()); time(data, "consumedAt", quote.getConsumedAt());
        if (quote.getOrderId() == null) data.putNull("orderId");
        else {
            // A damaged link must not reveal another user's order identifier even to a quote's owner.
            var linked = query("select o from Order o where o.id=:id and o.userId=:uid", Order.class)
                    .setParameter("id", quote.getOrderId()).setParameter("uid", quote.getUserId()).getResultList();
            if (linked.isEmpty()) { data.putNull("orderId"); data.put("orderLink", "INCONSISTENT_LINK"); }
            else { data.put("orderId", quote.getOrderId()); data.put("orderLink", "LINKED"); }
        }
        return data;
    }
    public ObjectNode transaction(ToolContext ctx, long orderId) {
        ownedOrder(ctx, orderId);
        var rows = transactionRows(orderId);
        return rows.isEmpty() ? fresh().put("orderId", orderId).put("linkStatus", "NOT_LINKED") : transactionSummary(rows.getFirst());
    }
    /** Internal immutable input, copied while the DB transaction is open; NOT a serializable Tool result. */
    public record ReceiptInput(long orderId, String txHash, BlockchainTransactionType type, String senderAddress,
                               BigInteger expectedInput, String databaseStatus, Instant dbReadAt) {}
    public ReceiptInput receiptInput(ToolContext ctx, long orderId) {
        Order order = ownedOrder(ctx, orderId);
        var rows = transactionRows(orderId);
        if (rows.isEmpty()) return new ReceiptInput(orderId, null, null, null, null, null, clock.instant());
        var tx = rows.getFirst();
        if ((tx.getType() != BlockchainTransactionType.BUY && tx.getType() != BlockchainTransactionType.SELL)
                || !tx.getType().name().equals(order.getSide().name()))
            throw new ToolFailure("INCONSISTENT_LINK");
        return new ReceiptInput(orderId, tx.getTxHash(), tx.getType(), tx.getSenderAddress(),
                TokenUnits.toWei(order.getInputAmount()), tx.getStatus().name(), clock.instant());
    }
    private List<BlockchainTransaction> transactionRows(long orderId) {
        return query("select t from BlockchainTransaction t where t.orderId=:id", BlockchainTransaction.class)
                .setParameter("id", orderId).setMaxResults(1).getResultList();
    }
    public ObjectNode market(ToolContext ctx, boolean includePrice) {
        authorize(ctx);
        MarketPriceSnapshot snapshot = market.current();
        ObjectNode data = snapshot(snapshot);
        if (includePrice) data.put("price", decimal(snapshot.price())).put("priceSymbol", WalletService.KRW_SYMBOL)
                .put("priceKind", "MARKET_REFERENCE_NOT_EXECUTION_PRICE");
        return data;
    }
    public ObjectNode portfolio(ToolContext ctx) {
        authorize(ctx);
        var rows = query("select b from UserBalance b where b.userId=:uid order by b.symbol", UserBalance.class)
                .setParameter("uid", ctx.userId()).getResultList();
        UserBalance krw = balance(rows, ctx.userId(), WalletService.KRW_SYMBOL);
        UserBalance token = balance(rows, ctx.userId(), WalletService.TOKEN_SYMBOL);
        MarketPriceSnapshot snapshot = market.current(); // one observation for price AND valuation metadata
        BigDecimal tokenValue = token.getAmount().multiply(snapshot.price()).setScale(18, RoundingMode.HALF_UP);
        BigDecimal cost = token.getAmount().multiply(token.getAverageBuyPrice()).setScale(18, RoundingMode.HALF_UP);
        ObjectNode data = fresh().put("currentPrice", decimal(snapshot.price()))
                .put("averageBuyPrice", decimal(token.getAverageBuyPrice())).put("tokenValue", decimal(tokenValue))
                .put("unrealizedProfit", decimal(tokenValue.subtract(cost)))
                .put("totalValue", decimal(krw.getAmount().add(tokenValue))).put("valuationSymbol", WalletService.KRW_SYMBOL);
        data.set("reference", snapshot(snapshot));
        var balances = data.putArray("balances");
        for (UserBalance b : List.of(krw, token)) balances.addObject().put("symbol", b.getSymbol())
                .put("total", decimal(b.getAmount())).put("locked", decimal(b.getLockedAmount()))
                .put("available", decimal(b.getAvailableAmount()));
        return data;
    }
    private UserBalance balance(List<UserBalance> rows, long uid, String symbol) {
        return rows.stream().filter(b -> b.getSymbol().equals(symbol)).findFirst().orElseGet(() -> new UserBalance(uid, symbol));
    }
    public ObjectNode abnormal(ToolContext ctx, ToolRegistry.Arguments args) {
        authorize(ctx); ctx.requireAdmin();
        boolean waiting = args.filter().equals("WAITING_LONG");
        String predicate = waiting ? "t.status in :statuses and coalesce(t.submittedAt,t.createdAt)<=:cutoff" : "t.status=:status";
        String cursorClause = args.cursor() == null ? "" : " and (t.createdAt>:after or (t.createdAt=:after and t.id>:afterId))";
        var q = query("select t from BlockchainTransaction t, Order o where t.orderId=o.id and (" + predicate + ")"
                + cursorClause + " order by t.createdAt,t.id", BlockchainTransaction.class);
        if (waiting) q.setParameter("statuses", List.of(BlockchainTransactionStatus.SIGNED, BlockchainTransactionStatus.SUBMITTED))
                .setParameter("cutoff", clock.instant().minusSeconds(args.waitingSeconds()));
        else q.setParameter("status", BlockchainTransactionStatus.REVIEW_REQUIRED);
        if (args.cursor() != null) q.setParameter("after", args.cursor().createdAt()).setParameter("afterId", args.cursor().id());
        var rows = q.setMaxResults(args.limit() + 1).getResultList();
        ObjectNode data = fresh().put("filter", args.filter());
        var items = data.putArray("items");
        for (var tx : rows.stream().limit(args.limit()).toList()) {
            ObjectNode item = transactionSummary(tx);
            item.remove("txHash"); // list is discovery, not a transaction detail payload
            item.put("attentionReason", args.filter());
            item.set("order", orderSummary(ownedOrder(ctx, tx.getOrderId())));
            items.add(item);
        }
        if (rows.size() > args.limit()) {
            var last = rows.get(args.limit() - 1);
            data.put("nextCursor", new ToolRegistry.Cursor(last.getCreatedAt(), last.getId()).encode());
        } else data.putNull("nextCursor");
        return data;
    }
    private ObjectNode orderSummary(Order o) {
        ObjectNode data = fresh().put("orderId", o.getId()).put("symbol", o.getSymbol()).put("side", o.getSide().name())
                .put("status", o.getStatus().name()).put("inputAmount", decimal(o.getInputAmount()))
                .put("expectedOutputAmount", decimal(o.getExpectedOutputAmount()));
        units(data, o.getSide() == OrderSide.BUY);
        time(data, "createdAt", o.getCreatedAt()); time(data, "updatedAt", o.getUpdatedAt());
        return data;
    }
    private ObjectNode transactionSummary(BlockchainTransaction t) {
        ObjectNode data = fresh().put("orderId", t.getOrderId()).put("linkStatus", "LINKED")
                .put("type", t.getType().name()).put("databaseStatus", t.getStatus().name()).put("txHash", t.getTxHash())
                .put("hasRecordedError", t.getErrorMessage() != null && !t.getErrorMessage().isBlank())
                .put("errorCategory", t.getErrorMessage() == null || t.getErrorMessage().isBlank() ? "NONE" : "UNCLASSIFIED");
        if (t.getBlockNumber() == null) data.putNull("blockNumber"); else data.put("blockNumber", t.getBlockNumber());
        time(data, "createdAt", t.getCreatedAt()); time(data, "submittedAt", t.getSubmittedAt()); time(data, "confirmedAt", t.getConfirmedAt());
        return data;
    }
    private ObjectNode snapshot(MarketPriceSnapshot s) {
        ObjectNode data = json.createObjectNode().put("symbol", s.symbol()).put("provider", s.provider())
                .put("marketStatus", s.marketStatus().name()).put("priceStatus", s.priceStatus().name());
        time(data, "observedAt", s.observedAt()); return data;
    }
    private ObjectNode fresh() { return json.createObjectNode().put("dbReadAt", clock.instant().toString()); }
    private static void units(ObjectNode data, boolean buy) {
        data.put("inputSymbol", buy ? WalletService.KRW_SYMBOL : WalletService.TOKEN_SYMBOL)
                .put("outputSymbol", buy ? WalletService.TOKEN_SYMBOL : WalletService.KRW_SYMBOL);
    }
    private static String decimal(BigDecimal value) { return value == null ? null : value.toPlainString(); }
    private static void time(ObjectNode data, String field, Instant value) { data.put(field, value == null ? null : value.toString()); }
}
