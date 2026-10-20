package com.wxx.aidocumentagent.agent;

import java.util.List;

/** Offsets are located by the server in the exact visible snapshot, never accepted from model output. */
public record ClaimEvidenceBinding(String claim, List<SourceSpan> sources) {
    public ClaimEvidenceBinding { sources = List.copyOf(sources); }

    public record SourceSpan(AgentCitation citation, String sourceVersionSha256, String visibleSha256, int start, int end, String quote) { }
}
