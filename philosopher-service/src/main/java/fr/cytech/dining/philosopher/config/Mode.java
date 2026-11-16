package fr.cytech.dining.philosopher.config;

/** Variante du problème exécutée par ce philosophe (une par section du sujet). */
public enum Mode {
    /** Section 1 : aucune ressource partagée, le philosophe mange quand il veut. */
    SPOILED,
    /** Section 2 : superviseur naïf, une fourchette après l'autre. */
    SUPERVISED_NAIVE,
    /** Section 3.1 : les deux fourchettes demandées ensemble, sans verrou côté superviseur. */
    SUPERVISED_UNSAFE,
    /** Section 3.2 : les deux fourchettes demandées ensemble, section critique verrouillée. */
    SUPERVISED_LOCKED,
    /** Section 3.3 : protocole distribué par jetons, sans superviseur. */
    TOKEN,
    /** Section 3.4 : verrous distribués ZooKeeper (Apache Curator). */
    ZOOKEEPER
}
