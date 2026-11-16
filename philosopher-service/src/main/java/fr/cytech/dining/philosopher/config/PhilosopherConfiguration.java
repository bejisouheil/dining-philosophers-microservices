package fr.cytech.dining.philosopher.config;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;

import fr.cytech.dining.common.HttpSupport;
import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.philosopher.core.DiningStrategy;
import fr.cytech.dining.philosopher.core.ForkAssignment;
import fr.cytech.dining.philosopher.core.PhilosopherLoop;
import fr.cytech.dining.philosopher.core.SpoiledStrategy;
import fr.cytech.dining.philosopher.events.EventPublisher;
import fr.cytech.dining.philosopher.events.HttpEventPublisher;
import fr.cytech.dining.philosopher.events.KafkaEventPublisher;
import fr.cytech.dining.philosopher.graph.GraphWorker;
import fr.cytech.dining.philosopher.supervised.NaiveSupervisedStrategy;
import fr.cytech.dining.philosopher.supervised.PairSupervisedStrategy;
import fr.cytech.dining.philosopher.supervised.SupervisorClient;
import fr.cytech.dining.philosopher.token.TokenNode;
import fr.cytech.dining.philosopher.token.TokenStrategy;
import fr.cytech.dining.philosopher.zookeeper.ZooKeeperStrategy;
import io.micrometer.core.instrument.MeterRegistry;

/** Assemble le philosophe : identité, horloge, envoi des événements, stratégie, boucle. */
@Configuration
@EnableConfigurationProperties(PhilosopherProperties.class)
public class PhilosopherConfiguration {

    @Bean
    PhilosopherIdentity philosopherIdentity(PhilosopherProperties properties, Environment environment) {
        String hostname = environment.getProperty("HOSTNAME");
        int port = environment.getProperty("server.port", Integer.class, properties.basePort());
        return PhilosopherIdentity.resolve(properties, hostname, port);
    }

    @Bean
    GraphWorker graphWorker() {
        return new GraphWorker();
    }

    @Bean
    LamportClock lamportClock() {
        return new LamportClock();
    }

    @Bean
    EventPublisher eventPublisher(PhilosopherProperties properties,
                                  ObjectProvider<KafkaTemplate<String, String>> kafka) {
        return switch (properties.eventsTransport().toLowerCase()) {
            case "none" -> EventPublisher.NONE;
            case "http" -> new HttpEventPublisher(HttpSupport.restClient(
                    properties.monitorUrl(), Duration.ofMillis(properties.requestTimeoutMs())));
            case "kafka" -> new KafkaEventPublisher(kafka.getObject(), properties.eventsTopic());
            default -> throw new IllegalArgumentException(
                    "philosopher.events-transport inconnu : " + properties.eventsTransport());
        };
    }

    @Bean
    DiningStrategy diningStrategy(PhilosopherProperties properties, PhilosopherIdentity identity,
                                  LamportClock clock, ObjectProvider<TokenNode> tokenNode) {
        int id = identity.id();
        int count = identity.count();
        return switch (properties.mode()) {
            case SPOILED -> new SpoiledStrategy();
            case SUPERVISED_NAIVE -> new NaiveSupervisedStrategy(supervisorClient(properties, clock, id), id,
                    count, properties.pollMs());
            case SUPERVISED_UNSAFE -> new PairSupervisedStrategy(supervisorClient(properties, clock, id),
                    "unsafe", id, count, properties.pollMs());
            case SUPERVISED_LOCKED -> new PairSupervisedStrategy(supervisorClient(properties, clock, id),
                    "locked", id, count, properties.pollMs());
            case TOKEN -> new TokenStrategy(tokenNode.getObject(), ForkAssignment.forksFor(id, count),
                    properties.retransmitMs());
            case ZOOKEEPER -> ZooKeeperStrategy.connect(properties.zookeeperConnect(),
                    ForkAssignment.forksFor(id, count), clock);
        };
    }

    private static SupervisorClient supervisorClient(PhilosopherProperties properties, LamportClock clock,
                                                     int philosopherId) {
        return new SupervisorClient(HttpSupport.restClient(properties.supervisorUrl(),
                Duration.ofMillis(properties.requestTimeoutMs())), clock, philosopherId);
    }

    @Bean(destroyMethod = "stop")
    PhilosopherLoop philosopherLoop(PhilosopherIdentity identity, PhilosopherProperties properties,
                                    DiningStrategy strategy, EventPublisher publisher, LamportClock clock,
                                    MeterRegistry registry) {
        PhilosopherLoop.Timing timing = new PhilosopherLoop.Timing(properties.thinkMinMs(),
                properties.thinkMaxMs(), properties.eatMinMs(), properties.eatMaxMs());
        return new PhilosopherLoop(identity.id(), properties.mode().name().toLowerCase(), strategy, publisher,
                clock, timing, registry);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> autoStart(PhilosopherProperties properties, PhilosopherLoop loop) {
        return event -> {
            if (properties.autoStart()) {
                loop.start();
            }
        };
    }
}
