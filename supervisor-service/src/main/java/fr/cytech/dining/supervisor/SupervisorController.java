package fr.cytech.dining.supervisor;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.cytech.dining.supervisor.forks.LockedPairForkTable;
import fr.cytech.dining.supervisor.forks.NaiveForkTable;
import fr.cytech.dining.supervisor.forks.PairForkTable;
import fr.cytech.dining.supervisor.forks.UnsafePairForkTable;

/**
 * API du superviseur. Trois variantes cohabitent, une par étape du sujet :
 * {@code /naive} (section 2), {@code /unsafe} (3.1) et {@code /locked} (3.2).
 */
@RestController
public class SupervisorController {

    /** Réponse à une demande de fourchette(s). */
    public record Answer(boolean granted) {
    }

    private final NaiveForkTable naive;
    private final UnsafePairForkTable unsafe;
    private final LockedPairForkTable locked;

    public SupervisorController(NaiveForkTable naive, UnsafePairForkTable unsafe, LockedPairForkTable locked) {
        this.naive = naive;
        this.unsafe = unsafe;
        this.locked = locked;
    }

    // --- Section 2 : une fourchette à la fois ---

    @GetMapping("/naive/forks/{fork}/available")
    public Answer available(@PathVariable int fork) {
        return new Answer(naive.isAvailable(fork));
    }

    @PostMapping("/naive/forks/{fork}/acquire")
    public Answer acquire(@PathVariable int fork, @RequestParam int philosopher) {
        return new Answer(naive.acquire(fork, philosopher));
    }

    @PostMapping("/naive/forks/{fork}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable int fork, @RequestParam int philosopher) {
        naive.release(fork, philosopher);
    }

    // --- Sections 3.1 et 3.2 : les deux fourchettes ensemble ---

    @PostMapping("/{variant}/forks/pair/acquire")
    public Answer acquirePair(@PathVariable String variant, @RequestParam int first, @RequestParam int second,
                              @RequestParam int philosopher) {
        return new Answer(table(variant).acquirePair(first, second, philosopher));
    }

    @PostMapping("/{variant}/forks/pair/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void releasePair(@PathVariable String variant, @RequestParam int first, @RequestParam int second,
                            @RequestParam int philosopher) {
        table(variant).releasePair(first, second, philosopher);
    }

    // --- Observation ---

    @GetMapping("/forks")
    public Map<String, Map<Integer, Integer>> forks() {
        return Map.of("naive", naive.owners(), "unsafe", unsafe.owners(), "locked", locked.owners());
    }

    @PostMapping("/forks/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset() {
        naive.reset();
        unsafe.reset();
        locked.reset();
    }

    private PairForkTable table(String variant) {
        return switch (variant) {
            case "unsafe" -> unsafe;
            case "locked" -> locked;
            default -> throw new IllegalArgumentException("Variante inconnue : " + variant);
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
