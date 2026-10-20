package com.wxx.aidocumentagent.rag.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvidenceOverlapTest {
    @Test
    void sharedSuffixMovesToTheLaterPassageWithoutChangingSourcePrefixes() {
        String overlap = "The service retries five times before stopping. ".repeat(5);
        String earlier = "Earlier independent content. ".repeat(30) + overlap;
        String later = overlap + "Then a distinct later fact.";
        var result = EvidenceOverlap.prefixes(List.of(new EvidenceOverlap.Part(11, 21, earlier, 500),
                new EvidenceOverlap.Part(12, 21, later, 100)));
        assertThat(result.get(11L)).isEqualTo(earlier.substring(0, earlier.length()-overlap.length()));
        assertThat(result.get(12L)).isEqualTo(later);
        assertThat(earlier).startsWith(result.get(11L));
        assertThat(result.get(12L)).contains(earlier.substring(result.get(11L).length()));
    }

    @Test
    void differentDocumentsAndUnselectedContinuationsNeverRemoveEvidence() {
        String overlap = "shared wording ".repeat(20);
        String first = "unique content ".repeat(50) + overlap;
        var crossDocument = EvidenceOverlap.prefixes(List.of(new EvidenceOverlap.Part(11, 21, first, 500),
                new EvidenceOverlap.Part(12, 22, overlap + "tail", 100)));
        assertThat(crossDocument.get(11L)).isEqualTo(first);
        assertThat(EvidenceOverlap.prefixes(List.of(new EvidenceOverlap.Part(11, 21, first, 500))).get(11L))
                .isEqualTo(first);
    }
}
