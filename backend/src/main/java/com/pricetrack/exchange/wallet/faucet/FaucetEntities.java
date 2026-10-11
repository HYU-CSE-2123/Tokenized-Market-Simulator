package com.pricetrack.exchange.wallet.faucet;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import org.hibernate.annotations.Immutable;

/** Schema mappings for isolated H2 regression. Production uses explicit SQL; writes use JDBC. */
public final class FaucetEntities {
    private FaucetEntities() {}
    @Entity @Table(name="faucet_policy_versions") @Immutable
    public static class Policy {
        @Id @Column(length=64) String version;
        @Column(nullable=false,length=64) String contentHash;
        @Column(nullable=false,length=512) String content;
        @Column(nullable=false) Instant createdAt;
    }
    @Entity @Table(name="faucet_account_usage")
    public static class Account {
        @Id Long userId;
        @Column(nullable=false) long grantCount;
        LocalDate lastDay;
    }
    @Entity @Table(name="faucet_budget_usage")
    public static class Budget {
        @Id Integer id;
        @Column(nullable=false,length=64) String activeVersion;
        @Column(nullable=false) long grantCount;
        @Column(nullable=false,precision=30,scale=18) BigDecimal spent;
        @Column(nullable=false) boolean initialized;
    }
    @Entity @Table(name="faucet_claims",uniqueConstraints=@UniqueConstraint(columnNames={"user_id","key_fingerprint"})) @Immutable
    public static class Claim {
        @Id @Column(length=36) String grantId;
        @Column(nullable=false) Long userId;
        @Column(nullable=false,length=64) String keyFingerprint;
        @Column(nullable=false,length=64) String requestFingerprint;
        @Column(nullable=false,length=64) String policyVersion;
        @Column(nullable=false,precision=30,scale=18) BigDecimal receivedAmount;
        @Column(nullable=false,precision=30,scale=18) BigDecimal balance;
        @Column(nullable=false) Instant grantedAt;
    }
}
