package edu.illinois.cs.dt.tools.polluter;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.Statement;
import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.utility.MvnCommands;
import edu.illinois.cs.dt.tools.utility.deltadebug.DeltaDebugger;
import edu.illinois.cs.testrunner.mavenplugin.TestPluginPlugin;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

public class PolluterDeltaDebugger extends DeltaDebugger<Statement> {

    private final MavenProject project;
    private final InstrumentingSmartRunner runner;
    private final JavaMethod methodToModify;
    private final List<String> passingOrder;

    public PolluterDeltaDebugger(MavenProject project, InstrumentingSmartRunner runner,
                                     JavaMethod methodToModify, List<String> passingOrder) {
        this.project = project;
        this.runner = runner;
        this.methodToModify = methodToModify;
        this.passingOrder = passingOrder;
    }

    // Cleaner statements are valid if using them leads to order-dependent test to pass
    @Override
    public boolean checkValid(List<Statement> statements) {
        return checkValid(statements, true);
    }

    public boolean checkValid(List<Statement> statements, boolean suppressError) {
        // Converting to NodeList
        NodeList<Statement> polluterStmts = NodeList.nodeList();
        polluterStmts.addAll(statements);

        // Giant try-catch block to handle odd case if cannot write to Java file on disk
        try {
            // If want to prepend set to true, then prepend to victim
            this.methodToModify.prepend(polluterStmts);

            this.methodToModify.javaFile().writeAndReloadCompilationUnit();

            // Rebuild and see if tests run properly
            try {
                MvnCommands.runMvnInstall(this.project, suppressError);
            } catch (Exception ex) {
                TestPluginPlugin.debug("Error building the code, passed in cleaner code does not compile");
                // Reset the change
                this.methodToModify.removeFirstBlock();

                this.methodToModify.javaFile().writeAndReloadCompilationUnit();
                return false;
            }
            boolean failInPassingOrder = testOrderFails(this.passingOrder);

            // Reset the change
            this.methodToModify.removeFirstBlock();

            this.methodToModify.javaFile().writeAndReloadCompilationUnit();

            return failInPassingOrder;
        } catch (IOException ioe) {
            TestPluginPlugin.error("Problem with writing to Java file!");
            return false;
        }

        // TODO: Make sure our fix doesn't break any other tests
        //  This block of code could be useful when dealing with a case where we add the necessary
        //  setup to a victim test but our "fix" would actually now cause another test to fail.
        //  We will need some additional logic to deal with this case (i.e., a fix reveals another
        //  dependency) than just this block of code, but it may have its uses later.
        // Check if we pass in the whole test class
        // Should check before fix if class is passing
        //        boolean didClassPass = didTestsPass(victimJavaFile.getTestListAsString());

        //        if (didClassPass) {
        //            boolean didClassPassAfterFix = didTestsPass(victimJavaFile.getTestListAsString());
        //            if (!didClassPassAfterFix) {
        //                System.out.println("Fix was unsuccessful. Fix causes some other test in the class to fail.");
        //                return;
        //            }
        //        }
    }

    // Helper method for determining if a specific test order passes
    private boolean testOrderFails(final List<String> tests) {
        return new PassingTestDetector(this.runner).notFailingTests(tests).orElse(new HashSet<>()).isEmpty();
    }
}
