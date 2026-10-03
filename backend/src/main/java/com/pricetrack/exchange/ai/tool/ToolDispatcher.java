package com.pricetrack.exchange.ai.tool;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import java.time.Duration;
import java.util.concurrent.*;
import org.springframework.dao.QueryTimeoutException;

/** One bounded call, explicit auth context and four real workers. Timed-out work never frees capacity prematurely. */
public final class ToolDispatcher implements AutoCloseable {
    private final ToolProperties properties;
    private final ToolRegistry registry;
    private final ToolReadFacade reads;
    private final ReceiptReader receipts;
    private final ToolAudit audit;
    private final ObjectMapper json;
    private final ThreadPoolExecutor workers;
    private final Duration timeout;
    private com.pricetrack.exchange.ai.observability.AiObservability observation;
    public ToolDispatcher observe(com.pricetrack.exchange.ai.observability.AiObservability value){observation=value;return this;}

    public ToolDispatcher(ToolProperties properties, ToolRegistry registry, ToolReadFacade reads,
                          ReceiptReader receipts, ToolAudit audit, ObjectMapper json) {
        this(properties, registry, reads, receipts, audit, json, Duration.ofSeconds(5));
    }
    public ToolDispatcher(ToolProperties properties, ToolRegistry registry, ToolReadFacade reads,
                          ReceiptReader receipts, ToolAudit audit, ObjectMapper json, Duration timeout) {
        this.properties = properties; this.registry = registry; this.reads = reads; this.receipts = receipts;
        this.audit = audit; this.json = json.copy().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.timeout = timeout;
        workers = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "read-tool"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public ToolResult invoke(AuthenticatedUser principal, String name, byte[] input) {
        return invoke(principal,name,input,null,timeout);
    }
    /** Server-only run metadata and remaining budget; never read from HTTP arguments. */
    public ToolResult invokeForAgent(AuthenticatedUser principal, String name, byte[] input, String runId, Duration remaining) {
        return invoke(principal,name,input,runId,remaining.compareTo(timeout)<0 ? remaining : timeout);
    }
    private ToolResult invoke(AuthenticatedUser principal, String name, byte[] input, String runId, Duration budget) {
        long start = System.nanoTime();
        ToolContext context = null;
        ToolResult result;
        Future<JsonNode> work = null;
        try {
            context = runId == null ? ToolContext.from(principal) : ToolContext.forAgent(principal,runId);
            if (budget.isZero() || budget.isNegative()) throw new ToolFailure("TOOL_TIMEOUT");
            if (!properties.enabled()) throw new ToolFailure("TOOL_DISABLED");
            if (input == null || input.length > 2048) throw new ToolFailure("TOOL_INPUT_LIMIT");
            if ("listAbnormalOrders".equals(name)) context.requireAdmin();
            if (name == null) throw new ToolFailure("TOOL_NOT_ALLOWED");
            JsonNode root = json.readTree(input);
            if (root == null || !root.isObject() || root.size() != 1 || !root.has("arguments")) throw new ToolFailure("INVALID_TOOL_ARGUMENTS");
            var args = registry.validate(name, root.get("arguments"));
            final ToolContext ctx = context;
            work = workers.submit(() -> execute(ctx, name, args));
            JsonNode data = work.get(budget.toNanos(), TimeUnit.NANOSECONDS);
            result = ToolResult.success(name, source(name), data);
            if (json.writeValueAsBytes(result).length > 32_768) throw new ToolFailure("TOOL_OUTPUT_LIMIT");
        } catch (RejectedExecutionException e) { result = ToolResult.failure(name, "TOOL_BUSY"); }
        catch (TimeoutException e) { result = ToolResult.failure(name, "TOOL_TIMEOUT"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); result = ToolResult.failure(name, "TOOL_TIMEOUT"); }
        catch (ExecutionException e) { result = ToolResult.failure(name, safeError(e.getCause())); }
        catch (ToolFailure e) { result = ToolResult.failure(safeName(name), e.code()); }
        catch (java.io.IOException e) { result = ToolResult.failure(safeName(name), "INVALID_TOOL_ARGUMENTS"); }
        catch (RuntimeException e) { result = ToolResult.failure(safeName(name), "TOOL_UNAVAILABLE"); }
        finally { if (work != null && !work.isDone()) work.cancel(true); }
        audit.record(context, name, result, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        if(observation!=null)observation.record("TOOL",safeName(name),runId,start,"SUCCESS".equals(result.status()),result.error()==null?"OK":result.error(),0,null,null);
        return result;
    }
    private JsonNode execute(ToolContext context, String name, ToolRegistry.Arguments args) {
        return switch (name) {
            case "getOrder" -> reads.order(context, args.orderId());
            case "getQuote" -> reads.quote(context, args.quoteId());
            case "getBlockchainTransaction" -> reads.transaction(context, args.orderId());
            case "getReceiptSummary" -> receipts.read(reads.receiptInput(context, args.orderId()));
            case "getMarketStatus" -> reads.market(context, false);
            case "getCurrentReferencePrice" -> reads.market(context, true);
            case "getPortfolio" -> reads.portfolio(context);
            case "listAbnormalOrders" -> reads.abnormal(context, args);
            default -> throw new ToolFailure("TOOL_NOT_ALLOWED");
        };
    }
    private static String safeError(Throwable e) {
        if (e instanceof ToolFailure f) return f.code();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof QueryTimeoutException || cause instanceof jakarta.persistence.QueryTimeoutException
                    || cause instanceof org.springframework.transaction.TransactionTimedOutException
                    || cause instanceof java.sql.SQLTimeoutException
                    || cause instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState())) return "TOOL_TIMEOUT";
        }
        return "TOOL_UNAVAILABLE";
    }
    private static String source(String name) {
        return switch (name) {
            case "getReceiptSummary" -> "TRADING_DB+RPC";
            case "getMarketStatus", "getCurrentReferencePrice" -> "MARKET_SNAPSHOT";
            case "getPortfolio" -> "TRADING_DB+MARKET_SNAPSHOT";
            default -> "TRADING_DB";
        };
    }
    private static String safeName(String name) { return name != null && ToolRegistry.NAMES.contains(name) ? name : "UNKNOWN"; }
    @Override public void close() { workers.shutdownNow(); }
}
