package com.pricetrack.exchange.ai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.ai.AiFailure;
import com.pricetrack.exchange.ai.AiProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 승인 manifest만 읽는다. 링크 추적·디렉터리 전체 ingest·경로 탈출은 하지 않는다. */
public class KnowledgeLoader {
    private final AiProperties properties;
    private final ObjectMapper json;
    public KnowledgeLoader(AiProperties properties, ObjectMapper json) { this.properties = properties; this.json = json; }
    public KnowledgeCorpus load() {
        try {
            Path root = Path.of(properties.knowledgeRoot()).toRealPath();
            var manifest = json.readTree(Files.readString(Path.of(properties.manifest())));
            if (manifest.path("version").asInt() != 1 || !manifest.path("documents").isArray()
                    || manifest.path("documents").size() > 50) throw new AiFailure("AI_MANIFEST_INVALID");
            List<KnowledgeCorpus.Document> documents = new ArrayList<>();
            List<KnowledgeCorpus.Chunk> chunks = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (var entry : manifest.path("documents")) {
                String path = entry.path("path").asText();
                if (!path.matches("[a-z0-9-]+\\.md") || !seen.add(path)) throw new AiFailure("AI_MANIFEST_INVALID");
                Path source = root.resolve(path).toRealPath();
                if (!source.startsWith(root) || Files.size(source) > 100_000) throw new AiFailure("AI_SOURCE_INVALID");
                String text = normalize(Files.readString(source));
                String hash = hash(text);
                if (!hash.equals(entry.path("sha256").asText())) throw new AiFailure("AI_APPROVAL_MISMATCH");
                int end = text.indexOf("\n---\n", 4);
                if (!text.startsWith("---\n") || end < 0) throw new AiFailure("AI_METADATA_INVALID");
                LoaderOptions options = new LoaderOptions();
                options.setAllowDuplicateKeys(false);
                options.setMaxAliasesForCollections(0);
                Object yaml = new Yaml(new SafeConstructor(options)).load(text.substring(4, end));
                if (!(yaml instanceof Map<?, ?> values)) throw new AiFailure("AI_METADATA_INVALID");
                Map<String, String> metadata = new TreeMap<>();
                for (String key : List.of("title", "domain", "type", "version", "status", "minimum_role", "updated_at")) {
                    Object value = values.get(key);
                    if (value == null || value.toString().isBlank()) throw new AiFailure("AI_METADATA_INVALID");
                    // YAML date may be materialized as java.util.Date; preserve ISO spelling from source instead.
                    metadata.put(key, value.toString());
                }
                String dateLine = text.substring(4, end).lines().filter(l -> l.startsWith("updated_at:")).findFirst().orElseThrow();
                LocalDate.parse(dateLine.substring(11).trim());
                metadata.put("updated_at", dateLine.substring(11).trim());
                if (!"active".equals(metadata.get("status"))
                        || !Set.of("USER", "ADMIN").contains(metadata.get("minimum_role"))
                        || !metadata.get("version").matches("[1-9][0-9]*")
                        || !metadata.get("version").equals(entry.path("documentVersion").asText()))
                    throw new AiFailure("AI_APPROVAL_MISMATCH");
                documents.add(new KnowledgeCorpus.Document(path, hash, Map.copyOf(metadata)));
                chunks.addAll(new MarkdownChunker(properties.chunkTokens(), properties.overlapTokens())
                        .split(path, hash, metadata, text.substring(end + 5)));
            }
            documents.sort(Comparator.comparing(KnowledgeCorpus.Document::path));
            String identity = properties.embeddingModel() + ":1536:heading-byte-bound-v1:"
                    + properties.chunkTokens() + ":" + properties.overlapTokens()
                    + documents.stream().map(d -> "\n" + d.path() + ":" + d.hash()).reduce("", String::concat);
            return new KnowledgeCorpus(hash(identity), List.copyOf(documents), List.copyOf(chunks));
        } catch (AiFailure e) { throw e; }
        catch (Exception e) { throw new AiFailure("AI_KNOWLEDGE_INVALID"); }
    }
    public static String normalize(String value) { return value.replace("\r\n", "\n").replace("\r", "\n"); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}

