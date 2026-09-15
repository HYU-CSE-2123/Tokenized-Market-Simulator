package com.pricetrack.exchange.market.provider.toss;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TossPriceConfigurationTest {
    @Test
    void failsWhenServerDoesNotRespondWithinReadTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread stalledServer = Thread.startVirtualThread(() -> acceptWithoutResponding(server));
            TossPriceProperties properties = new TossPriceProperties(
                    "http://127.0.0.1:" + server.getLocalPort(),
                    "client",
                    "secret",
                    "005930",
                    Duration.ofMillis(200),
                    Duration.ofMillis(100));
            RestClient restClient = new TossPriceConfiguration().tossRestClient(properties);
            TossAuthClient authClient = new TossAuthClient(restClient, properties);

            assertThatThrownBy(authClient::accessToken)
                    .isInstanceOf(TossApiException.class)
                    .hasMessageContaining("토큰 발급에 실패");

            stalledServer.interrupt();
            stalledServer.join(1_000);
        }
    }

    private static void acceptWithoutResponding(ServerSocket server) {
        try (Socket ignored = server.accept()) {
            Thread.sleep(5_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // 테스트 정리 과정에서 서버 소켓이 먼저 닫힐 수 있다.
        }
    }
}
