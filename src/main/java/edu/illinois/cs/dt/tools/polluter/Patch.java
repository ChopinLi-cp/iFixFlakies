package edu.illinois.cs.dt.tools.polluter;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class Patch {
    private JavaMethod methodToPatch;   // Method where patch is applied (prepended or appended)
    private BlockStmt patchedBlock;     // The block to patch into the method

    // Information that is mainly used in case applying patch cannot inline, needs combination
    private List<Path> testFiles;
    private String classpath;
    private boolean inlineSuccessful;

    private JavaMethod victimMethod;    // Keep around just for bookkeeping

    public Patch(JavaMethod methodToPatch, BlockStmt patchedBlock,
                 JavaMethod victimMethod,
                 List<Path> testFiles, String classpath,
                 boolean inlineSuccessful) {
        this.methodToPatch = methodToPatch;
        this.patchedBlock = patchedBlock;
        this.victimMethod = victimMethod;
        this.testFiles = testFiles;
        this.classpath = classpath;
        this.inlineSuccessful = inlineSuccessful;
    }

    public JavaMethod methodToPatch() {
        return this.methodToPatch;
    }

    public BlockStmt patchedBlock() {
        return this.patchedBlock;
    }

    public JavaMethod victimMethod() {
        return this.victimMethod;
    }

    public List<Path> testFiles() {
        return this.testFiles;
    }

    public String classpath() {
        return this.classpath;
    }

    public boolean inlineSuccessful() {
        return this.inlineSuccessful;
    }

    public void applyPatch() throws Exception {
        // If able to inline, then only need to put patch into the method to patch
        if (this.inlineSuccessful) {
            this.methodToPatch.prepend(this.patchedBlock.getStatements());
            this.methodToPatch.javaFile().writeAndReloadCompilationUnit();
        } else {
            // Otherwise, need to add the statements into fresh cleanerHelper in cleaner method's file, and call to it
            // The modification is to modify the cleaner class to add a helper, then have the other method call the helper
            ExpressionStmt helperCallStmt = getHelperCallStmt(victimMethod);
            this.methodToPatch.prepend(NodeList.nodeList(helperCallStmt));
            this.methodToPatch.javaFile().writeAndReloadCompilationUnit();

            String helperName = this.victimMethod.getClassName() + ".victimMethod";
            this.victimMethod = JavaMethod.find(this.victimMethod.methodName(), this.testFiles, this.classpath).get();
            this.victimMethod.javaFile().addMethod(helperName);
            this.victimMethod.javaFile().writeAndReloadCompilationUnit();
            JavaMethod helperMethod = JavaMethod.find(helperName, this.testFiles, this.classpath).get();
            helperMethod.javaFile().writeAndReloadCompilationUnit();

            this.methodToPatch = JavaMethod.find(this.methodToPatch.methodName(), this.testFiles, this.classpath).get();
        }
    }

    public void restore() throws Exception {
        Path path = PolluterPathManager.backupPath(this.methodToPatch.javaFile().path());
        Files.copy(path, this.methodToPatch.javaFile().path(), StandardCopyOption.REPLACE_EXISTING);
        if (!inlineSuccessful) {
            path = PolluterPathManager.backupPath(this.victimMethod.javaFile().path());
            Files.copy(path, this.victimMethod.javaFile().path(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ExpressionStmt getHelperCallStmt(JavaMethod cleanerMethod) {
        Expression objectCreation = new ObjectCreationExpr(null, new ClassOrInterfaceType(null, cleanerMethod.getClassName()), NodeList.nodeList());
        Expression helperCall = new MethodCallExpr(objectCreation, "cleanerHelper");
        ExpressionStmt helperCallStmt = new ExpressionStmt(helperCall);
        return helperCallStmt;
    }
}
