package edu.illinois.cs.dt.tools.polluter;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import org.apache.maven.project.MavenProject;

import java.util.List;

public class PolluterBlockDeltaDebugger extends PolluterDeltaDebugger {

    private final BlockStmt blockStmt;
    private final NodeList<Statement> stmtsToRun;

    public PolluterBlockDeltaDebugger(MavenProject project, InstrumentingSmartRunner runner,
                                          JavaMethod methodToModify, List<String> failingOrder,
                                      BlockStmt blockStmt, NodeList<Statement> stmtsToRun) {
        super(project, runner, methodToModify, failingOrder);
        this.blockStmt = blockStmt;
        this.stmtsToRun = stmtsToRun;
    }

    // Delta debugging statements within the relevant block in relation to rest of statements that contain the block, along with failing test order
    @Override
    public boolean checkValid(List<Statement> statements) {
        // Save original statements from block
        NodeList<Statement> original = this.blockStmt.getStatements();

        // Set the contents of the block to current statements
        NodeList<Statement> stmts = NodeList.nodeList();
        stmts.addAll(statements);
        this.blockStmt.setStatements(stmts);

        // Run the full stmtsToRun, but the relevant block statement has been modified
        boolean valid = super.checkValid(this.stmtsToRun);

        // Reset the block
        this.blockStmt.setStatements(original);

        return valid;
    }
}
