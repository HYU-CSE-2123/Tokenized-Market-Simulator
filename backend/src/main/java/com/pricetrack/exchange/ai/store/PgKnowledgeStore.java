package com.pricetrack.exchange.ai.store;

import com.pricetrack.exchange.ai.*;
import com.pricetrack.exchange.ai.knowledge.KnowledgeCorpus;
import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.*;
import com.pricetrack.exchange.user.UserRole;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;

/** JPA/DataSource Bean/TransactionManager와 분리된 private pool. AI DB만 단일 로컬 트랜잭션 사용. */
public class PgKnowledgeStore implements KnowledgeStore, AutoCloseable {
    private final HikariDataSource pool;
    public PgKnowledgeStore(AiProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.jdbcUrl()); config.setUsername(properties.dbUser()); config.setPassword(properties.dbPassword());
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(2000);
        config.setInitializationFailTimeout(-1); config.setPoolName("ai-knowledge");
        config.addDataSourceProperty("connectTimeout", "2"); config.addDataSourceProperty("socketTimeout", "5");
        pool = new HikariDataSource(config);
    }
    @Override public void initialize() {
        try (Connection c = pool.getConnection()) {
            try (Statement check = c.createStatement(); ResultSet db = check.executeQuery("select current_database()")) {
                db.next();
                if (!db.getString(1).matches("exchange_ai(_[a-z0-9_]+)?"))
                    throw new AiFailure("AI_DATABASE_TARGET_INVALID");
            }
            String sql = new ClassPathResource("ai/schema.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            try (Statement s = c.createStatement()) { s.setQueryTimeout(5); s.execute(sql); }
        } catch (Exception e) { throw new AiFailure("AI_DATABASE_UNAVAILABLE"); }
    }
    @Override public boolean active(String fingerprint) {
        try (Connection c = pool.getConnection(); PreparedStatement s = c.prepareStatement(
                "select exists(select 1 from ai.indexes where id=? and active)")) {
            s.setQueryTimeout(5); s.setString(1, fingerprint);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
        } catch (SQLException e) { throw new AiFailure("AI_DATABASE_UNAVAILABLE"); }
    }
    @Override public Map<String, float[]> cached(String model) {
        Map<String, float[]> result = new HashMap<>();
        try (Connection c = pool.getConnection(); PreparedStatement s = c.prepareStatement(
                "select ch.id,ch.embedding::text from ai.chunks ch join ai.indexes i on i.id=ch.index_id where i.model=?")) {
            s.setQueryTimeout(5); s.setString(1, model);
            try (ResultSet r = s.executeQuery()) { while (r.next()) result.put(r.getString(1), parseVector(r.getString(2))); }
            return result;
        } catch (SQLException e) { throw new AiFailure("AI_DATABASE_UNAVAILABLE"); }
    }
    @Override public void publish(KnowledgeCorpus corpus, Map<String, float[]> embeddings, String model) {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                execute(c, "select pg_advisory_xact_lock(502001)");
                execute(c, "update ai.indexes set active=false where active");
                execute(c, "insert into ai.indexes(id,model,dimensions,active) values(?,?,1536,false) on conflict(id) do nothing",
                        corpus.fingerprint(), model);
                for (var document : corpus.documents()) {
                    var m = document.metadata();
                    execute(c, """
                        insert into ai.documents(index_id,path,source_hash,version,title,minimum_role,domain,type,updated_at)
                        values(?,?,?,?,?,?,?,?,?) on conflict(index_id,path) do nothing
                        """, corpus.fingerprint(), document.path(), document.hash(), m.get("version"), m.get("title"),
                            m.get("minimum_role"), m.get("domain"), m.get("type"), m.get("updated_at"));
                }
                for (var chunk : corpus.chunks()) execute(c, """
                        insert into ai.chunks(index_id,id,path,heading,content,embedding)
                        values(?,?,?,?,?,?::vector) on conflict(index_id,id) do nothing
                        """, corpus.fingerprint(), chunk.id(), chunk.path(), chunk.heading(), chunk.content(), vector(embeddings.get(chunk.id())));
                execute(c, "update ai.indexes set active=true where id=?", corpus.fingerprint());
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw new AiFailure("AI_DATABASE_UNAVAILABLE"); }
    }
    @Override public List<KnowledgeHit> search(String fingerprint, float[] query, int topK, double threshold) {
        return searchForRole(fingerprint, query, topK, threshold, UserRole.ADMIN, Duration.ofSeconds(5));
    }
    @Override public List<KnowledgeHit> searchForRole(String fingerprint, float[] query, int topK,
            double threshold, UserRole role, Duration timeout) {
        return searchScoped(fingerprint,query,topK,threshold,role,null,timeout);
    }
    @Override public List<KnowledgeHit> searchForScope(String fingerprint,float[] query,int topK,double threshold,
            UserRole role,Set<String> domains,Duration timeout) {
        if(domains==null || domains.isEmpty() || domains.size()>4 || domains.stream().anyMatch(d ->
                !Set.of("trading","settlement","operations","support","market").contains(d)))throw new AiFailure("AI_DOMAIN_FILTER_INVALID");
        return searchScoped(fingerprint,query,topK,threshold,role,Set.copyOf(domains),timeout);
    }
    private List<KnowledgeHit> searchScoped(String fingerprint,float[] query,int topK,double threshold,
            UserRole role,Set<String> domains,Duration timeout) {
        if (role == null) throw new org.springframework.security.access.AccessDeniedException("Role required");
        String rolePredicate = role == UserRole.ADMIN ? "d.minimum_role in ('USER','ADMIN')" : "d.minimum_role='USER'";
        List<KnowledgeHit> result = new ArrayList<>();
        try (Connection c = pool.getConnection(); PreparedStatement s = c.prepareStatement("""
            select ch.id,d.path,d.title,ch.heading,d.version,d.minimum_role,ch.content,1-(ch.embedding <=> ?::vector) score
            from ai.chunks ch join ai.documents d on d.index_id=ch.index_id and d.path=ch.path
            join ai.indexes i on i.id=ch.index_id
            where i.active and i.id=? and %s
            and 1-(ch.embedding <=> ?::vector)>=? order by ch.embedding <=> ?::vector,ch.id limit ?
            """.formatted(rolePredicate+(domains==null?"":" and d.domain = any (?::text[])")))) {
            if (Thread.currentThread().isInterrupted() || timeout.isZero() || timeout.isNegative()) throw new AiFailure("AI_QUERY_TIMEOUT");
            s.setQueryTimeout((int)Math.max(1, Math.min(5, (timeout.toMillis()+999)/1000)));
            String vector = vector(query);
            s.setString(1, vector); s.setString(2, fingerprint);int parameter=3;
            if(domains!=null)s.setArray(parameter++,c.createArrayOf("text",domains.toArray(String[]::new)));
            s.setString(parameter++, vector);s.setDouble(parameter++, threshold); s.setString(parameter++, vector); s.setInt(parameter, topK);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) result.add(new KnowledgeHit(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                        r.getString(5),r.getString(6),r.getString(7),r.getDouble(8)));
            }
            return result;
        } catch (SQLException e) { throw new AiFailure("AI_DATABASE_UNAVAILABLE"); }
    }
    private static void execute(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setQueryTimeout(5);
            for (int i=0;i<args.length;i++) s.setString(i+1,args[i]);
            s.execute();
        }
    }
    public static String vector(float[] v) {
        if (v == null || v.length != AiProperties.DIMENSIONS) throw new AiFailure("AI_VECTOR_INVALID");
        StringJoiner out = new StringJoiner(",", "[", "]");
        double norm=0;
        for (float f : v) { if (!Float.isFinite(f)) throw new AiFailure("AI_VECTOR_INVALID"); norm += f*(double)f; out.add(Float.toString(f)); }
        if (norm==0) throw new AiFailure("AI_VECTOR_INVALID");
        return out.toString();
    }
    private static float[] parseVector(String value) {
        String[] values=value.substring(1,value.length()-1).split(",");
        float[] v = new float[values.length];
        for(int i=0;i<v.length;i++)v[i]=Float.parseFloat(values[i]);
        return v;
    }
    @Override public void close() { pool.close(); }
}
