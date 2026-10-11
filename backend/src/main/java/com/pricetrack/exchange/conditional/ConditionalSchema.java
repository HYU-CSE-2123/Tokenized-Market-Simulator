package com.pricetrack.exchange.conditional;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Additive DDL only: no existing orders, balances, grants or chain data are migrated. */
@Component @DependsOn("entityManagerFactory")
public class ConditionalSchema {
    private final DataSource dataSource;
    public ConditionalSchema(DataSource dataSource){this.dataSource=dataSource;}
    @PostConstruct void initialize(){new ResourceDatabasePopulator(new ClassPathResource("conditional-schema.sql")).execute(dataSource);}
}
