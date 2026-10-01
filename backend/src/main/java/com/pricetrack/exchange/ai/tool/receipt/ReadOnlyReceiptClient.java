package com.pricetrack.exchange.ai.tool.receipt;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.ToolFailure;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade.ReceiptInput;
import com.pricetrack.exchange.blockchain.config.*;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

/** Separate bounded RPC transport. No send/sign/estimate/recovery operation exists here. */
public final class ReadOnlyReceiptClient implements ReceiptReader, AutoCloseable {
    private final BlockchainProperties blockchain;
    private final BlockchainReconciliationProperties reconciliation;
    private final ContractEventParser parser;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration budget;
    private final AtomicLong ids = new AtomicLong();

    public ReadOnlyReceiptClient(BlockchainProperties blockchain, BlockchainReconciliationProperties reconciliation,
                                 ContractEventParser parser, ObjectMapper json) {
        this(blockchain, reconciliation, parser, json, Duration.ofSeconds(3));
    }
    public ReadOnlyReceiptClient(BlockchainProperties blockchain, BlockchainReconciliationProperties reconciliation,
                                 ContractEventParser parser, ObjectMapper json, Duration budget) {
        this.blockchain = blockchain; this.reconciliation = reconciliation; this.parser = parser; this.json = json; this.budget = budget;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public ObjectNode read(ReceiptInput input) {
        ObjectNode data = json.createObjectNode().put("orderId", input.orderId()).put("databaseStatus", input.databaseStatus())
                .put("dbReadAt", input.dbReadAt().toString());
        if (input.type() == null) return data.put("linkStatus", "NOT_LINKED").put("receiptStatus", "UNKNOWN");
        if (!blockchain.enabled()) throw new ToolFailure("BLOCKCHAIN_DISABLED");
        if (input.txHash() == null) return data.put("linkStatus", "LINKED").put("receiptStatus", "TX_HASH_UNAVAILABLE");
        if (!input.txHash().matches("0x[0-9a-fA-F]{64}")) throw new ToolFailure("INCONSISTENT_LINK");
        long deadline = System.nanoTime() + budget.toNanos();
        JsonNode receiptJson = rpc("eth_getTransactionReceipt", List.of(input.txHash()), deadline);
        data.put("linkStatus", "LINKED").put("txHash", input.txHash()).put("rpcReadAt", Instant.now().toString());
        if (receiptJson.isNull()) return data.put("receiptStatus", "NOT_FOUND");
        try {
            if (!receiptJson.isObject() || !receiptJson.path("transactionHash").asText().equalsIgnoreCase(input.txHash())
                    || !receiptJson.path("blockNumber").asText().matches("0x[0-9a-fA-F]+")
                    || !receiptJson.path("blockHash").asText().matches("0x[0-9a-fA-F]{64}")) throw unavailable();
            TransactionReceipt receipt = json.treeToValue(receiptJson, TransactionReceipt.class);
            BigInteger block = receipt.getBlockNumber();
            JsonNode latestJson = rpc("eth_blockNumber", List.of(), deadline);
            if (!latestJson.isTextual() || !latestJson.asText().matches("0x[0-9a-fA-F]+")) throw unavailable();
            BigInteger latest = new BigInteger(latestJson.asText().substring(2), 16);
            BigInteger confirmations = latest.subtract(block).add(BigInteger.ONE).max(BigInteger.ZERO);
            String execution = switch (receiptJson.path("status").asText()) { case "0x1" -> "SUCCESS"; case "0x0" -> "FAILED"; default -> "UNKNOWN"; };
            data.put("receiptStatus", "FOUND").put("executionStatus", execution).put("blockNumber", block.toString())
                    .put("blockHash", receipt.getBlockHash()).put("confirmationObservedAt", Instant.now().toString())
                    .put("confirmations", confirmations.toString()).put("requiredConfirmations", reconciliation.requiredConfirmations());
            String match = "UNKNOWN";
            if (execution.equals("SUCCESS")) {
                try {
                    var event = parser.parse(receipt, input.type(), blockchain.exchangeVaultAddress(), input.senderAddress(), input.expectedInput());
                    match = "MATCH";
                    data.putObject("event").put("inputWei", event.inputAmount().toString()).put("outputWei", event.outputAmount().toString())
                            .put("feeWei", event.fee().toString()).put("priceE8", event.priceE8().toString());
                } catch (RuntimeException e) { match = "MISMATCH"; }
            }
            return data.put("eventValidation", match); // Never settles or modifies persisted state.
        } catch (ToolFailure e) { throw e; }
        catch (Exception e) { throw unavailable(); }
    }
    private JsonNode rpc(String method, List<String> params, long deadline) {
        long id = ids.incrementAndGet();
        CompletableFuture<HttpResponse<byte[]>> call = null;
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new ToolFailure("TOOL_TIMEOUT");
            ObjectNode request = json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            request.set("params", json.valueToTree(params));
            var uri = URI.create(blockchain.rpcUrl());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getUserInfo() != null) throw unavailable();
            var httpRequest = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(request))).build();
            call = http.sendAsync(httpRequest, response -> new BoundedBody());
            var response = call.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) throw unavailable();
            JsonNode root = json.readTree(response.body());
            if (root == null || !root.isObject() || !root.path("id").isIntegralNumber() || root.path("id").longValue() != id
                    || !root.path("jsonrpc").asText().equals("2.0") || root.has("error") || !root.has("result")) throw unavailable();
            return root.get("result");
        } catch (TimeoutException e) { throw new ToolFailure("TOOL_TIMEOUT"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ToolFailure("TOOL_TIMEOUT"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof HttpTimeoutException) throw new ToolFailure("TOOL_TIMEOUT");
            throw unavailable();
        } catch (ToolFailure e) { throw e; }
        catch (Exception e) { throw unavailable(); }
        finally { if (call != null && !call.isDone()) call.cancel(true); }
    }
    private static ToolFailure unavailable() { return new ToolFailure("TOOL_UNAVAILABLE"); }
    @Override public void close() { http.shutdownNow(); }

    /** Cancellation occurs during receipt of oversized/chunked bodies, not after buffering. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer b : buffers) {
                if (b.remaining() > 65_536 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(unavailable()); return;
                }
                byte[] part = new byte[b.remaining()]; b.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable t) { result.completeExceptionally(t); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
