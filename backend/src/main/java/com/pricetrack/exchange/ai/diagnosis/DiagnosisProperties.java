package com.pricetrack.exchange.ai.diagnosis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Initial safety limits, separate from trading and long-pending policy. */
@ConfigurationProperties("app.ai.diagnosis")
public record DiagnosisProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String actorLoginId,@DefaultValue("") String sourceNamespace,
        @DefaultValue("5000") long pollIntervalMs,@DefaultValue("50") int batchSize,
        @DefaultValue("100") int queueLimit,@DefaultValue("20") int dailyLimit,
        @DefaultValue("7") int retentionDays) {
    public boolean valid(){return actorLoginId!=null && actorLoginId.matches("[a-zA-Z0-9_-]{4,30}")
        && sourceNamespace!=null && sourceNamespace.matches("[a-zA-Z0-9_-]{1,64}")
        && pollIntervalMs>=1000 && pollIntervalMs<=60000 && batchSize>=1 && batchSize<=100
        && queueLimit>=1 && queueLimit<=1000 && dailyLimit>=1 && dailyLimit<=100
        && retentionDays>=1 && retentionDays<=30;}
}
