package edu.illinois.cs.dt.tools.polluter;

import edu.illinois.cs.dt.tools.utility.OperationTime;

public class PatchResult {

    private OperationTime time;
    private PolluteStatus status;
    private String dependentTest;
    private String polluter;
    private int iterations;
    private String patchLocation;

    public PatchResult(final OperationTime time, final PolluteStatus status,
                       final String dependentTest, final String polluter,
                       final int iterations, final String patchLocation) {
        this.time = time;
        this.status = status;
        this.dependentTest = dependentTest;
        this.polluter = polluter;
        this.iterations = iterations;
        this.patchLocation = patchLocation;
    }

    public OperationTime time() {
        return this.time;
    }

    public PolluteStatus status() {
        return this.status;
    }

    public String dependentTest() {
        return this.dependentTest;
    }

    public String polluter() {
        return this.polluter == null ? "N/A" : this.polluter;
    }

    public int iterations() {
        return this.iterations;
    }

    public String patchLocation() {
        return this.patchLocation;
    }
}
