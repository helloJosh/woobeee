package com.woobeee.mvc.ai;

import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.config.AiTopics;
import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import com.woobeee.mvc.ai.service.AiOutboxPublisher;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실 브로커로 발행기를 한 번 돌려 본다 — 멱등 프로듀서·SASL 설정·토픽 키가 실제로 맞는지는 목으로는 모른다.
 * 로컬 compose 의 Kafka(localhost:8011, SASL)가 떠 있을 때만 돈다. 없으면 건너뛴다(CI 가 없고, 브로커 없이도
 * 접수는 성공해야 하므로 이 테스트가 필수는 아니다). DB 는 쓰지 않는다 — 리포지터리는 목이다.
 */
@SuppressWarnings("unchecked")
class AiKafkaRoundTripTest {

    private static Map<String, Object> common() {
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:8011"));
        props.put("security.protocol", "SASL_PLAINTEXT");
        props.put("sasl.mechanism", "PLAIN");
        props.put("sasl.jaas.config",
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"admin\" password=\"admin!23\";");
        return props;
    }

    private static void requireBroker() {
        Map<String, Object> props = common();
        props.put("request.timeout.ms", 3000);
        props.put("default.api.timeout.ms", 3000);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.describeCluster().nodes().get(3, TimeUnit.SECONDS);
            admin.createTopics(List.of(new NewTopic(AiTopics.REQUEST, 1, (short) 1))).all().get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (!(ex.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) {
                Assumptions.abort("no usable Kafka broker: " + ex.getCause());
            }
        } catch (Exception ex) {
            Assumptions.abort("no usable Kafka broker: " + ex);
        }
    }

    @Test
    void AI_AC_03_theOutboxPublisherSendsAKeyedEventThroughAnIdempotentProducerToARealBroker() {
        requireBroker();
        Map<String, Object> producer = common();
        producer.put("key.serializer", StringSerializer.class);
        producer.put("value.serializer", StringSerializer.class);
        producer.put("acks", "all");
        producer.put("enable.idempotence", true);
        producer.put("max.block.ms", 5000);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producer));

        AiOutboxRepository outbox = mock(AiOutboxRepository.class);
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AiOutbox row = mock(AiOutbox.class);
        when(row.getEventId()).thenReturn(eventId);
        when(row.getRequestId()).thenReturn(requestId);
        LocalDateTime now = LocalDateTime.now();
        when(outbox.lockDue(now, 20)).thenReturn(List.of(row));
        AiOutboxPublisher publisher = new AiOutboxPublisher(outbox, mock(AiRequestRepository.class), template,
                new AiProperties(null, null, 0, 0, 0, 0L, null, null));

        assertThat(publisher.publishOnce(now)).isEqualTo(1);
        verify(outbox).markPublished(eventId);

        Map<String, Object> consumer = common();
        consumer.put("key.deserializer", StringDeserializer.class);
        consumer.put("value.deserializer", StringDeserializer.class);
        consumer.put("enable.auto.commit", false);
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(consumer)) {
            TopicPartition partition = new TopicPartition(AiTopics.REQUEST, 0);
            reader.assign(List.of(partition));
            reader.seekToBeginning(List.of(partition));

            ConsumerRecord<String, String> found = null;
            long deadline = System.currentTimeMillis() + 15_000;
            while (found == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(500))) {
                    if (requestId.toString().equals(record.key())) {
                        found = record;
                    }
                }
            }

            assertThat(found).as("the published event should be readable from %s", AiTopics.REQUEST).isNotNull();
            assertThat(found.value()).contains(eventId.toString()).contains("\"version\":1");
        }
    }
}
