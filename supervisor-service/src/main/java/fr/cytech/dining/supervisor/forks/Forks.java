package fr.cytech.dining.supervisor.forks;

/** Validation des numéros de fourchette. */
final class Forks {

    private Forks() {
    }

    static void check(int first, int second, int forkCount) {
        if (first < 0 || first >= forkCount || second < 0 || second >= forkCount) {
            throw new IllegalArgumentException("Fourchette inconnue : " + first + " ou " + second);
        }
        if (first == second) {
            throw new IllegalArgumentException("Il faut deux fourchettes différentes");
        }
    }
}
