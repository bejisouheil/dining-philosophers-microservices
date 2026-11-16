package fr.cytech.dining.philosopher.token;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.philosopher.config.PhilosopherIdentity;
import fr.cytech.dining.philosopher.config.PhilosopherProperties;

/** Composants du protocole par jetons, créés uniquement en mode {@code token}. */
@Configuration
@ConditionalOnProperty(name = "philosopher.mode", havingValue = "token")
public class TokenConfiguration {

    @Bean
    HttpPeerTransport peerTransport(PhilosopherProperties properties) {
        return new HttpPeerTransport(properties::peerUrl, Duration.ofMillis(properties.requestTimeoutMs()));
    }

    @Bean
    TokenStateStore tokenStateStore(PhilosopherProperties properties, PhilosopherIdentity identity) {
        if (properties.stateDir().isBlank()) {
            return TokenStateStore.NONE;
        }
        return new FileTokenStateStore(Path.of(properties.stateDir(), "philosopher-" + identity.id() + ".json"));
    }

    @Bean
    TokenNode tokenNode(PhilosopherIdentity identity, LamportClock clock, PeerTransport transport,
                        TokenStateStore store) {
        return new TokenNode(identity.id(), identity.count(), clock, transport, store);
    }

    /** Reprise après panne, avant que la boucle du philosophe ne démarre. */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationListener<ApplicationReadyEvent> tokenRecovery(TokenNode node) {
        return event -> node.recover();
    }
}
