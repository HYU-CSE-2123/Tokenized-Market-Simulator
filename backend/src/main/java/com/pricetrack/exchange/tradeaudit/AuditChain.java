package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import com.pricetrack.exchange.reserve.ReserveModels.Baseline;
import java.math.BigInteger;
import java.util.List;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.core.methods.response.Transaction;

/** No signing/broadcast methods; each run owns its deadline and RPC allowance. */
public interface AuditChain {
    Session session();
    interface Session {
        Block head();
        boolean canonical(Block block);
        boolean environment(Baseline baseline);
        Evidence evidence(String hash);
        List<Log> scan(BigInteger from,BigInteger to);
        int calls();
    }
    record Evidence(Transaction transaction,TransactionReceipt receipt,Block block,
            String historicalSigner,BigInteger historicalFee,List<Log> blockLogs) {}
}
