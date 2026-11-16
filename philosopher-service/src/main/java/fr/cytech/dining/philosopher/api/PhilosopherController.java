package fr.cytech.dining.philosopher.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.cytech.dining.philosopher.core.PhilosopherLoop;

/** API de contrôle d'un philosophe : consulter son état, démarrer ou arrêter sa boucle. */
@RestController
public class PhilosopherController {

    private final PhilosopherLoop loop;

    public PhilosopherController(PhilosopherLoop loop) {
        this.loop = loop;
    }

    @GetMapping("/state")
    public PhilosopherLoop.Status state() {
        return loop.status();
    }

    @PostMapping("/control/start")
    public PhilosopherLoop.Status start() {
        loop.start();
        return loop.status();
    }

    @PostMapping("/control/stop")
    public PhilosopherLoop.Status stop() {
        loop.stop();
        return loop.status();
    }
}
