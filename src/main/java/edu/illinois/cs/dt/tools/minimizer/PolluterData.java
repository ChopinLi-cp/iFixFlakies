package edu.illinois.cs.dt.tools.minimizer;

import edu.illinois.cs.dt.tools.utility.OperationTime;

import java.util.ArrayList;
import java.util.List;

import edu.illinois.cs.dt.tools.minimizer.cleaner.CleanerData;

public class PolluterData {
    private final OperationTime time;
    private final int index;            // The index of when this polluter was found (0 is first)
    private final List<String> deps;

    public PolluterData(final OperationTime time, final int index, final List<String> deps) {
        this.time = time;
        this.index = index;
        this.deps = deps;
    }

    public OperationTime time() {
        return time;
    }

    public int index() {
        return index;
    }

    public List<String> deps() {
        return deps;
    }

    public List<String> withDeps(final String dependentTest) {
        final List<String> order = new ArrayList<>(deps);
        if (!order.contains(dependentTest)) {
            order.add(dependentTest);
        }
        return order;
    }
}
