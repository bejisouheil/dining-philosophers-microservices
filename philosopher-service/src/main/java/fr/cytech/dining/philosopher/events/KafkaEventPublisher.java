package fr.cytech.dining.philosopher.events;

import java.util.concurrent.TimeUnit;

import org.springframework.kafka.core.KafkaTemplate;

import fr.cytech.dining.common.PhilosopherEvent;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publie les événements dans un topic Kafka (communication asynchrone, section 6).
 *
 * <p>Le philosophe n'a plus besoin de connaître le moniteur ni qu'il soit en marche : Kafka
 * conserve les événements et le moniteur les lit quand il peut. La clé du message est
 * l'identifiant du philosophe : tous ses événements vont dans la même partition, donc dans l'ordre.
 */
public class KafkaEventPublisher extends QueuedEventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    @Override
    protected void deliver(PhilosopherEvent event) throws Exception {
        String key = Integer.toString(event.philosopherId());
        kafka.send(topic, key, mapper.writeValueAsString(event)).get(5, TimeUnit.SECONDS);
    }
}
