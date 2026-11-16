package fr.cytech.dining.philosopher.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(PhilosopherConfiguration.class);

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

    /**
     * Séquence de démarrage, dans cet ordre précis :
     * <ol>
     *   <li>recaler l'horloge de Lamport sur la plus grande date connue du moniteur, pour que les
     *       événements d'après un redémarrage soient datés après ceux d'avant ;</li>
     *   <li>annoncer le démarrage (événement STARTED) ;</li>
     *   <li>reprise du protocole par jetons après une panne ;</li>
     *   <li>lancer la boucle penser/manger.</li>
     * </ol>
     */
    @Bean
    ApplicationListener<ApplicationReadyEvent> startup(PhilosopherProperties properties, PhilosopherLoop loop,
                                                       LamportClock clock, ObjectProvider<TokenNode> tokenNode) {
        return event -> {
            synchronizeClockWithMonitor(properties, clock);
            loop.announceStart();
            tokenNode.ifAvailable(TokenNode::recover);
            if (properties.autoStart()) {
                loop.start();
            }
        };
    }

    private record MonitorClock(long lamportTime) {
    }

    private static void synchronizeClockWithMonitor(PhilosopherProperties properties, LamportClock clock) {
        if ("none".equalsIgnoreCase(properties.eventsTransport())) {
            return; // aucun moniteur dans cette configuration
        }
        try {
            MonitorClock monitor = HttpSupport.restClient(properties.monitorUrl(),
                            Duration.ofMillis(properties.requestTimeoutMs()))
                    .get().uri("/api/clock").retrieve().body(MonitorClock.class);
            if (monitor != null) {
                clock.update(monitor.lamportTime());
            }
        } catch (RuntimeException e) {
            log.info("Moniteur injoignable au démarrage : horloge non recalée ({})", e.getMessage());
        }
    }
}
