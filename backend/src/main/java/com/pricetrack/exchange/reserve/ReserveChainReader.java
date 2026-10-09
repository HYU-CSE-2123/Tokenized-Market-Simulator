package com.pricetrack.exchange.reserve;
import static com.pricetrack.exchange.reserve.ReserveModels.*;
import java.util.Set;

/** Only reads state at explicitly identified blocks; cannot sign, send, fund or mine. */
public interface ReserveChainReader {
    Block head();
    boolean canonical(Block block);
    Chain read(Block block);
    boolean receiptMatches(TradeRow trade,OrderRow order,TxRow tx,Chain chain);
    boolean hasUnknownTransfers(Block from,Block to,Set<String> knownTransactions);
}
