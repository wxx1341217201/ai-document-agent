package com.wxx.aidocumentagent.chunking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChunkingProperties.class)
class ChunkingConfiguration {
}
