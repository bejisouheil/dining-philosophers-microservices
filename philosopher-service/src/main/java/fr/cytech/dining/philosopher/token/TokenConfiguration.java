package fr.cytech.dining.philosopher.token;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    TokenNode tokenNode(PhilosopherIdentity identity, LamportClock clock, PeerTransport transport) {
        return new TokenNode(identity.id(), identity.count(), clock, transport);
    }
}
