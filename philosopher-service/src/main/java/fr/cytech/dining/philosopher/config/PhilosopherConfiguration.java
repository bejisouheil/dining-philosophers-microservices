package fr.cytech.dining.philosopher.config;

import java.time.Duration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import fr.cytech.dining.common.HttpSupport;
import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.philosopher.core.DiningStrategy;
import fr.cytech.dining.philosopher.core.PhilosopherLoop;
import fr.cytech.dining.philosopher.core.SpoiledStrategy;
import fr.cytech.dining.philosopher.events.EventPublisher;
import fr.cytech.dining.philosopher.events.HttpEventPublisher;
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
    LamportClock lamportClock() {
        return new LamportClock();
    }

    @Bean
    EventPublisher eventPublisher(PhilosopherProperties properties) {
        return switch (properties.eventsTransport().toLowerCase()) {
            case "none" -> EventPublisher.NONE;
            case "http" -> new HttpEventPublisher(HttpSupport.restClient(
                    properties.monitorUrl(), Duration.ofMillis(properties.requestTimeoutMs())));
            default -> throw new IllegalArgumentException(
                    "philosopher.events-transport inconnu : " + properties.eventsTransport());
        };
    }

    @Bean
    DiningStrategy diningStrategy(PhilosopherProperties properties) {
        return switch (properties.mode()) {
            case SPOILED -> new SpoiledStrategy();
            default -> throw new IllegalStateException("Mode pas encore disponible : " + properties.mode());
        };
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
