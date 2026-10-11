package com.pricetrack.exchange.blockchain.observation;

import java.time.*;
import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** One independent connection with bounded SQL; no business transaction or balance locks. */
@Component
public class ObservationStore {
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;
    public ObservationStore(DataSourceProperties properties) {
        var config=new HikariConfig();
        config.setJdbcUrl(properties.determineUrl()); config.setUsername(properties.determineUsername());
        config.setPassword(properties.determinePassword()); config.setDriverClassName(properties.determineDriverClassName());
        config.setPoolName("transaction-observation"); config.setMaximumPoolSize(1); config.setMinimumIdle(0);
        config.setConnectionTimeout(250); config.setInitializationFailTimeout(-1);
        if(properties.determineUrl().startsWith("jdbc:postgresql:"))
            config.setConnectionInitSql("SET statement_timeout='2s'; SET lock_timeout='250ms'; SET TIME ZONE 'UTC'");
        else if(properties.determineUrl().startsWith("jdbc:h2:"))config.setConnectionInitSql("SET TIME ZONE 'UTC'");
        pool=new HikariDataSource(config); jdbc=new JdbcTemplate(pool); jdbc.setQueryTimeout(2);
    }
    /** Single writer per process. Conditional update keeps duplicates/older starts from replacing latest. */
    public synchronized void save(ObservationRecorder.Attempt a) {
        LocalDateTime started=LocalDateTime.ofInstant(a.startedAt(),ZoneOffset.UTC),finished=LocalDateTime.ofInstant(a.finishedAt(),ZoneOffset.UTC),recorded=LocalDateTime.ofInstant(Instant.now(),ZoneOffset.UTC);
        int updated=jdbc.update("UPDATE blockchain_transaction_observations SET attempt_id=?,started_at=?,finished_at=?,phase=?,result_code=?,attempt_count=attempt_count+1,recorded_at=? WHERE transaction_id=? AND started_at < ? AND attempt_id <> ?",
            a.attemptId(),started,finished,a.phase().name(),a.result().name(),recorded,a.transactionId(),started,a.attemptId());
        if(updated==0)jdbc.update("INSERT INTO blockchain_transaction_observations(transaction_id,attempt_id,started_at,finished_at,phase,result_code,attempt_count,recorded_at) SELECT ?,?,?,?,?,?,1,? WHERE NOT EXISTS (SELECT 1 FROM blockchain_transaction_observations WHERE transaction_id=?)",
            a.transactionId(),a.attemptId(),started,finished,a.phase().name(),a.result().name(),recorded,a.transactionId());
    }
    @PreDestroy public void close(){pool.close();}
}
