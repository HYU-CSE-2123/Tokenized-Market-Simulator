package com.pricetrack.exchange.ai.tool.receipt;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade.ReceiptInput;
import com.pricetrack.exchange.blockchain.config.*;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;
import com.sun.net.httpserver.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.web3j.crypto.Hash;

class ReadOnlyReceiptClientTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    HttpServer server;
    ExecutorService executor;
    List<String> methods;
    ObjectNode receipt;
    volatile String mode;
    @BeforeEach void start() throws Exception {
        mode = "NORMAL"; methods = new CopyOnWriteArrayList<>(); receipt = successReceipt();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                var input = json.readTree(exchange.getRequestBody());
                methods.add(input.path("method").asText());
                ObjectNode out = json.createObjectNode().put("jsonrpc", "2.0"); out.set("id", input.get("id"));
                if (mode.equals("ERROR")) out.putObject("error").put("message", "SECRET_PROVIDER_ERROR");
                else if (input.path("method").asText().equals("eth_blockNumber")) out.put("result", "0xb");
                else out.set("result", receipt == null ? json.nullNode() : receipt);
                byte[] body = mode.equals("LARGE") ? new byte[70_000] : json.writeValueAsBytes(out);
                exchange.sendResponseHeaders(200, 0);
                if (mode.equals("SLOW")) {
                    exchange.getResponseBody().write(' '); exchange.getResponseBody().flush();
                    try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                exchange.getResponseBody().write(body);
            } catch (java.io.IOException ignored) { /* cancellation closes an in-flight body */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    ReadOnlyReceiptClient client(boolean enabled, Duration budget) {
        var p = new BlockchainProperties(enabled, "http://127.0.0.1:" + server.getAddress().getPort(), "", "", "", ToolFixtures.ADDRESS, "unused");
        return new ReadOnlyReceiptClient(p, new BlockchainReconciliationProperties(1000, 1000, 3), new ContractEventParser(), json, budget);
    }
    ReceiptInput input() { return new ReceiptInput(1, ToolFixtures.HASH, BlockchainTransactionType.BUY, ToolFixtures.ADDRESS,
            BigInteger.TEN, "SUBMITTED", Instant.now()); }
    ObjectNode successReceipt() {
        ObjectNode out = json.createObjectNode().put("transactionHash", ToolFixtures.HASH).put("blockHash", "0x" + "22".repeat(32))
                .put("blockNumber", "0xa").put("status", "0x1");
        ObjectNode log = out.putArray("logs").addObject().put("address", ToolFixtures.ADDRESS)
                .put("data", "0x" + uint(10) + uint(9) + uint(1) + uint(75000));
        log.putArray("topics").add(Hash.sha3String("Bought(address,uint256,uint256,uint256,uint256)"))
                .add("0x" + "0".repeat(24) + ToolFixtures.ADDRESS.substring(2));
        return out;
    }
    static String uint(long value) { return String.format("%064x", value); }
    @Test void successReturnsEventAndConfirmationsWithoutClaimingDatabaseSettlement() {
        try (var client = client(true, Duration.ofSeconds(3))) {
            var data = client.read(input());
            assertThat(data.path("executionStatus").asText()).isEqualTo("SUCCESS");
            assertThat(data.path("databaseStatus").asText()).isEqualTo("SUBMITTED");
            assertThat(data.path("eventValidation").asText()).isEqualTo("MATCH");
            assertThat(data.path("confirmations").asText()).isEqualTo("2");
            assertThat(data.path("requiredConfirmations").asInt()).isEqualTo(3);
            assertThat(data.toString()).doesNotContain("senderAddress", "rawTransaction", "logs", "FILLED");
            assertThat(methods).containsExactly("eth_getTransactionReceipt", "eth_blockNumber");
        }
    }
    @Test void absentReceiptIsNotAnExecutionFailure() {
        receipt = null;
        try (var client = client(true, Duration.ofSeconds(3))) {
            var data = client.read(input()); assertThat(data.path("receiptStatus").asText()).isEqualTo("NOT_FOUND");
            assertThat(data.has("executionStatus")).isFalse(); assertThat(methods).containsExactly("eth_getTransactionReceipt");
        }
    }
    @Test void soldEventPreservesWeiFieldsAndRejectsWrongDirectionOrInput() {
        var log = (ObjectNode) receipt.path("logs").get(0);
        log.put("data", "0x" + uint(2) + uint(149850) + uint(150) + uint(7500000000000L));
        log.putArray("topics").add(Hash.sha3String("Sold(address,uint256,uint256,uint256,uint256)"))
                .add("0x" + "0".repeat(24) + ToolFixtures.ADDRESS.substring(2));
        var sell = new ReceiptInput(1, ToolFixtures.HASH, BlockchainTransactionType.SELL,
                ToolFixtures.ADDRESS, BigInteger.TWO, "SUBMITTED", Instant.now());
        try (var client = client(true, Duration.ofSeconds(3))) {
            var data = client.read(sell);
            assertThat(data.path("eventValidation").asText()).isEqualTo("MATCH");
            assertThat(data.path("executionStatus").asText()).isEqualTo("SUCCESS");
            assertThat(data.path("databaseStatus").asText()).isEqualTo("SUBMITTED");
            assertThat(data.path("event").path("inputWei").asText()).isEqualTo("2");
            assertThat(data.path("event").path("outputWei").asText()).isEqualTo("149850");
            assertThat(data.path("event").path("feeWei").asText()).isEqualTo("150");
            assertThat(data.path("event").path("priceE8").asText()).isEqualTo("7500000000000");
            assertThat(client.read(input()).path("eventValidation").asText()).isEqualTo("MISMATCH");
            assertThat(client.read(new ReceiptInput(1, ToolFixtures.HASH, BlockchainTransactionType.SELL,
                    ToolFixtures.ADDRESS, BigInteger.TEN, "SUBMITTED", Instant.now()))
                    .path("eventValidation").asText()).isEqualTo("MISMATCH");
        }
    }
    @Test void failedExecutionAndMismatchedEventsRemainSeparateFacts() {
        try (var client = client(true, Duration.ofSeconds(3))) {
            receipt.put("status", "0x0");
            assertThat(client.read(input()).path("executionStatus").asText()).isEqualTo("FAILED");
            receipt.put("status", "0x1").putArray("logs");
            assertThat(client.read(input()).path("eventValidation").asText()).isEqualTo("MISMATCH");
        }
    }
    @Test void wrongHashAndRpcErrorsNeverBecomeReceiptFacts() {
        try (var client = client(true, Duration.ofSeconds(3))) {
            receipt.put("transactionHash", "0x" + "ff".repeat(32));
            assertThatThrownBy(() -> client.read(input())).isInstanceOf(ToolFailure.class).hasMessage("TOOL_UNAVAILABLE");
            mode = "ERROR";
            assertThatThrownBy(() -> client.read(input())).isInstanceOf(ToolFailure.class).hasMessage("TOOL_UNAVAILABLE");
        }
    }
    @Test void disabledChainAndAbsentLinkDoNotCallRpc() {
        try (var client = client(false, Duration.ofSeconds(3))) {
            assertThatThrownBy(() -> client.read(input())).hasMessage("BLOCKCHAIN_DISABLED");
            assertThat(client.read(new ReceiptInput(1, null, null, null, null, null, Instant.now())).path("linkStatus").asText()).isEqualTo("NOT_LINKED");
            assertThat(methods).isEmpty();
        }
    }
    @Test void slowChunkedBodyTimesOutAndClientRecovers() {
        mode = "SLOW";
        try (var client = client(true, Duration.ofMillis(300))) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.read(input())).hasMessage("TOOL_TIMEOUT");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
            mode = "NORMAL";
            assertThat(client.read(input()).path("receiptStatus").asText()).isEqualTo("FOUND");
        }
    }
    @Test void oversizedChunkedResponseIsRejectedDuringReceive() {
        mode = "LARGE";
        try (var client = client(true, Duration.ofSeconds(3))) {
            assertThatThrownBy(() -> client.read(input())).hasMessage("TOOL_UNAVAILABLE");
        }
        var subscriber = new ReadOnlyReceiptClient.BoundedBody();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(65_537)));
        verify(subscription).cancel();
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }
}
