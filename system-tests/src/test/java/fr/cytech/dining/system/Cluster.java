package fr.cytech.dining.system;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.monitor.MonitorServiceApplication;
import fr.cytech.dining.monitor.Snapshot;
import fr.cytech.dining.philosopher.PhilosopherServiceApplication;
import fr.cytech.dining.philosopher.core.PhilosopherLoop;
import fr.cytech.dining.philosopher.token.TokenNode;
import fr.cytech.dining.supervisor.SupervisorServiceApplication;

/**
 * Lance un vrai système dans la JVM du test : un moniteur, un superviseur et N philosophes, chacun
 * dans son propre contexte Spring Boot et sur son propre port. Ils ne communiquent que par HTTP,
 * exactement comme en production.
 */
final class Cluster implements AutoCloseable {

    private final int count;
    private final int monitorPort;
    private final int supervisorPort;
    private final int basePort;
    private final Map<String, String> philosopherOptions;
    private final List<String> commonArguments;
    private final RestClient http = RestClient.create();

    private ConfigurableApplicationContext monitor;
    private ConfigurableApplicationContext supervisor;
    private final ConfigurableApplicationContext[] philosophers;

    private Cluster(Builder builder) {
        this.count = builder.count;
        this.monitorPort = freePort();
        this.supervisorPort = freePort();
        this.basePort = consecutiveFreePorts(count);
        this.philosophers = new ConfigurableApplicationContext[count];
        this.philosopherOptions = new LinkedHashMap<>();
        philosopherOptions.put("mode", builder.mode);
        philosopherOptions.put("count", Integer.toString(count));
        philosopherOptions.put("base-port", Integer.toString(basePort));
        philosopherOptions.put("peer-url-template", "http://localhost:{port}");
        philosopherOptions.put("monitor-url", "http://localhost:" + monitorPort);
        philosopherOptions.put("supervisor-url", "http://localhost:" + supervisorPort);
        philosopherOptions.put("think-min-ms", "2");
        philosopherOptions.put("think-max-ms", "10");
        philosopherOptions.put("eat-min-ms", "5");
        philosopherOptions.put("eat-max-ms", "15");
        philosopherOptions.put("poll-ms", "5");
        philosopherOptions.put("retransmit-ms", "200");
        philosopherOptions.putAll(builder.philosopherOptions);
        this.commonArguments = List.copyOf(builder.commonArguments);

        startMonitor();
        List<String> supervisorArguments = new ArrayList<>(commonArguments);
        supervisorArguments.add("--supervisor.forks=" + count);
        builder.supervisorOptions.forEach((key, value) -> supervisorArguments.add("--supervisor." + key + "=" + value));
        supervisor = run(SupervisorServiceApplication.class, "supervisor-service", supervisorPort,
                supervisorArguments);
        for (int id = 0; id < count; id++) {
            startPhilosopher(id);
        }
    }

    static Builder with(int count, String mode) {
        return new Builder(count, mode);
    }

    static final class Builder {
        private final int count;
        private final String mode;
        private final Map<String, String> philosopherOptions = new LinkedHashMap<>();
        private final Map<String, String> supervisorOptions = new LinkedHashMap<>();
        private final List<String> commonArguments = new ArrayList<>();

        private Builder(int count, String mode) {
            this.count = count;
            this.mode = mode;
        }

        /** Règle une option {@code philosopher.*} de tous les philosophes. */
        Builder philosopher(String option, Object value) {
            philosopherOptions.put(option, value.toString());
            return this;
        }

        /** Règle une option {@code supervisor.*}. */
        Builder supervisor(String option, Object value) {
            supervisorOptions.put(option, value.toString());
            return this;
        }

        /** Ajoute un argument de ligne de commande à tous les services (ex. adresse de Kafka). */
        Builder argument(String argument) {
            commonArguments.add(argument);
            return this;
        }

        Cluster start() {
            return new Cluster(this);
        }
    }

    // --- Cycle de vie des services (pannes et redémarrages compris) ---

    void startMonitor() {
        monitor = run(MonitorServiceApplication.class, "monitor-service", monitorPort, commonArguments);
    }

    void startPhilosopher(int id) {
        List<String> arguments = new ArrayList<>(commonArguments);
        arguments.add("--philosopher.id=" + id);
        philosopherOptions.forEach((key, value) -> arguments.add("--philosopher." + key + "=" + value));
        philosophers[id] = run(PhilosopherServiceApplication.class, "philosopher-" + id, basePort + id, arguments);
    }

    /** Arrêt brutal du service d'un philosophe : il ne répond plus du tout. */
    void killPhilosopher(int id) {
        philosophers[id].close();
        philosophers[id] = null;
    }

    void killMonitor() {
        monitor.close();
        monitor = null;
    }

    private static ConfigurableApplicationContext run(Class<?> application, String name, int port,
                                                      List<String> extraArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                "--server.port=" + port,
                "--server.shutdown=immediate",
                "--spring.application.name=" + name,
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false",
                "--logging.level.root=WARN"));
        arguments.addAll(extraArguments);
        return new SpringApplicationBuilder(application).run(arguments.toArray(String[]::new));
    }

    // --- Observation par les API REST ---

    int count() {
        return count;
    }

    Snapshot snapshot() {
        return http.get().uri("http://localhost:" + monitorPort + "/api/snapshot").retrieve().body(Snapshot.class);
    }

    PhilosopherLoop.Status state(int id) {
        return http.get().uri(url(id) + "/state").retrieve().body(PhilosopherLoop.Status.class);
    }

    TokenNode.Snapshot tokenState(int id) {
        return http.get().uri(url(id) + "/token/state").retrieve().body(TokenNode.Snapshot.class);
    }

    void control(int id, String action) {
        http.post().uri(url(id) + "/control/" + action).retrieve().toBodilessEntity();
    }

    String supervisorForks() {
        return http.get().uri("http://localhost:" + supervisorPort + "/forks").retrieve().body(String.class);
    }

    long[] meals() {
        long[] meals = new long[count];
        for (int id = 0; id < count; id++) {
            meals[id] = philosophers[id] == null ? -1 : state(id).meals();
        }
        return meals;
    }

    long minMeals() {
        long min = Long.MAX_VALUE;
        for (long meal : meals()) {
            if (meal >= 0) {
                min = Math.min(min, meal);
            }
        }
        return min;
    }

    String url(int id) {
        return "http://localhost:" + (basePort + id);
    }

    @Override
    public void close() {
        for (ConfigurableApplicationContext philosopher : philosophers) {
            if (philosopher != null) {
                philosopher.close();
            }
        }
        if (supervisor != null) {
            supervisor.close();
        }
        if (monitor != null) {
            monitor.close();
        }
    }

    // --- Ports libres ---

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Les philosophes écoutent sur basePort + id : il faut N ports libres qui se suivent. */
    private static int consecutiveFreePorts(int count) {
        for (int attempt = 0; attempt < 200; attempt++) {
            int base = ThreadLocalRandom.current().nextInt(20_000, 45_000);
            List<ServerSocket> sockets = new ArrayList<>();
            try {
                for (int i = 0; i < count; i++) {
                    sockets.add(new ServerSocket(base + i));
                }
                return base;
            } catch (IOException e) {
                // un des ports est pris : on essaie ailleurs
            } finally {
                for (ServerSocket socket : sockets) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // rien à faire
                    }
                }
            }
        }
        throw new IllegalStateException("Aucune plage de " + count + " ports libres");
    }
}
