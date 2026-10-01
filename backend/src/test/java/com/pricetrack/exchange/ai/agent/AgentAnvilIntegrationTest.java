package com.pricetrack.exchange.ai.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.tool.*;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.ReadOnlyReceiptClient;
import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.config.*;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.*;
import com.pricetrack.exchange.user.UserRole;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.http.HttpService;
import static com.pricetrack.exchange.ai.agent.AgentModelProvider.*;

/** Fresh local Anvil: real NOT_FOUND RPC, no deployment, broadcast, signing or funding. */
@EnabledIfEnvironmentVariable(named="AI_AGENT_ANVIL_TESTS",matches="true")
@SpringBootTest(properties={"app.ai.enabled=false","app.ai.tools.enabled=true","app.blockchain.enabled=false",
    "app.blockchain.price-report.enabled=false","app.admin.password=",
    "spring.datasource.url=jdbc:h2:mem:agent_anvil_eval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
class AgentAnvilIntegrationTest {
    @Autowired ToolReadFacade reads;@Autowired ObjectMapper json;
    @Autowired OrderRepository orders;@Autowired BlockchainTransactionRepository txs;
    @Test void agentReadsActualRpcAndPreservesChainAndJpaState()throws Exception{
        var web3j=Web3j.build(new HttpService("http://127.0.0.1:8545"));
        try{
            var height=web3j.ethBlockNumber().send().getBlockNumber();
            var nonce=web3j.ethGetTransactionCount(ToolFixtures.ADDRESS,DefaultBlockParameterName.PENDING).send().getTransactionCount();
            var balance=web3j.ethGetBalance(ToolFixtures.ADDRESS,DefaultBlockParameterName.LATEST).send().getBalance();
            var order=orders.saveAndFlush(ToolFixtures.order(820001));var tx=txs.saveAndFlush(ToolFixtures.tx(order.getId()));
            var provider=mock(AgentModelProvider.class);
            when(provider.plan(any(),any(),any())).thenReturn(new Plan(Route.MIXED,Subject.ORDER,Usage.none()));
            var chain=new BlockchainProperties(true,"http://127.0.0.1:8545","","","",ToolFixtures.ADDRESS,"");
            try(var rpc=new ReadOnlyReceiptClient(chain,new BlockchainReconciliationProperties(1000,1000,1),new ContractEventParser(),json);
                var tools=new ToolDispatcher(new ToolProperties(true),new ToolRegistry(json),reads,rpc,new ToolAudit(),json);
                var agent=new AgentService(new AgentProperties(true),()->provider,()->null,tools,json)){
                var result=agent.answer(new AuthenticatedUser(820001L,"unused",UserRole.USER),
                    json.writeValueAsBytes(Map.of("question","왜 대기 중인가요?","target",Map.of("orderId",order.getId()))));
                assertThat(result.status()).isEqualTo("PARTIAL");
                assertThat(result.toolEvidence()).filteredOn(e -> e.tool().equals("getReceiptSummary")).singleElement()
                    .satisfies(e -> assertThat(e.data().path("receiptStatus").asText()).isEqualTo("NOT_FOUND"));
                assertThat(result.uncertainties()).contains("RECEIPT_NOT_FOUND","RAG_UNAVAILABLE");
                assertThat(txs.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(BlockchainTransactionStatus.REVIEW_REQUIRED);
                assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING_ONCHAIN);
                assertThat(web3j.ethBlockNumber().send().getBlockNumber()).isEqualTo(height);
                assertThat(web3j.ethGetTransactionCount(ToolFixtures.ADDRESS,DefaultBlockParameterName.PENDING).send().getTransactionCount()).isEqualTo(nonce);
                assertThat(web3j.ethGetBalance(ToolFixtures.ADDRESS,DefaultBlockParameterName.LATEST).send().getBalance()).isEqualTo(balance);
            }
        }finally{web3j.shutdown();}
    }
}
