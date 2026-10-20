package com.wxx.aidocumentagent.ingestion.benchmark;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Fixed 100-document corpus: 50 x 200 chunks, 50 x 20 chunks, 512 characters per chunk. */
public final class RabbitBenchmarkCorpus {
    private RabbitBenchmarkCorpus() { }
    public static Map<String, Object> generate(Path directory, ObjectMapper mapper) throws Exception {
        Files.createDirectories(directory);
        var entries = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 100; i++) {
            String id = "doc-%03d".formatted(i);
            int chunks = i % 2 == 0 ? 200 : 20;
            String sentence = "Document " + id + ": durable ingestion, bounded queues, deterministic embeddings and worker scheduling. ";
            String text = sentence.repeat(chunks * 512 / sentence.length() + 1).substring(0, chunks * 512 - 1) + "X";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Path file = directory.resolve(id + ".txt");
            if (Files.exists(file) && !java.util.Arrays.equals(Files.readAllBytes(file), bytes))
                throw new IllegalStateException("Corpus file differs from frozen generator: " + file);
            if (!Files.exists(file)) Files.write(file, bytes);
            entries.add(Map.of("id", id, "file", id + ".txt", "longDocument", i % 2 == 0,
                    "expectedChunks", chunks, "bytes", bytes.length, "sha256", LocalBenchmarkIndex.digest(text)));
        }
        var manifest = Map.<String, Object>of("schema", "m19e-corpus-v1", "documents", entries,
                "documentCount", 100, "longDocumentRatio", 0.5, "averageChunks", 110,
                "averageBytes", 56320, "chunkSizeCharacters", 512, "overlap", 0);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        Path target = directory.resolve("manifest.json");
        if (Files.exists(target) && !mapper.readTree(Files.readString(target)).equals(mapper.valueToTree(manifest)))
            throw new IllegalStateException("Manifest differs from frozen generator");
        if (!Files.exists(target)) Files.writeString(target, json, StandardCharsets.UTF_8);
        return manifest;
    }
    public static void main(String[] args) throws Exception {
        generate(Path.of(args.length == 0 ? "benchmarks/rabbitmq/data" : args[0]), JsonMapper.builder().build());
    }
}
