package com.pricetrack.exchange.tradeaudit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AuditBudgetTest {
    @Test void callsAndDeadlinePreventFurtherRpc() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();byte[] body=("{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"number\":\"0x1\",\"timestamp\":\"0x1\",\"hash\":\"0x"+"a".repeat(64)+"\"}}").getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        var config=mock(BlockchainProperties.class);when(config.rpcUrl()).thenReturn("http://127.0.0.1:"+server.getAddress().getPort());
        when(config.enabled()).thenReturn(true);
        var limited=new RpcAuditChain(config,1,30);var timed=new RpcAuditChain(config,5,1);
        try {
            var s=limited.session();s.head();assertThatThrownBy(s::head).hasMessage("RPC_CALL_LIMIT");assertThat(s.calls()).isEqualTo(1);assertThat(calls.get()).isEqualTo(1);
            var timeout=timed.session();Thread.sleep(1100);assertThatThrownBy(timeout::head).hasMessage("RUN_DEADLINE");assertThat(timeout.calls()).isZero();assertThat(calls.get()).isEqualTo(1);
        } finally {limited.close();timed.close();server.stop(0);}
    }
}
