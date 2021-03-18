package edu.illinois.cs.dt.tools.polluter;

import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.testrunner.data.results.Result;
import edu.illinois.cs.testrunner.data.results.TestRunResult;
import scala.util.Try;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class PassingTestDetector {
    private final InstrumentingSmartRunner runner;

    public PassingTestDetector(final InstrumentingSmartRunner runner) {
        this.runner = runner;
    }

    public Optional<Set<String>> notFailingTests(final List<String> tests) {
        final Set<String> notFailingTests = new HashSet<>();

        if (tests.isEmpty()) {
            return Optional.of(notFailingTests);
        }

        final Try<TestRunResult> testRunResultTry = runner.runList(tests);

        if (testRunResultTry.isSuccess()) {
            testRunResultTry.get().results().forEach((testName, res) -> {
                if (res.result().equals(Result.PASS)) {
                    notFailingTests.add(testName);
                }
            });

            return Optional.of(notFailingTests);
        } else {
            return Optional.empty();
        }
    }
}
