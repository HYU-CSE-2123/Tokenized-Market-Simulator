package com.pricetrack.exchange.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.tool.read.ToolReadFacade;
import com.pricetrack.exchange.ai.tool.receipt.*;
import com.pricetrack.exchange.blockchain.config.*;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.market.MarketPriceService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import org.springframework.context.annotation.*;

/** No RAG/provider/store dependency; construction never queries a database or remote RPC. */
@Configuration
public class ToolConfiguration {
    @Bean ToolRegistry toolRegistry(ObjectMapper json) { return new ToolRegistry(json); }
    @Bean ToolAudit toolAudit() { return new ToolAudit(); }
    @Bean ToolReadFacade toolReadFacade(EntityManager em, MarketPriceService market, ObjectMapper json, ToolProperties p) {
        return new ToolReadFacade(em, market, json, p, Clock.systemUTC());
    }
    @Bean(destroyMethod = "close") ReadOnlyReceiptClient readOnlyReceiptClient(BlockchainProperties blockchain,
            BlockchainReconciliationProperties reconciliation, ContractEventParser parser, ObjectMapper json) {
        return new ReadOnlyReceiptClient(blockchain, reconciliation, parser, json);
    }
    @Bean(destroyMethod = "close") ToolDispatcher toolDispatcher(ToolProperties p, ToolRegistry registry,
            ToolReadFacade reads, ReceiptReader receipts, ToolAudit audit, ObjectMapper json) {
        return new ToolDispatcher(p, registry, reads, receipts, audit, json);
    }
}
