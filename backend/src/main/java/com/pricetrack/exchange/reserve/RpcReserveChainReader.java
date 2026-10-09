package com.pricetrack.exchange.reserve;

import static com.pricetrack.exchange.reserve.ReserveModels.*;
import com.pricetrack.exchange.blockchain.config.BlockchainProperties;
import com.pricetrack.exchange.blockchain.contract.ContractEventParser;
import com.pricetrack.exchange.blockchain.transaction.BlockchainTransactionType;
import java.math.BigInteger;
import java.time.Duration;
import java.util.*;
import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.abi.*;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.protocol.*;
import org.web3j.protocol.core.*;
import org.web3j.protocol.core.methods.request.*;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;

/** Separate two-second read-only RPC client. Number is pinned; hash is verified before/after reads. */
@Component @ConditionalOnProperty(name="app.reserve.enabled",havingValue="true")
public class RpcReserveChainReader implements ReserveChainReader {
    private final BlockchainProperties config;
    private final ContractEventParser parser;
    private final Web3j rpc;
    private final String operator;
    public RpcReserveChainReader(BlockchainProperties config,ContractEventParser parser) {
        this.config=config; this.parser=parser;
        rpc=Web3j.build(new HttpService(config.rpcUrl(),new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(2)).build()));
        operator=Credentials.create(config.operatorPrivateKey()).getAddress().toLowerCase(Locale.ROOT);
    }
    @PreDestroy void close() { rpc.shutdown(); }
    @Override public Block head() {
        try {
            var r=rpc.ethGetBlockByNumber(DefaultBlockParameterName.LATEST,false).send();
            if(r.hasError() || r.getBlock()==null) throw new IllegalStateException();
            return new Block(r.getBlock().getNumber(),r.getBlock().getHash());
        } catch(Exception e) { throw new ReadUnavailable(); }
    }
    @Override public boolean canonical(Block b) {
        try {
            var r=rpc.ethGetBlockByNumber(DefaultBlockParameter.valueOf(b.number()),false).send();
            return !r.hasError() && r.getBlock()!=null && Objects.equals(b.hash(),r.getBlock().getHash());
        } catch(Exception e) { throw new ReadUnavailable(); }
    }
    @Override public Chain read(Block b) {
        try {
            if(!config.enabled() || !canonical(b)) throw new ReadUnavailable();
            var chainId=rpc.ethChainId().send();
            if(chainId.hasError()) throw new ReadUnavailable();
            var hashes=new TreeMap<String,String>();
            for(String address:List.of(config.mockKrwAddress(),config.mSecAddress(),config.exchangeVaultAddress(),config.priceOracleAddress())) {
                var code=rpc.ethGetCode(address,DefaultBlockParameter.valueOf(b.number())).send();
                if(code.hasError() || code.getCode()==null || code.getCode().equals("0x")) throw new ReadUnavailable();
                hashes.put(address.toLowerCase(Locale.ROOT),Hash.sha3(code.getCode()));
            }
            String k=config.mockKrwAddress(),s=config.mSecAddress(),v=config.exchangeVaultAddress(),o=config.priceOracleAddress();
            String signer=address(o,"priceSigner",b);
            var identity=new Identity(chainId.getChainId(),operator,lower(k),lower(s),lower(v),lower(o),signer,Map.copyOf(hashes));
            boolean roles=address(s,"minter",b).equals(lower(v))
                && address(o,"authorizedConsumer",b).equals(lower(v))
                && address(v,"krw",b).equals(lower(k)) && address(v,"token",b).equals(lower(s))
                && address(v,"oracle",b).equals(lower(o)) && address(v,"owner",b).equals(operator)
                && !signer.equals(operator) && uint(k,"decimals",b).equals(BigInteger.valueOf(18))
                && uint(s,"decimals",b).equals(BigInteger.valueOf(18));
            return new Chain(identity,b,balance(k,operator,b),balance(s,operator,b),balance(k,v,b),balance(s,v,b),
                    uint(k,"totalSupply",b),uint(s,"totalSupply",b),
                    uint(k,"allowance",b,new Address(operator),new Address(v)),uint(v,"feeBps",b),roles,ethBalance(b));
        } catch(ReadUnavailable e) { throw e; } catch(Exception e) { throw new ReadUnavailable(); }
    }
    private String lower(String s) { return s.toLowerCase(Locale.ROOT); }
    /** Optional observation, not a gas estimate or a prerequisite for ledger consistency. */
    private String ethBalance(Block b) {
        try {
            var result=rpc.ethGetBalance(operator,DefaultBlockParameter.valueOf(b.number())).send();
            return result.hasError()?null:result.getBalance().toString();
        } catch(Exception unavailable) { return null; }
    }
    private BigInteger balance(String token,String who,Block b) { return uint(token,"balanceOf",b,new Address(who)); }
    private BigInteger uint(String contract,String method,Block b,Type... args) {
        return (BigInteger) call(contract,new Function(method,List.of(args),List.of(new TypeReference<Uint256>() {})),b);
    }
    private String address(String contract,String method,Block b) {
        return lower((String) call(contract,new Function(method,List.of(),List.of(new TypeReference<Address>() {})),b));
    }
    private Object call(String contract,Function f,Block b) {
        try {
            var r=rpc.ethCall(Transaction.createEthCallTransaction(null,contract,FunctionEncoder.encode(f)),
                    DefaultBlockParameter.valueOf(b.number())).send();
            if(r.hasError() || r.getValue()==null || r.getValue().equals("0x")) throw new ReadUnavailable();
            var values=FunctionReturnDecoder.decode(r.getValue(),f.getOutputParameters());
            if(values.size()!=1) throw new ReadUnavailable(); return values.getFirst().getValue();
        } catch(Exception e) { throw new ReadUnavailable(); }
    }
    @Override public boolean receiptMatches(TradeRow t,OrderRow o,TxRow tx,Chain c) {
        try {
            var r=rpc.ethGetTransactionReceipt(t.hash()).send();
            if(r.hasError()) throw new ReadUnavailable();
            if(r.getTransactionReceipt().isEmpty()) return false;
            var receipt=r.getTransactionReceipt().get();
            if(!receipt.isStatusOK() || tx.block()==null || !receipt.getBlockNumber().equals(BigInteger.valueOf(tx.block()))
                    || receipt.getBlockNumber().compareTo(c.block().number())>0
                    || !Objects.equals(lower(receipt.getFrom()),c.identity().operator())
                    || !Objects.equals(lower(receipt.getTo()),c.identity().vault())
                    || !canonical(new Block(receipt.getBlockNumber(),receipt.getBlockHash()))) return false;
            var e=parser.parse(receipt,BlockchainTransactionType.valueOf(o.side()),c.identity().vault(),c.identity().operator(),o.input());
            boolean buy=o.side().equals("BUY");
            return e.outputAmount().equals(buy?t.base():t.quote()) && e.fee().equals(t.fee())
                    && e.priceE8().equals(t.priceE8());
        } catch(ReadUnavailable e) { throw e; } catch(Exception e) { return false; }
    }
    @Override public boolean hasUnknownTransfers(Block from,Block to,Set<String> known) {
        if(from.number().equals(to.number())) return false;
        if(to.number().subtract(from.number()).compareTo(BigInteger.valueOf(10000))>0) throw new ReserveStore.CutLimitException();
        try {
            var filter=new EthFilter(DefaultBlockParameter.valueOf(from.number().add(BigInteger.ONE)),
                    DefaultBlockParameter.valueOf(to.number()),List.of(config.mockKrwAddress(),config.mSecAddress()));
            filter.addSingleTopic(Hash.sha3String("Transfer(address,address,uint256)"));
            var response=rpc.ethGetLogs(filter).send();
            if(response.hasError()) throw new ReadUnavailable();
            if(response.getLogs().size()>10000) throw new ReserveStore.CutLimitException();
            for(var item:response.getLogs()) {
                if(!(item.get() instanceof Log log) || log.isRemoved() || !canonical(new Block(log.getBlockNumber(),log.getBlockHash())))
                    throw new ReadUnavailable();
                if(!known.contains(lower(log.getTransactionHash()))) return true;
            }
            return false;
        } catch(ReserveStore.CutLimitException e) { throw e; } catch(Exception e) { throw new ReadUnavailable(); }
    }
    public static class ReadUnavailable extends RuntimeException {}
}
