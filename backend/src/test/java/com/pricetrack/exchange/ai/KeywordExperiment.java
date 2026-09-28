package com.pricetrack.exchange.ai;

import com.pricetrack.exchange.ai.store.KnowledgeHit;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;

/** 평가 전용 PostgreSQL exact-identifier FTS + RRF. 서비스 검색 경로에는 연결하지 않는다. */
final class KeywordExperiment {
    static List<KnowledgeHit> fuse(AiProperties p,String fingerprint,String question,List<KnowledgeHit> ranked,double threshold) throws Exception {
        var terms=Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}").matcher(question).results()
                .map(m -> m.group().toLowerCase(Locale.ROOT)).distinct().limit(6).toList();
        var semantic=ranked.stream().filter(h -> h.similarity()>=threshold).limit(40).toList();
        if(terms.isEmpty()) return semantic;
        var byId=new HashMap<String,KnowledgeHit>();ranked.forEach(h -> byId.put(h.id(),h));
        List<KnowledgeHit> keyword=new ArrayList<>();
        String query=String.join(" || ",Collections.nCopies(terms.size(),"plainto_tsquery('simple',?)"));
        String exact=String.join("|",terms); // regex 문법이 없는 allowlist 토큰만 사용한다.
        String sql="""
            WITH q AS (SELECT %s AS value), eligible AS (
              SELECT ch.id,ch.content,setweight(to_tsvector('simple',d.title),'A') ||
                setweight(to_tsvector('simple',ch.heading),'B') || to_tsvector('simple',ch.content) AS terms
              FROM ai.chunks ch JOIN ai.documents d ON d.index_id=ch.index_id AND d.path=ch.path
              JOIN ai.indexes i ON i.id=ch.index_id
              WHERE i.active AND i.id=? AND d.minimum_role IN ('USER','ADMIN'))
            SELECT id FROM eligible,q WHERE terms @@ q.value AND content ~* ?
            ORDER BY ts_rank_cd(terms,q.value) DESC,id LIMIT 40
            """.formatted(query);
        try(var c=DriverManager.getConnection(p.jdbcUrl(),p.dbUser(),p.dbPassword());var s=c.prepareStatement(sql)) {
            s.setQueryTimeout(5);int index=1;for(var term:terms)s.setString(index++,term);
            s.setString(index++,fingerprint);s.setString(index,"\\m("+exact+")\\M");
            try(var r=s.executeQuery()){while(r.next())keyword.add(byId.get(r.getString(1)));}
        }
        Map<String,Double> scores=new HashMap<>();
        for(var list:List.of(semantic,keyword))for(int i=0;i<list.size();i++)scores.merge(list.get(i).id(),1.0/(60+i+1),Double::sum);
        return scores.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed().thenComparing(id -> id))
                .map(byId::get).toList();
    }
}
