package com.wxx.aidocumentagent.reading;

@FunctionalInterface
public interface ReadingModel {
    Reply complete(String phase, String system, String user);
    record Reply(String text, long promptTokens, long completionTokens) { }
}
