package com.wxx.aidocumentagent.agent;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentToolRequirementsTest {
    @Test
    void honorsExplicitReadOperationsAndOptOuts() {
        assertThat(AgentToolRequirements.from("先核对文档元数据并读取相邻片段，再回答问题"))
                .isEqualTo(new AgentToolRequirements(true, true));
        assertThat(AgentToolRequirements.from("Read adjacent context and verify document metadata."))
                .isEqualTo(new AgentToolRequirements(true, true));
        assertThat(AgentToolRequirements.from("无需核对元数据，也不用读取相邻上下文，直接回答。"))
                .isEqualTo(new AgentToolRequirements(false, false));
        assertThat(AgentToolRequirements.from("What does the document say?"))
                .isEqualTo(new AgentToolRequirements(false, false));
    }
}
