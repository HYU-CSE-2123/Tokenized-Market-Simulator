package com.pricetrack.exchange.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 제목 경로를 보존한다. UTF-8 byte 수를 토큰 상한으로 사용해 한국어도 보수적으로 제한한다. */
public class MarkdownChunker {
    /** LEGACY는 Phase 2 고정 비교용이다. API 입력으로 선택하지 않는다. */
    public enum Mode { LEGACY, BOUNDARIES_ONLY, REFERENCES_ONLY, CLEAN }
    private final int limit;
    private final int overlap;
    private final Mode mode;
    public MarkdownChunker(int limit, int overlap) { this(limit, overlap, Mode.CLEAN); }
    public MarkdownChunker(int limit, int overlap, Mode mode) {
        this.limit = limit; this.overlap = overlap; this.mode = Objects.requireNonNull(mode);
    }
    public String version() {
        return mode == Mode.LEGACY ? "heading-byte-bound-v1" : "heading-byte-v2-" + mode.name();
    }
    public List<KnowledgeCorpus.Chunk> split(String path, String sourceHash, Map<String, String> meta, String body) {
        List<KnowledgeCorpus.Chunk> result = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder section = new StringBuilder();
        boolean fenced = false;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("```") || line.startsWith("~~~")) fenced = !fenced;
            if (!fenced && line.matches("#{1,6} .+")) {
                flush(result, path, sourceHash, meta, headings, section.toString());
                section.setLength(0);
                int level = line.indexOf(' ');
                while (headings.size() >= level) headings.removeLast();
                while (headings.size() < level - 1) headings.add("");
                headings.add(line.substring(level + 1).trim());
            } else section.append(line).append('\n');
        }
        flush(result, path, sourceHash, meta, headings, section.toString());
        return result;
    }
    private void flush(List<KnowledgeCorpus.Chunk> out, String path, String hash, Map<String, String> meta,
                       List<String> headings, String text) {
        if (text.isBlank()) return;
        // 설명이 섞인 절은 보존한다. 순수 근거 링크 목록만 검색 청크에서 제외하며 원문은 유지한다.
        if ((mode == Mode.CLEAN || mode == Mode.REFERENCES_ONLY)
                && !headings.isEmpty() && headings.getLast().equals("근거")
                && text.lines().filter(s -> !s.isBlank()).allMatch(s -> s.matches("\\s*- \\[.+]\\(.+\\)\\s*"))) return;
        String heading = String.join(" > ", headings.stream().filter(s -> !s.isBlank()).toList());
        String prefix = meta.get("title") + "\n" + heading + "\n";
        int budget = limit - bytes(prefix);
        if (budget < 64) throw new com.pricetrack.exchange.ai.AiFailure("AI_HEADING_TOO_LONG");
        // Paragraph/line boundaries are preferred; oversized rows keep a repeated table header.
        String tableHeader = "";
        String[] lines = text.strip().split("\n");
        for (int i = 0; i < lines.length - 1; i++)
            if (lines[i].startsWith("|") && lines[i + 1].matches("[| :\\-]+")) {
                tableHeader = lines[i] + "\n" + lines[i + 1] + "\n"; break;
            }
        String remaining = text.strip();
        int part = 0;
        while (!remaining.isBlank()) {
            String repeated = part > 0 && !tableHeader.isEmpty() ? tableHeader : "";
            int capacity = budget - bytes(repeated);
            if (capacity < 32) throw new com.pricetrack.exchange.ai.AiFailure("AI_TABLE_TOO_WIDE");
            int end = prefixEnd(remaining, capacity);
            if (end < remaining.length()) {
                if (mode == Mode.CLEAN || mode == Mode.BOUNDARIES_ONLY) {
                    int boundary = lastBoundary(remaining, end);
                    if (boundary > 0) end = boundary;
                } else {
                    int newline = remaining.lastIndexOf('\n', end - 1);
                    if (newline > end / 2) end = newline + 1;
                }
            }
            String slice = remaining.substring(0, end);
            String content = prefix + repeated + slice.strip();
            String id = KnowledgeLoader.hash(path + ":" + heading + ":" + part + ":" + content);
            out.add(new KnowledgeCorpus.Chunk(id, path, meta.get("title"), heading, meta.get("version"),
                    meta.get("minimum_role"), meta.get("domain"), meta.get("type"), hash, content));
            if (end == remaining.length()) break;
            int back = tableHeader.isEmpty() ? suffixStart(slice, Math.min(overlap, capacity / 4)) : slice.length();
            if (mode == Mode.CLEAN || mode == Mode.BOUNDARIES_ONLY) {
                // 바이트 길이만 맞춘 꼬리 조각 대신 온전한 문장/행만 overlap한다.
                // 한 문장도 예산에 들어오지 않으면 overlap을 생략한다.
                while (back < slice.length() && !boundaryAt(slice, back)) back++;
            }
            remaining = slice.substring(back) + remaining.substring(end);
            part++;
        }
    }
    private static boolean boundaryAt(String text, int index) {
        return index > 0 && (text.charAt(index - 1) == '\n'
                || (index > 1 && Character.isWhitespace(text.charAt(index - 1))
                    && ".!?。！？".indexOf(text.charAt(index - 2)) >= 0));
    }
    private static int lastBoundary(String text, int end) {
        for (int i = end; i > 0; i--) if (boundaryAt(text, i)) return i;
        return 0; // 예산보다 긴 단일 문장/행만 Unicode-safe hard split한다.
    }
    private static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
    private static int prefixEnd(String text, int max) {
        int i = 0, size = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i), n = Character.charCount(cp);
            int width = bytes(text.substring(i, i + n));
            if (size + width > max) break;
            size += width; i += n;
        }
        return i;
    }
    private static int suffixStart(String s, int max) {
        int i = s.length(), size = 0;
        while (i > 0) {
            int cp = s.codePointBefore(i), n = Character.charCount(cp);
            int width = bytes(s.substring(i - n, i));
            if (size + width > max) break;
            size += width; i -= n;
        }
        return i;
    }
}
