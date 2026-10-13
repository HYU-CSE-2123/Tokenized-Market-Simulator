package com.pricetrack.exchange.support;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Test-only identity guard. Validates the new fixture before Spring can initialize schema. */
public final class PhaseAFixture {
    private static Properties verified;
    private PhaseAFixture() {}
    public static Properties optional(String gateRun) throws Exception {
        if(System.getenv("PHASE_A_SESSION")==null)return null;
        Path session=Path.of(System.getenv("PHASE_A_SESSION")).toRealPath();
        Path runtime=Path.of("../deployment/runtime").toRealPath();
        if(!session.getParent().equals(runtime)||!session.getFileName().toString().matches("phase-a-\\d{14}-[0-9a-f]{8}"))
            throw new IllegalStateException("Phase A session required");
        Path run=Path.of(Objects.requireNonNull(gateRun)).toRealPath();
        if(!run.getParent().equals(session.resolve("fixtures"))||!run.getFileName().toString().matches("(reserve|audit|faucet|conditional)-[0-9a-f]{8}"))
            throw new IllegalStateException("New Phase A child fixture required");
        var meta=new ObjectMapper().readTree(run.resolve("run.json").toFile());
        String id=run.getFileName().toString();
        if(!meta.path("runId").asText().equals(id)||!meta.path("session").asText().equals(session.getFileName().toString()))
            throw new IllegalStateException("Fixture identity mismatch");
        int db=meta.path("dbPort").asInt(),rpc=meta.path("rpcPort").asInt();
        if(db<30000||db>49000||rpc<30000||rpc>49000||db==rpc)throw new IllegalStateException("Unsafe fixture ports");
        var p=new Properties();try(var in=Files.newInputStream(run.resolve("secrets/test.properties"))){p.load(in);}
        String database=meta.path("database").asText();
        if(!Set.of("exchange_reserve_e2e","exchange_trade_audit_e2e").contains(database))throw new IllegalStateException("Unsafe DB name");
        if(!p.getProperty("spring.datasource.url","").equals("jdbc:postgresql://127.0.0.1:"+db+"/"+database+"?connectTimeout=2&socketTimeout=5")
            ||!p.getProperty("app.blockchain.rpc-url","").equals("http://127.0.0.1:"+rpc)
            ||!p.getProperty("spring.config.import","MISSING").isEmpty()
            ||!p.getProperty("app.price.provider","").equals("simulated"))throw new IllegalStateException("Unsafe fixture configuration");
        for(String flag:List.of("app.ai.enabled","app.ai.tools.enabled","app.ai.agent.enabled","app.ai.skills.enabled","app.ai.diagnosis.enabled"))
            if(!p.getProperty(flag,"").equals("false"))throw new IllegalStateException("AI must be disabled");
        if(!p.getProperty("app.price.toss.base-url","").equals("http://127.0.0.1:1"))throw new IllegalStateException("External provider not isolated");
        // Non-mutating marker query; wrong identity cannot reach schema initialization.
        try(var c=DriverManager.getConnection(p.getProperty("spring.datasource.url"),p.getProperty("spring.datasource.username"),p.getProperty("spring.datasource.password"));
            var s=c.createStatement()){
            s.setQueryTimeout(2);try(var rows=s.executeQuery("SELECT run_id FROM phase_a_fixture_identity")){
                if(!rows.next()||!rows.getString(1).equals(id)||rows.next())throw new IllegalStateException("Wrong PostgreSQL fixture identity");
            }
        }
        verified=p;return p;
    }
    public static String rpcOr(String legacy) {
        if(System.getenv("PHASE_A_SESSION")==null)return legacy;
        if(verified==null)throw new IllegalStateException("Phase A fixture not verified");
        return verified.getProperty("app.blockchain.rpc-url");
    }
}
