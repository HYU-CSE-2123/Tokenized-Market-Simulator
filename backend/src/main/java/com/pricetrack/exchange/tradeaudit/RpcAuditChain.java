package com.pricetrack.exchange.tradeaudit;

import static com.pricetrack.exchange.tradeaudit.AuditModels.*;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.reserve.ReserveModels.Baseline;
import jakarta.annotation.PreDestroy;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Hash;
import org.web3j.protocol.*;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;

/** Independent read-only client, bounded per-call timeout and total run budget. */
@Component @ConditionalOnProperty(name="app.trade-audit.enabled",havingValue="true")
public class RpcAuditChain implements AuditChain {
    private final Web3j rpc;
    private final BlockchainProperties config;
    private final int maximumCalls,seconds;
    public RpcAuditChain(BlockchainProperties config,@Value("${app.trade-audit.max-rpc-calls:500}") int calls,
            @Value("${app.trade-audit.timeout-seconds:30}") int seconds) {
        if(calls<1 || calls>10000 || seconds<1 || seconds>120) throw new IllegalArgumentException("Invalid audit budget");
        this.config=config; this.maximumCalls=calls; this.seconds=seconds;
        rpc=Web3j.build(new HttpService(config.rpcUrl(),new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(2)).build()));
    }
    @PreDestroy void close() { rpc.shutdown(); }
    public Session session() { return new Reads(); }
    private final class Reads implements Session {
        private final long deadline=System.nanoTime()+seconds*1_000_000_000L;
        private int count;
        private Block cut;
        private <T> T read(Supplier<Request<?,? extends Response<T>>> request) {
            if(!config.enabled()) throw new Unavailable("CHAIN_DISABLED");
            if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) throw new Unavailable("RUN_DEADLINE");
            if(count>=maximumCalls) throw new Unavailable("RPC_CALL_LIMIT");
            count++;
            try {
                var result=request.get().send();
                if(System.nanoTime()>deadline) throw new Unavailable("RUN_DEADLINE");
                if(result.hasError()) throw new Unavailable("RPC_READ_FAILED");
                return result.getResult();
            } catch(Unavailable e) { throw e; } catch(Exception e) { throw new Unavailable("RPC_READ_FAILED"); }
        }
        public int calls() { return count; }
        private Block block(DefaultBlockParameter parameter) {
            var b=read(()->rpc.ethGetBlockByNumber(parameter,false));
            if(b==null) throw new Unavailable("BLOCK_MISSING");
            return new Block(b.getNumber(),b.getHash(),b.getTimestamp());
        }
        public Block head() { Block b=block(DefaultBlockParameterName.LATEST); if(cut==null) cut=b; return b; }
        public boolean canonical(Block b) { return Objects.equals(block(DefaultBlockParameter.valueOf(b.number())).hash(),b.hash()); }
        public boolean environment(Baseline baseline) {
            var id=baseline.chain().identity();
            if(!config.enabled() || !new BigInteger(read(()->rpc.ethChainId()).substring(2),16).equals(id.chainId())) return false;
            if(!same(config.exchangeVaultAddress(),id.vault()) || !same(config.priceOracleAddress(),id.oracle())
                || !same(config.mockKrwAddress(),id.krw()) || !same(config.mSecAddress(),id.token())) return false;
            if(!same(org.web3j.crypto.Credentials.create(config.operatorPrivateKey()).getAddress(),id.operator())) return false;
            if(!canonical(new Block(baseline.chain().block().number(),baseline.chain().block().hash(),BigInteger.ZERO))) return false;
            for(var entry:id.codeHashes().entrySet()) {
                String code=read(()->rpc.ethGetCode(entry.getKey(),DefaultBlockParameter.valueOf(cut.number())));
                if(code==null || !same(Hash.sha3(code),entry.getValue())) return false;
            }
            return true;
        }
        private Object call(String address,String name,BigInteger block,TypeReference<?> output) {
            Function f=new Function(name,List.of(),List.of(output));
            String result=read(()->rpc.ethCall(org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null,address,FunctionEncoder.encode(f)),DefaultBlockParameter.valueOf(block)));
            var values=FunctionReturnDecoder.decode(result,f.getOutputParameters());
            if(values.size()!=1) throw new Unavailable("HISTORICAL_CONFIG_UNAVAILABLE");
            return values.getFirst().getValue();
        }
        public Evidence evidence(String hash) {
            var tx=read(()->rpc.ethGetTransactionByHash(hash));
            var receipt=read(()->rpc.ethGetTransactionReceipt(hash));
            if(tx==null || receipt==null) throw new Unavailable("RECEIPT_OR_TRANSACTION_MISSING");
            Block b=block(DefaultBlockParameter.valueOf(receipt.getBlockNumber()));
            if(!same(b.hash(),receipt.getBlockHash())) throw new Unavailable("REORG");
            if(!receipt.isStatusOK()) return new Evidence(tx,receipt,b,null,null,List.of());
            String signer; BigInteger fee; List<Log> changes;
            try {
                BigInteger previous=b.number().subtract(BigInteger.ONE);
                if(previous.signum()<0) throw new Unavailable("HISTORICAL_CONFIG_UNAVAILABLE");
                signer=(String)call(config.priceOracleAddress(),"priceSigner",previous,new TypeReference<Address>() {});
                fee=(BigInteger)call(config.exchangeVaultAddress(),"feeBps",previous,new TypeReference<Uint256>() {});
                changes=logs(b.number(),b.number(),List.of(config.exchangeVaultAddress(),config.priceOracleAddress()),false);
                for(Log log:changes) {
                    if(!same(log.getBlockHash(),b.hash())) throw new Unavailable("REORG");
                    if(log.getTransactionIndex().compareTo(receipt.getTransactionIndex())>=0 || log.getTopics().isEmpty()) continue;
                    if(same(log.getAddress(),config.priceOracleAddress()) && same(log.getTopics().getFirst(),Hash.sha3String("PriceSignerUpdated(address,address)"))) {
                        if(log.getTopics().size()!=3) throw new Unavailable("HISTORICAL_CONFIG_UNAVAILABLE");
                        signer="0x"+log.getTopics().get(2).substring(26);
                    }
                    if(same(log.getAddress(),config.exchangeVaultAddress()) && same(log.getTopics().getFirst(),Hash.sha3String("FeeBpsUpdated(uint256,uint256)"))) {
                        var values=FunctionReturnDecoder.decode(log.getData(),new Function("values",List.of(),List.of(new TypeReference<Uint256>() {},new TypeReference<Uint256>() {})).getOutputParameters());
                        if(values.size()!=2) throw new Unavailable("HISTORICAL_CONFIG_UNAVAILABLE");
                        fee=(BigInteger)values.get(1).getValue();
                    }
                }
            } catch(Unavailable e) { throw e; } catch(Exception e) { throw new Unavailable("HISTORICAL_CONFIG_UNAVAILABLE"); }
            return new Evidence(tx,receipt,b,signer,fee,changes);
        }
        private List<Log> logs(BigInteger from,BigInteger to,List<String> addresses,boolean vaultOnly) {
            EthFilter filter=new EthFilter(DefaultBlockParameter.valueOf(from),DefaultBlockParameter.valueOf(to),addresses);
            if(vaultOnly) filter.addOptionalTopics(Hash.sha3String("Bought(address,uint256,uint256,uint256,uint256)"),Hash.sha3String("Sold(address,uint256,uint256,uint256,uint256)"));
            var result=read(()->rpc.ethGetLogs(filter));
            if(result==null || result.size()>10000) throw new Unavailable("CHAIN_SCAN_LIMIT");
            var out=new ArrayList<Log>();
            for(var item:result) {
                if(!(item.get() instanceof Log l) || l.isRemoved()) throw new Unavailable("CHAIN_SCAN_INVALID");
                out.add(l);
            }
            out.sort(Comparator.comparing(Log::getBlockNumber).thenComparing(Log::getTransactionIndex).thenComparing(Log::getLogIndex));
            return out;
        }
        public List<Log> scan(BigInteger from,BigInteger to) {
            var output=new ArrayList<Log>();
            for(BigInteger start=from;start.compareTo(to)<=0;start=start.add(BigInteger.valueOf(500))) {
                BigInteger end=start.add(BigInteger.valueOf(499)).min(to);
                output.addAll(logs(start,end,List.of(config.exchangeVaultAddress()),true));
                if(output.size()>10000) throw new Unavailable("CHAIN_SCAN_LIMIT");
            }
            return List.copyOf(output);
        }
    }
    static boolean same(String a,String b) { return a!=null && b!=null && a.equalsIgnoreCase(b); }
}
