package fr.cytech.dining.philosopher.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.EventType;
import fr.cytech.dining.common.PhilosopherEvent;

class HttpEventPublisherTest {

    private MockRestServiceServer monitor;
    private HttpEventPublisher publisher;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://monitor");
        monitor = MockRestServiceServer.bindTo(builder).build();
        publisher = new HttpEventPublisher(builder.build());
    }

    @AfterEach
    void tearDown() {
        publisher.close();
    }

    @Test
    void lEvenementEstEnvoyeEnJsonAuMoniteur() {
        monitor.expect(requestTo("http://monitor/events"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.philosopherId").value(1))
                .andExpect(jsonPath("$.type").value("HUNGRY"))
                .andExpect(jsonPath("$.lamportTime").value(7))
                .andRespond(withSuccess());

        publisher.publish(new PhilosopherEvent(1, EventType.HUNGRY, 7, 1, 0, List.of(1, 2), "test"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(monitor::verify);
        assertThat(publisher.droppedCount()).isZero();
    }

    @Test
    void unMoniteurEnPanneNeBloquePasEtNeFaitPasPlanterLePhilosophe() {
        monitor.expect(ExpectedCount.manyTimes(), requestTo("http://monitor/events")).andRespond(withServerError());

        long start = System.nanoTime();
        publisher.publish(new PhilosopherEvent(1, EventType.EATING, 1, 1, 0, List.of(), "test"));
        long publishMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(publishMillis).isLessThan(100); // publish() rend la main tout de suite
        await().atMost(Duration.ofSeconds(5)).until(() -> publisher.droppedCount() == 1);
    }
}
