package fr.cytech.dining.philosopher.graph;

import fr.cytech.dining.philosopher.graph.Tasks.DegreeResult;
import fr.cytech.dining.philosopher.graph.Tasks.DegreeTask;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandResult;
import fr.cytech.dining.philosopher.graph.Tasks.ExpandTask;
import fr.cytech.dining.philosopher.graph.Tasks.LoadTask;

/** Appels du Master vers un Worker désigné par son identifiant de philosophe. */
public interface WorkerClient {

    DegreeResult degrees(int worker, DegreeTask task);

    void load(int worker, LoadTask task);

    /** @throws Tasks.PartitionNotLoadedException si le Worker n'a plus la partition */
    ExpandResult expand(int worker, ExpandTask task);

    void forget(int worker, String jobId);
}
