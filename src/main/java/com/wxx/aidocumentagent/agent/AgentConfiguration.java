package com.wxx.aidocumentagent.agent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.wxx.aidocumentagent.agent.react.ReActProperties;
import com.wxx.aidocumentagent.agent.plan.PlanProperties;

/** 后续模块只需注册 DocumentAgent Bean；当前允许零实现以保持 M13 独立。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RetrievalFirstProperties.class, ReActProperties.class, PlanProperties.class})
public class AgentConfiguration {

    @Bean
    AgentEventPublisher agentEventPublisher(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    AgentRouter agentRouter(ObjectProvider<DocumentAgent> agents) {
        return new AgentRouter(agents.orderedStream().toList());
    }
}
