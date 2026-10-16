package com.pricetrack.exchange.reserve;

import static com.pricetrack.exchange.reserve.ReserveModels.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/** Exact quantity conservation, deliberately independent of market valuation or solvency promises. */
public final class ReserveCalculator {
    private ReserveCalculator() {}
    public static List<Check> calculate(DbCut db,Chain initial,Chain actual) {
        var checks=new ArrayList<Check>();
        BigInteger k=BigInteger.ZERO,s=BigInteger.ZERO,dk=BigInteger.ZERO,ds=BigInteger.ZERO;
        var expected=new TreeMap<String,BigInteger>();
        for(Grant grant:db.grants()) expected.merge(grant.userId()+":mKRW",grant.amount(),BigInteger::add);
        for(TradeRow trade:db.trades()) {
            boolean buy=trade.side().equals("BUY");
            BigInteger krw=buy?trade.quote().negate():trade.quote();
            BigInteger sec=buy?trade.base():trade.base().negate();
            expected.merge(trade.userId()+":mKRW",krw,BigInteger::add);
            expected.merge(trade.userId()+":mSEC",sec,BigInteger::add);
            if(trade.mode().equals("ONCHAIN")) { dk=dk.add(krw); ds=ds.add(sec); }
        }
        for(Balance b:db.balances()) {
            String key=b.userId()+":"+b.symbol();
            checks.add(check("USER_"+key,expected.getOrDefault(key,BigInteger.ZERO),b.amount()));
            expected.remove(key);
            if(b.symbol().equals("mKRW")) k=k.add(b.amount()); else if(b.symbol().equals("mSEC")) s=s.add(b.amount());
            boolean valid=b.amount().signum()>=0 && b.locked().signum()>=0 && b.locked().compareTo(b.amount())<=0;
            checks.add(check("LOCK_"+key,BigInteger.ZERO,valid?b.locked():BigInteger.valueOf(-1)));
        }
        for(var missing:expected.entrySet()) checks.add(check("MISSING_USER_"+missing.getKey(),missing.getValue(),BigInteger.ZERO));
        checks.add(check("OPERATOR_KRW",initial.operatorKrw().add(dk),actual.operatorKrw()));
        checks.add(check("VAULT_KRW",initial.vaultKrw().subtract(dk),actual.vaultKrw()));
        checks.add(check("OPERATOR_SEC",initial.operatorSec().add(ds),actual.operatorSec()));
        checks.add(check("VAULT_SEC",initial.vaultSec(),actual.vaultSec()));
        checks.add(check("SEC_SUPPLY",initial.secSupply().add(ds),actual.secSupply()));
        checks.add(check("KRW_SUPPLY",initial.krwSupply(),actual.krwSupply()));
        checks.add(check("MANAGED_KRW",initial.operatorKrw().add(initial.vaultKrw()),actual.operatorKrw().add(actual.vaultKrw())));
        checks.add(check("CONTRACT_ROLES",BigInteger.ONE,actual.rolesValid()?BigInteger.ONE:BigInteger.ZERO));
        return List.copyOf(checks);
    }
    static Check check(String name,BigInteger expected,BigInteger actual) {
        return new Check(name,expected.toString(),actual.toString(),actual.subtract(expected).toString(),expected.equals(actual));
    }
    public static Liquidity liquidity(DbCut db,Chain chain) {
        BigInteger k=BigInteger.ZERO,s=BigInteger.ZERO;
        for(Balance b:db.balances()) {
            if(b.symbol().equals("mKRW")) k=k.add(b.amount());
            if(b.symbol().equals("mSEC")) s=s.add(b.amount());
        }
        String reference=k.signum()>0?new BigDecimal(chain.operatorKrw()).divide(new BigDecimal(k),4,RoundingMode.DOWN).toPlainString():null;
        return new Liquidity(chain.operatorKrw().toString(),chain.allowance().toString(),chain.operatorSec().toString(),
                chain.vaultKrw().toString(),k.toString(),s.toString(),reference,
                "Quantity-only execution resources. Allocation reference is not a reserve guarantee or Proof of Reserves; no price-based redemption promise.",
                chain.operatorEthWei(),chain.operatorEthWei()==null?"BALANCE_UNAVAILABLE_NOT_ESTIMATED":"READ_ONLY_NOT_ESTIMATED");
    }
}
