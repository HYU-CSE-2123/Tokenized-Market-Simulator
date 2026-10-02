package com.pricetrack.exchange.ai.diagnosis;

import com.pricetrack.exchange.auth.AuthenticatedUser;
import com.pricetrack.exchange.blockchain.transaction.*;
import com.pricetrack.exchange.order.Order;
import com.pricetrack.exchange.user.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;

/** Short, non-locking trading reads; returns no signing material or exception body. */
@Transactional(readOnly=true,timeout=2)
public class DiagnosisSourceReader {
    private final EntityManager em;
    public DiagnosisSourceReader(EntityManager em){this.em=em;}
    public record Target(long transactionId,Long orderId,String txHash,String status,String type) {
        public boolean valid(){return orderId!=null && orderId>0 && txHash!=null && txHash.matches("0x[0-9a-fA-F]{64}")
            && "REVIEW_REQUIRED".equals(status) && Set.of("BUY","SELL").contains(type);}
    }
    public List<Target> scan(long after,int size){
        return em.createQuery("select t.id,t.orderId,t.txHash,t.status,t.type from BlockchainTransaction t where t.status=:status and t.type in :types and t.id>:after order by t.id",Object[].class)
            .setParameter("status",BlockchainTransactionStatus.REVIEW_REQUIRED)
            .setParameter("types",List.of(BlockchainTransactionType.BUY,BlockchainTransactionType.SELL))
            .setParameter("after",after).setMaxResults(size).setHint("jakarta.persistence.query.timeout",2000)
            .getResultList().stream().map(this::target).toList();
    }
    public Target current(long id){
        return em.createQuery("select t.id,t.orderId,t.txHash,t.status,t.type from BlockchainTransaction t where t.id=:id",Object[].class)
            .setParameter("id",id).setMaxResults(1).setHint("jakarta.persistence.query.timeout",2000).getResultList()
            .stream().findFirst().map(this::target).orElse(null);
    }
    public boolean linked(Target t){
        return t!=null && t.valid() && !em.createQuery("select o.id from Order o where o.id=:id and o.side=:side",Long.class)
            .setParameter("id",t.orderId()).setParameter("side",com.pricetrack.exchange.order.OrderSide.valueOf(t.type()))
            .setHint("jakarta.persistence.query.timeout",2000).setMaxResults(1).getResultList().isEmpty();
    }
    public AuthenticatedUser actor(String loginId){
        var rows=em.createQuery("select u.id,u.loginId,u.role from User u where u.loginId=:login",Object[].class)
            .setParameter("login",loginId.toLowerCase(Locale.ROOT)).setHint("jakarta.persistence.query.timeout",2000).setMaxResults(1).getResultList();
        if(rows.isEmpty() || rows.getFirst()[2]!=UserRole.ADMIN)throw new DiagnosisFailure("DIAGNOSIS_ACTOR_UNAVAILABLE");
        var u=rows.getFirst();return new AuthenticatedUser((Long)u[0],(String)u[1],UserRole.ADMIN);
    }
    private Target target(Object[] t){return new Target((Long)t[0],(Long)t[1],(String)t[2],t[3].toString(),t[4].toString());}
}
