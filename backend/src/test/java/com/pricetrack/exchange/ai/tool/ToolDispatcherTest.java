package com.pricetrack.exchange.ai.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReceiptReader;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.user.UserRole;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ToolDispatcherTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final ToolReadFacade reads = mock(ToolReadFacade.class);
    final ReceiptReader receipts = mock(ReceiptReader.class);
    final AuthenticatedUser user = new AuthenticatedUser(7L, "PRIVATE_LOGIN", UserRole.USER);
    ToolDispatcher dispatcher(boolean enabled, Duration timeout) {
        return new ToolDispatcher(new ToolProperties(enabled), new ToolRegistry(json), reads, receipts, new ToolAudit(), json, timeout);
    }
    byte[] args(String text) { return ("{\"arguments\":" + text + "}").getBytes(StandardCharsets.UTF_8); }

    @Test void disabledAndAnonymousCallsCannotTouchReadDependencies() {
        try (var tools = dispatcher(false, Duration.ofSeconds(1))) {
            assertThat(tools.invoke(user, "getOrder", args("{\"orderId\":1}")).error()).isEqualTo("TOOL_DISABLED");
            assertThat(tools.invoke(null, "getOrder", args("{}")).error()).isEqualTo("AUTHENTICATION_REQUIRED");
            verifyNoInteractions(reads, receipts);
        }
    }
    @Test void uncooperativeTimedOutWorkersCannotCreateAnUnboundedBacklog() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(4);
        when(reads.market(any(), eq(false))).thenAnswer(call -> {
            try { while (release.getCount() > 0) { try { release.await(); } catch (InterruptedException ignored) { } } }
            finally { finished.countDown(); }
            return json.createObjectNode();
        });
        try (var tools = dispatcher(true, Duration.ofMillis(80))) {
            for (int i = 0; i < 4; i++) assertThat(tools.invoke(user, "getMarketStatus", args("{}")).error()).isEqualTo("TOOL_TIMEOUT");
            assertThat(tools.invoke(user, "getMarketStatus", args("{}")).error()).isEqualTo("TOOL_BUSY");
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(30);
            assertThat(tools.invoke(user, "getMarketStatus", args("{}")).status()).isEqualTo("SUCCESS");
        } finally { release.countDown(); }
    }
    @Test void timeoutInterruptsWorkAndReleasesCapacityAfterWorkerExits() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        when(reads.market(any(), eq(false))).thenAnswer(call -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { interrupted.countDown(); throw e; }
            return json.createObjectNode();
        });
        try (var tools = dispatcher(true, Duration.ofMillis(100))) {
            assertThat(tools.invoke(user, "getMarketStatus", args("{}")).error()).isEqualTo("TOOL_TIMEOUT");
            assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
            when(reads.market(any(), eq(false))).thenReturn(json.createObjectNode());
            assertThat(tools.invoke(user, "getMarketStatus", args("{}")).status()).isEqualTo("SUCCESS");
        }
    }
    @Test void oversizedOutputAndInternalExceptionsDoNotBecomeEvidence() {
        try (var tools = dispatcher(true, Duration.ofSeconds(1))) {
            when(reads.market(any(), eq(false))).thenReturn(json.createObjectNode().put("oversized", "x".repeat(32768)));
            var result = tools.invoke(user, "getMarketStatus", args("{}"));
            assertThat(result.error()).isEqualTo("TOOL_OUTPUT_LIMIT"); assertThat(result.data()).isNull();
            when(reads.market(any(), eq(false))).thenThrow(new IllegalStateException("SECRET_DATABASE_URL"));
            result = tools.invoke(user, "getMarketStatus", args("{}"));
            assertThat(result.error()).isEqualTo("TOOL_UNAVAILABLE"); assertThat(result.toString()).doesNotContain("SECRET_DATABASE_URL");
        }
    }
    @Test void schemaAndValidationCoverAllEightAllowlistedFunctions() {
        var registry = new ToolRegistry(json);
        assertThat(ToolRegistry.NAMES).hasSize(8);
        for (String name : ToolRegistry.NAMES) {
            JsonNode schema = registry.inputSchema(name);
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            var invalid = json.createObjectNode().put("userId", 2);
            assertThatThrownBy(() -> registry.validate(name, invalid)).isInstanceOf(ToolFailure.class);
        }
        assertThat(registry.inputSchema("getOrder").path("required").get(0).asText()).isEqualTo("orderId");
        var cursor = new ToolRegistry.Cursor(Instant.parse("2026-09-29T00:00:00.123456Z"), 9);
        assertThat(ToolRegistry.Cursor.decode(cursor.encode())).isEqualTo(cursor);
        assertThatThrownBy(() -> ToolRegistry.Cursor.decode("bad")).isInstanceOf(ToolFailure.class);
    }
    @Test void auditContainsServerContextButNoRequestPayloadOrSensitiveMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(ToolAudit.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try (var tools = dispatcher(true, Duration.ofSeconds(1))) {
            tools.invoke(user, "SECRET_TOOL\nINJECTION", args("{\"secret\":\"SECRET_ARGUMENT\"}"));
            tools.invoke(user, "listAbnormalOrders", args("{}"));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
            assertThat(logs).contains("userId=7", "role=USER", "purpose=READ_ONLY_TOOL_TEST", "TOOL_FORBIDDEN", "latencyMs=")
                    .doesNotContain("SECRET_ARGUMENT", "SECRET_TOOL", "PRIVATE_LOGIN");
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
