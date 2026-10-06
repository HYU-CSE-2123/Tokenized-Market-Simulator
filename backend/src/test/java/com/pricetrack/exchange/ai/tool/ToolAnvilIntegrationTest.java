package com.pricetrack.exchange.ai.tool;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReadOnlyReceiptClient;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.config.*;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.blockchain.support.TokenUnits;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.user.UserRole;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;

/** Reuses a recent existing Anvil event. No deployment, private key, broadcast, funding or chain mutation. */
@EnabledIfEnvironmentVariable(named = "AI_TOOL_ANVIL_TESTS", matches = "true")
@SpringBootTest(properties = {"app.ai.tools.enabled=true", "app.ai.enabled=false", "app.blockchain.enabled=false",
        "app.blockchain.price-report.enabled=false", "app.admin.password=", "spring.datasource.url=jdbc:h2:mem:tool_anvil;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class ToolAnvilIntegrationTest {
    @Autowired ToolReadFacade reads;
    @Autowired ObjectMapper json;
    @Autowired OrderRepository orders;
    @Autowired BlockchainTransactionRepository transactions;
    @Test void existingReceiptMatchesAndReadCallsDoNotChangeChainOrDatabaseState() throws Exception {
        // Select an already-prepared isolated chain; never create a trade just for this read-only test.
        String rpc = System.getenv().getOrDefault("AI_TOOL_TEST_RPC_URL", "http://127.0.0.1:8545");
        Web3j web3j = Web3j.build(new HttpService(rpc));
        try {
            BigInteger height = web3j.ethBlockNumber().send().getBlockNumber();
            String bought = Hash.sha3String("Bought(address,uint256,uint256,uint256,uint256)");
            String sold = Hash.sha3String("Sold(address,uint256,uint256,uint256,uint256)");
            var filter = new EthFilter(DefaultBlockParameter.valueOf(height.subtract(BigInteger.valueOf(200)).max(BigInteger.ZERO)),
                    DefaultBlockParameterName.LATEST, List.of());
            filter.addOptionalTopics(bought, sold);
            var logs = web3j.ethGetLogs(filter).send().getLogs();
            assertThat(logs).as("Requires an existing Bought/Sold event in the last 200 local blocks").isNotEmpty();
            Log log = (Log) logs.getLast().get();
            String sender = "0x" + log.getTopics().get(1).substring(26);
            var type = log.getTopics().getFirst().equals(bought) ? BlockchainTransactionType.BUY : BlockchainTransactionType.SELL;
            BigInteger input = new BigInteger(log.getData().substring(2, 66), 16);
            BigInteger nonce = web3j.ethGetTransactionCount(sender, DefaultBlockParameterName.PENDING).send().getTransactionCount();
            BigInteger balance = web3j.ethGetBalance(sender, DefaultBlockParameterName.LATEST).send().getBalance();
            var order = ToolFixtures.order(710001); order.setInputAmount(TokenUnits.fromWei(input)); order.setSide(OrderSide.valueOf(type.name()));
            order = orders.saveAndFlush(order);
            var tx = ToolFixtures.tx(order.getId()); tx.setTxHash(log.getTransactionHash()); tx.setType(type); tx.setSenderAddress(sender);
            tx.setStatus(BlockchainTransactionStatus.SUBMITTED); tx = transactions.saveAndFlush(tx);
            var chain = new BlockchainProperties(true, rpc, "", "", "", log.getAddress(), "");
            try (var client = new ReadOnlyReceiptClient(chain, new BlockchainReconciliationProperties(1000, 1000, 1), new ContractEventParser(), json);
                 var dispatcher = new ToolDispatcher(new ToolProperties(true), new ToolRegistry(json), reads, client, new ToolAudit(), json)) {
                byte[] request = ("{\"arguments\":{\"orderId\":" + order.getId() + "}}").getBytes(StandardCharsets.UTF_8);
                var result = dispatcher.invoke(new AuthenticatedUser(710001L, "unused", UserRole.USER), "getReceiptSummary", request);
                assertThat(result.status()).isEqualTo("SUCCESS");
                assertThat(result.data().path("eventValidation").asText()).isEqualTo("MATCH");
                assertThat(result.data().path("executionStatus").asText()).isEqualTo("SUCCESS");
                assertThat(result.data().path("databaseStatus").asText()).isEqualTo("SUBMITTED");
                assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(BlockchainTransactionStatus.SUBMITTED);
                assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_ONCHAIN);
                assertThat(web3j.ethBlockNumber().send().getBlockNumber()).isEqualTo(height);
                assertThat(web3j.ethGetTransactionCount(sender, DefaultBlockParameterName.PENDING).send().getTransactionCount()).isEqualTo(nonce);
                assertThat(web3j.ethGetBalance(sender, DefaultBlockParameterName.LATEST).send().getBalance()).isEqualTo(balance);
            }
        } finally { web3j.shutdown(); }
    }
}
