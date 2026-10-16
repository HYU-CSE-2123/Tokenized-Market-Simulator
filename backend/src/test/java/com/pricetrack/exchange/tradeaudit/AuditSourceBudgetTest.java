package com.pricetrack.exchange.tradeaudit;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Oversized source is rejected by SQL before raw TEXT enters the JDBC row mapper. */
class AuditSourceBudgetTest {
    @ParameterizedTest @ValueSource(strings={"SINGLE","CUMULATIVE","UTF8"})
    void rawBytesCannotBypassJsonIgnore(String scenario) {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:audit-budget-"+scenario,"sa","");
        try(var connection=ds.getConnection()) {
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE orders(id BIGINT)");jdbc.execute("CREATE TABLE trades(id BIGINT)");
            jdbc.execute("CREATE TABLE blockchain_transactions(type VARCHAR(8),raw_transaction CLOB)");
            jdbc.execute("CREATE TABLE price_quotes(order_id BIGINT,status VARCHAR(20),signature CLOB)");
            if(scenario.equals("CUMULATIVE")) {
                jdbc.update("INSERT INTO blockchain_transactions VALUES('BUY',REPEAT('a',8000001)),('SELL',REPEAT('b',8000001))");
            } else jdbc.update("INSERT INTO blockchain_transactions VALUES('BUY',REPEAT(?,?))",scenario.equals("UTF8")?"한":"a",scenario.equals("UTF8")?6000000:16000001);
            assertThatThrownBy(()->new AuditStore(ds,new ObjectMapper()).sourceBudget(false)).isInstanceOf(AuditModels.Unavailable.class).hasMessage("DB_SNAPSHOT_LIMIT");
        } catch(java.sql.SQLException e) {throw new AssertionError(e);}
    }
}
