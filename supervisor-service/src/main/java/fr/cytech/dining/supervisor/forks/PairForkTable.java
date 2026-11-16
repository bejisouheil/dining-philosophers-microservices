package fr.cytech.dining.supervisor.forks;

import java.util.Map;

/** Superviseur auquel un philosophe demande ses deux fourchettes en une seule fois (section 3). */
public interface PairForkTable {

    /** Réserve les deux fourchettes si elles sont libres toutes les deux ; sinon ne réserve rien. */
    boolean acquirePair(int first, int second, int philosopher);

    /** Libère les deux fourchettes. */
    void releasePair(int first, int second, int philosopher);

    /** Fourchette → philosophe qui la détient. */
    Map<Integer, Integer> owners();

    void reset();
}
