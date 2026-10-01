package com.pricetrack.exchange.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;

/** Two bounded structured operations, no executable tool schema or unbounded model loop. */
public interface AgentModelProvider {
    enum Route { KNOWLEDGE, STATE, MIXED, CLARIFY, UNSUPPORTED }
    enum Subject { NONE, ORDER, QUOTE, MARKET, PRICE, PORTFOLIO, ABNORMAL }
    record Usage(long inputTokens,long outputTokens){public static Usage none(){return new Usage(0,0);}}
    record Plan(Route route,Subject subject,Usage usage) {}
    record FactReference(String evidenceId,String pointer,String value) {}
    record Generated(String status,String interpretation,List<String> citationIds,List<FactReference> facts,
                     List<String> uncertainties,List<String> recommendedNextCheck,Usage usage) {}
    Plan plan(String question,Subject targetKind,Duration timeout);
    Generated synthesize(String question,Route route,JsonNode evidence,Duration timeout);
}
