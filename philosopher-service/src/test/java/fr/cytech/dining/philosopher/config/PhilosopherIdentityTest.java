package fr.cytech.dining.philosopher.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhilosopherIdentityTest {

    private static PhilosopherProperties properties(int id) {
        return new PhilosopherProperties(id, 5, Mode.SPOILED, false, 0, 0, 0, 0, "http://monitor",
                "http://supervisor", "none", "topic", "http://philosopher-{id}:{port}", 8081, "zk:2181",
                1000, 1000, 10);
    }

    @Test
    void lIdentifiantExpliciteALaPriorite() {
        assertThat(PhilosopherIdentity.resolveId(properties(4), "philosopher-1", 8082)).isEqualTo(4);
    }

    @Test
    void sinonOnLitLeNumeroALaFinDuNomDHote() {
        assertThat(PhilosopherIdentity.resolveId(properties(-1), "philosopher-3", 8081)).isEqualTo(3);
    }

    @Test
    void sinonOnDeduitLIdentifiantDuPort() {
        assertThat(PhilosopherIdentity.resolveId(properties(-1), "PC-de-test", 8083)).isEqualTo(2);
    }

    @Test
    void parDefautOnEstLePhilosopheZero() {
        assertThat(PhilosopherIdentity.resolveId(properties(-1), null, 0)).isZero();
    }

    @Test
    void unIdentifiantHorsBornesEstRefuse() {
        assertThatThrownBy(() -> new PhilosopherIdentity(5, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PhilosopherIdentity(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leGabaritDAdresseRemplaceIdEtPort() {
        assertThat(properties(0).peerUrl(2)).isEqualTo("http://philosopher-2:8083");
    }
}
