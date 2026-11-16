package fr.cytech.dining.philosopher;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.philosopher.core.PhilosopherLoop;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "philosopher.id=2",
        "philosopher.auto-start=false",
        "philosopher.events-transport=none",
        "philosopher.think-min-ms=1", "philosopher.think-max-ms=2",
        "philosopher.eat-min-ms=1", "philosopher.eat-max-ms=2"
})
class PhilosopherServiceApplicationTests {

    @Value("${local.server.port}")
    int port;

    @Autowired
    PhilosopherLoop loop;

    @Test
    void lApiExposeLEtatDuPhilosophe() {
        RestClient client = RestClient.create("http://localhost:" + port);

        PhilosopherLoop.Status status = client.get().uri("/state").retrieve().body(PhilosopherLoop.Status.class);

        assertThat(status).isNotNull();
        assertThat(status.id()).isEqualTo(2);
        assertThat(status.mode()).isEqualTo("spoiled");
        assertThat(status.state()).isEqualTo(PhilosopherLoop.State.THINKING);
        assertThat(status.running()).isFalse();
    }

    @Test
    void laBoucleSeDemarreEtSArretePArLApi() throws InterruptedException {
        RestClient client = RestClient.create("http://localhost:" + port);

        PhilosopherLoop.Status started = client.post().uri("/control/start").retrieve()
                .body(PhilosopherLoop.Status.class);
        assertThat(started.running()).isTrue();
        Thread.sleep(200);
        PhilosopherLoop.Status stopped = client.post().uri("/control/stop").retrieve()
                .body(PhilosopherLoop.Status.class);

        assertThat(stopped.running()).isFalse();
        assertThat(stopped.meals()).isGreaterThan(0);
        assertThat(loop.isRunning()).isFalse();
    }

    @Test
    void lesSondesDeSanteRepondent() {
        RestClient client = RestClient.create("http://localhost:" + port);
        String health = client.get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(health).contains("UP");
    }
}
