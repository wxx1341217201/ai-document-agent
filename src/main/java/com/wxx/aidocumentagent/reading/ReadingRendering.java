package com.wxx.aidocumentagent.reading;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.wxx.aidocumentagent.reading.ReadingState.*;

final class ReadingRendering {
    private ReadingRendering() { }
    static List<String> gaps(ReadingState state, List<DocumentRef> scope) {
        var gaps = new ArrayList<>(state.gaps);
        gaps.addAll(state.materialGaps);
        state.goals.stream().filter(g -> !g.covered()).map(g -> "待完成：" + g.description()).forEach(gaps::add);
        if (state.comprehensive) scope.forEach(doc -> {
            long read = state.cursors.getOrDefault(doc.id(), Cursor.start()).reviewedChunks();
            if (read < doc.chunks()) gaps.add(doc.name() + "：已审阅 " + read + " / " + doc.chunks() + " 个文本片段");
        });
        if (state.stopReason != null) gaps.add(state.stopReason);
        return gaps.stream().distinct().limit(20).toList();
    }

    static String render(String goal, ReadingState state, List<DocumentRef> scope) {
        var out = new StringBuilder("# 阅读笔记\n\n## 阅读目标\n\n").append(escape(goal)).append("\n\n");
        var labels = new LinkedHashMap<String, String>();
        labels.put("FACT", "原文事实"); labels.put("COMMENTARY", "评点观点");
        labels.put("INFERENCE", "分析与推论"); labels.put("DISPUTE", "争议与不同解释");
        labels.forEach((kind, title) -> {
            var selected = state.findings.stream().filter(f -> f.kind().equals(kind)).toList();
            if (!selected.isEmpty()) {
                out.append("## ").append(title).append("\n\n");
                selected.forEach(f -> out.append("- ").append(escape(f.text())).append(" ")
                        .append(f.supports().stream().map(s -> "[" + s.citationId() + "]").distinct()
                                .collect(java.util.stream.Collectors.joining(" "))).append("\n"));
                out.append("\n");
            }
        });
        if (state.findings.isEmpty()) out.append("尚未形成经过证据核验的可交付结论。\n\n");
        var missing = gaps(state, scope);
        if (!missing.isEmpty()) {
            out.append("## 尚未解决的问题\n\n");
            missing.forEach(gap -> out.append("- ").append(escape(gap)).append("\n"));
            out.append("\n");
        }
        var used = state.findings.stream().flatMap(f -> f.supports().stream()).map(Support::citationId)
                .collect(java.util.stream.Collectors.toSet());
        if (!used.isEmpty()) out.append("## 原文依据\n\n");
        state.sources.stream().filter(s -> used.contains(s.citationId())).forEach(s -> {
            out.append("### [").append(s.citationId()).append("] ").append(escape(s.documentName()))
                    .append(" · 片段 ").append(s.chunkIndex() + 1);
            if (s.pageFrom() != null) out.append(" · PDF 文件页 ").append(s.pageFrom()).append("–").append(s.pageTo());
            out.append("\n\n资料版本：").append(s.documentVersion()).append("\n\n");
            state.findings.stream().flatMap(f -> f.supports().stream())
                    .filter(support -> support.citationId().equals(s.citationId())).map(Support::quote).distinct()
                    .forEach(quote -> out.append(quote.lines().map(line -> "> " + escape(line))
                            .collect(java.util.stream.Collectors.joining("\n"))).append("\n\n"));
        });
        return out.toString();
    }
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "\\[").replace("]", "\\]").replace("!", "\\!");
    }
    static String export(ReadingApi.Note note) {
        if (note.sources().isEmpty()) return note.content();
        var out = new StringBuilder(note.content()).append("\n\n## 引用快照（导出）\n\n");
        note.sources().forEach(s -> {
            out.append("### [").append(s.citationId()).append("] ").append(escape(s.documentName()))
                    .append(" · 片段 ").append(s.chunkIndex() + 1).append("\n\n资料版本：")
                    .append(s.documentVersion()).append("\n\n");
            if (s.pageFrom() != null) out.append("PDF 文件页：").append(s.pageFrom()).append("–").append(s.pageTo()).append("\n\n");
            out.append(s.text().lines().map(line -> "> " + escape(line)).collect(java.util.stream.Collectors.joining("\n"))).append("\n\n");
        });
        return out.toString();
    }
}
