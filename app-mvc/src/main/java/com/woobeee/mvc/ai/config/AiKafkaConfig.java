package com.woobeee.mvc.ai.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 토픽을 선언한다. 브로커가 떠 있으면 KafkaAdmin 이 없는 토픽만 만들고, 없으면 경고만 남기고 부팅한다 —
 * 접수는 브로커 없이도 성공해야 한다(AI-AC-03). 파티션은 1 이다: 직렬 처리가 요구사항이다(AI-AC-19).
 * 로컬 브로커가 1대라 복제 계수도 1 이다.
 */
@Configuration
public class AiKafkaConfig {

    @Bean
    public NewTopic aiRequestTopic() {
        return TopicBuilder.name(AiTopics.REQUEST).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic aiRequestDlqTopic() {
        return TopicBuilder.name(AiTopics.DLQ).partitions(1).replicas(1).build();
    }
}
