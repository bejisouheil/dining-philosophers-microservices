package fr.cytech.dining.monitor;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.stereotype.Component;

import fr.cytech.dining.common.PhilosopherEvent;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lit les événements des philosophes dans Kafka (section 6), activé par
 * {@code monitor.kafka.enabled=true}.
 *
 * <p>À chaque démarrage le moniteur utilise un nouveau groupe de consommateurs et relit le topic
 * depuis le début : son historique, qui n'existe qu'en mémoire, est entièrement reconstruit à
 * partir de Kafka. Un moniteur en panne ne perd donc rien. Les messages lus deux fois sont écartés
 * par {@link EventStore}.
 */
@Component
@ConditionalOnProperty(name = "monitor.kafka.enabled", havingValue = "true")
public class KafkaEventListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventListener.class);

    private final EventStore store;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public KafkaEventListener(EventStore store) {
        this.store = store;
    }

    /** Crée le topic au démarrage s'il n'existe pas : 3 partitions, une clé par philosophe. */
    @Bean
    NewTopic philosopherEventsTopic(@Value("${monitor.kafka.topic:philosopher-events}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @KafkaListener(topics = "${monitor.kafka.topic:philosopher-events}",
            groupId = "${monitor.kafka.group:monitor-${random.uuid}}")
    public void onEvent(String json) {
        try {
            store.add(mapper.readValue(json, PhilosopherEvent.class));
        } catch (RuntimeException e) {
            log.warn("Message Kafka illisible, ignoré : {}", e.getMessage());
        }
    }
}
