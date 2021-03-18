package edu.illinois.cs.dt.tools.polluter;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import edu.illinois.cs.dt.tools.detection.DetectorPathManager;
import edu.illinois.cs.dt.tools.detection.DetectorPlugin;
import edu.illinois.cs.dt.tools.minimizer.FlakyClass;
import edu.illinois.cs.dt.tools.minimizer.MinimizeTestsResult;
import edu.illinois.cs.dt.tools.minimizer.MinimizerPlugin;
import edu.illinois.cs.dt.tools.minimizer.PolluterData;
import edu.illinois.cs.dt.tools.minimizer.cleaner.CleanerGroup;
import edu.illinois.cs.dt.tools.runner.InstrumentingSmartRunner;
import edu.illinois.cs.dt.tools.utility.ErrorLogger;
import edu.illinois.cs.dt.tools.utility.MvnCommands;
import edu.illinois.cs.dt.tools.utility.OperationTime;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.results.Result;
import edu.illinois.cs.testrunner.mavenplugin.TestPlugin;
import edu.illinois.cs.testrunner.mavenplugin.TestPluginPlugin;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.project.MavenProject;
import scala.Option;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PolluterPlugin extends TestPlugin {
    public static final String PATCH_LINE_SEP = "==========================";

    private MavenProject project;
    private InstrumentingSmartRunner runner;

    private List<Patch> patches;

    // Some fields to help with computing time to first cleaner and outputing in log
    private long startTime;
    private boolean foundFirst;

    // Don't delete. Need a default constructor for TestPlugin
    public PolluterPlugin() {
    }

    private boolean testOrderFails(final List<String> tests) {
        return new PassingTestDetector(runner).notFailingTests(tests).orElse(new HashSet<>()).isEmpty();
    }

    private String classpath() throws DependencyResolutionRequiredException {
        final List<String> elements = new ArrayList<>(project.getCompileClasspathElements());
        elements.addAll(project.getRuntimeClasspathElements());
        elements.addAll(project.getTestClasspathElements());

        return String.join(File.pathSeparator, elements);
    }

    private URLClassLoader projectClassLoader() throws DependencyResolutionRequiredException {
        // Get the project classpath, it will be useful for many things
        List<URL> urlList = new ArrayList();
        for (String cp : classpath().split(":")) {
            try {
                urlList.add(new File(cp).toURL());
            } catch (MalformedURLException mue) {
                TestPluginPlugin.error("Classpath element " + cp + " is malformed!");
            }
        }
        URL[] urls = urlList.toArray(new URL[urlList.size()]);
        return URLClassLoader.newInstance(urls);
    }

    @Override
    public void execute(final MavenProject project) {
        this.project = project;

        final Option<Runner> runnerOption = RunnerFactory.from(project);
        final ErrorLogger logger = new ErrorLogger(project);

        this.patches = new ArrayList<>();

        System.out.println("DIAGNOSER_MODULE_COORDINATES: " + logger.coordinates());

        logger.runAndLogError(() -> {
            if (!Files.exists(DetectorPathManager.cachePath())) {
                Files.createDirectories(DetectorPathManager.cachePath());
            }
            if (runnerOption.isDefined()) {
                this.runner = InstrumentingSmartRunner.fromRunner(runnerOption.get());

                if (!Files.exists(DetectorPathManager.originalOrderPath()) && MinimizerPlugin.ORIGINAL_ORDER == null) {
                    Files.write(DetectorPathManager.originalOrderPath(), DetectorPlugin.getOriginalOrder(project));
                }

                startTime = System.currentTimeMillis();

                // Iterate through each minimized, collecing such that unique for dependent test, combine polluters
                Map<String, MinimizeTestsResult> minimizedResults = new HashMap<>();
                for (MinimizeTestsResult minimized : detect().collect(Collectors.toList())) {
                    String dependentTest = minimized.dependentTest();
                    if (!minimizedResults.containsKey(dependentTest)) {
                        minimizedResults.put(dependentTest, minimized);
                    }
                    // Iterate through all the polluters of the current minimized, add in new ones into the existing one
                    for (PolluterData pd : minimized.polluters()) {
                        if (!minimizedResults.get(dependentTest).polluters().contains(pd)) {
                            minimizedResults.get(dependentTest).polluters().add(pd);
                        }
                    }
                }
                for (String dependentTest : minimizedResults.keySet()) {
                    MinimizeTestsResult minimized = minimizedResults.get(dependentTest);
                    PolluterResult polluterResult = OperationTime.runOperation(() -> {
                        return setupAndApplyPollute(minimized);
                    }, (patchResults, time) -> {
                        // Determine overall status by looking through result of each patch result
                        PolluteStatus overallStatus = PolluteStatus.NOD;    // Start with "lowest" enum, gets overriden by better fixes
                        for (PatchResult res : patchResults) {
                            if (res.status().ordinal() > overallStatus.ordinal()) {
                                overallStatus = res.status();
                            }
                        }
                        return new PolluterResult(time, overallStatus, minimized.dependentTest(), patchResults);
                    });
                    polluterResult.save();
                }
            } else {
                final String errorMsg = "Module is not using a supported test framework (probably not JUnit).";
                TestPluginPlugin.info(errorMsg);
                logger.writeError(errorMsg);
            }

            return null;
        });
    }

    private Stream<MinimizeTestsResult> detect() throws Exception {
        if (!Files.exists(DetectorPathManager.detectionFile())) {
            if (Configuration.config().getProperty("diagnosis.run_detection", true)) {
                new DetectorPlugin(DetectorPathManager.detectionResults(), runner).execute(project);
            } else if (MinimizerPlugin.FLAKY_LIST == null) {
                throw new NoSuchFileException("File " + DetectorPathManager.detectionFile() + " does not exist and diagnosis.run_detection is set to false");
            }
        }

        return new MinimizerPlugin(runner).runDependentTestFile(DetectorPathManager.detectionFile(), project);
    }

    private boolean sameTestClass(String test1, String test2) {
        return test1.substring(0, test1.lastIndexOf('.')).equals(test2.substring(0, test2.lastIndexOf('.')));
    }

    private List<PatchResult> setupAndApplyPollute(final MinimizeTestsResult minimized) throws Exception {
        startTime = System.currentTimeMillis();

        List<PatchResult> patchResults = new ArrayList<>();

        // Check that the minimized is not some NOD, in which case we do not proceed
        if (minimized.flakyClass() == FlakyClass.NOD) {
            TestPluginPlugin.info("Will not patch polluter for discovered NOD test " + minimized.dependentTest());
            patchResults.add(new PatchResult(OperationTime.instantaneous(), PolluteStatus.NOD, minimized.dependentTest(), "N/A", 0, null));
            return patchResults;
        }

        // Get all test source files
        final List<Path> testFiles = testSources();

        // All minimized orders passed in should have some polluters before (or setters in the case of the order passing)
        if (minimized.polluters().isEmpty()) {
            TestPluginPlugin.error("No polluters for: " + minimized.dependentTest());
            patchResults.add(new PatchResult(OperationTime.instantaneous(), PolluteStatus.NO_DEPS, minimized.dependentTest(), "N/A", 0, null));
            return patchResults;
        }

        TestPluginPlugin.info("Beginning to fix dependent test " + minimized.dependentTest());

        List<PolluterData> polluterDataOrder = new ArrayList<PolluterData>();

        // If in a passing order and there are multiple potential setters, then prioritize the one in the same test class as dependent test
        if (minimized.expected().equals(Result.PASS)) {
            Set<PolluterData> pdWithSameTestClass = new HashSet<>();
            Set<PolluterData> pdWithDiffTestClass = new HashSet<>();
            for (PolluterData pd : minimized.polluters()) {
                // Only care about case of one polluter
                if (pd.deps().size() == 1) {
                    String setter = pd.deps().get(0);
                    // Want the one in the same test class
                    if (sameTestClass(setter, minimized.dependentTest())) {
                        pdWithSameTestClass.add(pd);
                    } else {
                        pdWithDiffTestClass.add(pd);
                    }
                }
            }
            // Add first in same test class ones, then the remaining ones
            polluterDataOrder.addAll(pdWithSameTestClass);
            polluterDataOrder.addAll(pdWithDiffTestClass);
        } else {
            // If case of failing order with polluters, best bet is one that has a cleaner, and in same test class as victim
            Set<PolluterData> pdNoCleaner = new HashSet<>();
            Set<PolluterData> pdWithCleaner = new HashSet<>();
            Set<PolluterData> pdWithSingleCleaner = new HashSet<>();
            Set<PolluterData> pdWithSingleCleanerSameTestClassVictim = new HashSet<>();
            Set<PolluterData> pdWithSingleCleanerSameTestClassPolluter = new HashSet<>();
            for (PolluterData pd : minimized.polluters()) {
                // Consider if has a cleaner
                if (!pd.cleanerData().cleaners().isEmpty()) {
                    pdWithCleaner.add(pd);
                    String polluter = pd.deps().get(pd.deps().size() - 1);  // If we're going to modify polluter, do it with the last one
                    // Would be best to have a cleaner group that is only one test
                    for (CleanerGroup cleanerGroup : pd.cleanerData().cleaners()) {
                        if (cleanerGroup.cleanerTests().size() == 1) {
                            pdWithSingleCleaner.add(pd);
                            // Even more ideal, if the cleaner is in the same test class as victim
                            String cleaner = cleanerGroup.cleanerTests().get(0);
                            if (sameTestClass(cleaner, minimized.dependentTest())) {
                                pdWithSingleCleanerSameTestClassVictim.add(pd);
                            }
                            // Also valid is if in the same test class as the polluter
                            if (sameTestClass(cleaner, polluter)) {
                                pdWithSingleCleanerSameTestClassPolluter.add(pd);
                            }
                        }
                    }
                } else {
                    pdNoCleaner.add(pd);
                }
            }
            // Remove from each level duplicates
            pdWithCleaner.removeAll(pdWithSingleCleaner);
            pdWithSingleCleaner.removeAll(pdWithSingleCleanerSameTestClassVictim);
            pdWithSingleCleaner.removeAll(pdWithSingleCleanerSameTestClassPolluter);
            pdWithSingleCleanerSameTestClassVictim.removeAll(pdWithSingleCleanerSameTestClassPolluter);
            // Prioritize based on those levels
            polluterDataOrder.addAll(pdWithSingleCleanerSameTestClassPolluter);
            polluterDataOrder.addAll(pdWithSingleCleanerSameTestClassVictim);
            polluterDataOrder.addAll(pdWithSingleCleaner);
            polluterDataOrder.addAll(pdWithCleaner);
            polluterDataOrder.addAll(pdNoCleaner);
        }

        for (PolluterData polluterData : polluterDataOrder) {
            // Apply fix using specific passed in polluter data
            patchResults.addAll(setupAndApplyPollute(minimized, polluterData, testFiles));
        }
        return patchResults;
    }

    private List<PatchResult> setupAndApplyPollute(final MinimizeTestsResult minimized,
                                               final PolluterData polluterData,
                                               final List<Path> testFiles) throws Exception {
        List<PatchResult> patchResults = new ArrayList<>();

        String polluterTestName;
        Optional<JavaMethod> polluterMethodOpt;
        List<String> passingOrder;

        List<String> cleanerTestNames = new ArrayList<>();  // Can potentially work with many cleaners, try them all

        String victimTestName = minimized.dependentTest();
        Optional<JavaMethod> victimMethodOpt = JavaMethod.find(victimTestName, testFiles, classpath());
        if (!victimMethodOpt.isPresent()) {
            TestPluginPlugin.error("Could not find victim method " + victimTestName);
            TestPluginPlugin.error("Tried looking in: " + testFiles);
            patchResults.add(new PatchResult(OperationTime.instantaneous(), PolluteStatus.MISSING_METHOD, victimTestName, "N/A", 0, null));
            return patchResults;
        }

        // If dealing with a case of result with failure, then get standard cleaner logic from it
        if (!minimized.expected().equals(Result.PASS)) {
            // Failing order has both the dependent test and the dependencies
            passingOrder = Collections.singletonList(minimized.dependentTest());

            polluterTestName = polluterData.deps().get(polluterData.deps().size() - 1); // If more than one polluter, want to potentially modify last one
            polluterMethodOpt = JavaMethod.find(polluterTestName, testFiles, classpath());

        } else {
            // "Cleaner" when result is passing is the "polluting" test(s)
            // TODO: Handle group of setters with more than one test
            if (polluterData.deps().size() > 1) {
                TestPluginPlugin.error("There is more than one setter test (currently unsupported)");
                patchResults.add(new PatchResult(OperationTime.instantaneous(), PolluteStatus.UNSUPPORTED, victimTestName, "N/A", 0, null));
                return patchResults;
            }
            polluterTestName = null;    // No polluter if minimized order is passing
            polluterMethodOpt = Optional.ofNullable(null);

            cleanerTestNames.add(polluterData.deps().get(0));   // Assume only one, get first...

            // Failing order should be just the dependent test by itself (as is the full failing order (for now))
            passingOrder = null;
        }

        if (polluterTestName != null && !polluterMethodOpt.isPresent()) {
            TestPluginPlugin.error("Could not find polluter method " + polluterTestName);
            TestPluginPlugin.error("Tried looking in: " + testFiles);
            patchResults.add(new PatchResult(OperationTime.instantaneous(), PolluteStatus.MISSING_METHOD, victimTestName, polluterTestName, 0, null));
            return patchResults;
        }

        // Check if we pass in isolation before fix
        TestPluginPlugin.info("Running victim test itself before adding code from polluter.");
        if (testOrderFails(passingOrder)) {
            TestPluginPlugin.error("Passing order doesn't pass.");
            Path patch = writePatch(victimMethodOpt.get(), 0, null, 0, null, polluterMethodOpt.orElse(null), 0, "NOT PASSING ORDER");
            patchResults.add(new PatchResult(OperationTime.instantaneous(), PolluteStatus.NOT_FAILING, victimTestName, polluterTestName, 0, patch.toString()));
            return patchResults;
        }

        // Reload methods
        victimMethodOpt = JavaMethod.find(victimTestName, testFiles, classpath());
        if (polluterMethodOpt.isPresent()) {
            polluterMethodOpt = JavaMethod.find(polluterTestName, testFiles, classpath());
        }
        TestPluginPlugin.info("Applying code from " + polluterMethodOpt.get().methodName() + " to make " + victimMethodOpt.get().methodName() + " fail.");
        PatchResult patchResult = applyPollute(passingOrder, polluterMethodOpt.orElse(null), victimMethodOpt.get());
        patchResults.add(patchResult);
        // A successful patch means we do not need to try all the remaining cleaners for this ordering
        if (patchResult.status().ordinal() > PolluteStatus.FIX_INVALID.ordinal()) {
            //return patchResults;
            double elapsedSeconds = System.currentTimeMillis() / 1000.0 - startTime / 1000.0;
            TestPluginPlugin.info("FIRST PATCH: Found first polluter patch for dependent test " + victimMethodOpt.get().methodName() + " in " + elapsedSeconds + " seconds.");
        }
        return patchResults;
    }

    private List<Path> testSources() throws IOException {
        final List<Path> testFiles = new ArrayList<>();
        try (final Stream<Path> paths = Files.walk(Paths.get(project.getBuild().getTestSourceDirectory()))) {
            paths.filter(Files::isRegularFile)
                    .forEach(testFiles::add);
        }
        return testFiles;
    }

    private void backup(final JavaFile javaFile) throws IOException {
        final Path path = PolluterPathManager.backupPath(javaFile.path());
        Files.copy(javaFile.path(), path, StandardCopyOption.REPLACE_EXISTING);
    }

    private void restore(final JavaFile javaFile) throws IOException {
        final Path path = PolluterPathManager.backupPath(javaFile.path());
        Files.copy(path, javaFile.path(), StandardCopyOption.REPLACE_EXISTING);
    }

    private NodeList<Statement> getCodeFromAnnotatedMethod(final String testClassName, final JavaFile javaFile, final String annotation) throws Exception {
        NodeList<Statement> stmts = NodeList.nodeList();

        // Determine super classes, to be used for later looking up helper methods
        Class testClass = projectClassLoader().loadClass(testClassName);
        List<Class> superClasses = new ArrayList<>();
        Class currClass = testClass;
        while (currClass != null) {
            superClasses.add(currClass);
            currClass = currClass.getSuperclass();
        }

        // If the test class is a subclass of JUnit 3's TestCase, then there is no annotation, just handle setUp and tearDown
        boolean isJUnit3 = false;
        for (Class clazz : superClasses) {
            if (clazz.toString().equals("class junit.framework.TestCase")) {
                isJUnit3 = true;
                break;
            }
        }
        // In JUnit 3 mode, try to get statements in setUp/tearDown only if in local class; otherwise put in a call to method if in superclass
        if (isJUnit3) {
            // Check if the test class had defined a setUp/tearDown
            String methName = "";
            for (Method meth : testClass.getDeclaredMethods()) {
                if (annotation.equals("@org.junit.Before")) {
                    if (meth.getName().equals("setUp")) {
                        methName = "setUp";
                        break;
                    }
                } else if (annotation.equals("@org.junit.After")) {
                    if (meth.getName().equals("tearDown")) {
                        methName = "tearDown";
                        break;
                    }
                }
            }
            if (!methName.equals("")) {
                MethodDeclaration method = javaFile.findMethodDeclaration(testClassName + "." + methName);
                Optional<BlockStmt> body = method.getBody();
                if (body.isPresent()) {
                    if (method.getDeclarationAsString(false, true, false).contains("throws ")) {
                        // Wrap the body inside a big try statement to suppress any exceptions
                        ClassOrInterfaceType exceptionType = new ClassOrInterfaceType().setName(new SimpleName("Throwable"));
                        CatchClause catchClause = new CatchClause(new Parameter(exceptionType, "ex"), new BlockStmt());
                        stmts.add(new TryStmt(new BlockStmt(body.get().getStatements()), NodeList.nodeList(catchClause), new BlockStmt()));
                    } else {
                        stmts.addAll(body.get().getStatements());
                    }
                }
                return stmts;   // Finished getting all the statements
            }

            // If reached here, means should go over super classes to see if one of these methods is even defined
            for (Class clazz : superClasses) {
                for (Method meth : clazz.getDeclaredMethods()) {
                    if (annotation.equals("@org.junit.Before")) {
                        if (meth.getName().equals("setUp")) {
                            stmts.add(new ExpressionStmt(new MethodCallExpr(null, "setUp")));
                            return stmts;
                        }
                    } else if (annotation.equals("@org.junit.After")) {
                        if (meth.getName().equals("tearDown")) {
                            stmts.add(new ExpressionStmt(new MethodCallExpr(null, "tearDown")));
                            return stmts;
                        }
                    }
                }
            }
        }

        // Iterate through super classes going "upwards", starting with this test class, to get annotated methods
        // If already seen a method of the same name, then it is overriden, so do not include
        List<String> annotatedMethods = new ArrayList<>();
        List<String> annotatedMethodsLocal = new ArrayList<>();
        for (Class clazz : superClasses) {
            for (Method meth : clazz.getDeclaredMethods()) {
                for (Annotation anno : meth.getDeclaredAnnotations()) {
                    if (anno.toString().equals(annotation + "()")) {
                        if (!annotatedMethods.contains(meth.getName())) {
                            annotatedMethods.add(meth.getName());
                        }
                        if (clazz.equals(testClass)) {
                            annotatedMethodsLocal.add(meth.getName());
                        }
                    }
                }
            }
        }
        annotatedMethods.removeAll(annotatedMethodsLocal);

        // For Before, go last super class first, then inline the statements in test class
        if (annotation.equals("@org.junit.Before")) {
            for (int i = annotatedMethods.size() - 1; i >= 0; i--) {
                stmts.add(new ExpressionStmt(new MethodCallExpr(null, annotatedMethods.get(i))));
            }
            for (String methName : annotatedMethodsLocal) {
                MethodDeclaration method = javaFile.findMethodDeclaration(testClassName + "." + methName);
                Optional<BlockStmt> body = method.getBody();
                if (body.isPresent()) {
                    if (method.getDeclarationAsString(false, true, false).contains("throws ")) {
                        // Wrap the body inside a big try statement to suppress any exceptions
                        ClassOrInterfaceType exceptionType = new ClassOrInterfaceType().setName(new SimpleName("Throwable"));
                        CatchClause catchClause = new CatchClause(new Parameter(exceptionType, "ex"), new BlockStmt());
                        stmts.add(new TryStmt(new BlockStmt(body.get().getStatements()), NodeList.nodeList(catchClause), new BlockStmt()));
                    } else {
                        stmts.addAll(body.get().getStatements());
                    }
                }
            }
        } else {
            // For After, inline the statements in test class, then go first super class first
            for (String methName : annotatedMethodsLocal) {
                MethodDeclaration method = javaFile.findMethodDeclaration(testClassName + "." + methName);
                Optional<BlockStmt> body = method.getBody();
                if (body.isPresent()) {
                    if (method.getDeclarationAsString(false, true, false).contains("throws ")) {
                        // Wrap the body inside a big try statement to suppress any exceptions
                        ClassOrInterfaceType exceptionType = new ClassOrInterfaceType().setName(new SimpleName("Throwable"));
                        CatchClause catchClause = new CatchClause(new Parameter(exceptionType, "ex"), new BlockStmt());
                        stmts.add(new TryStmt(new BlockStmt(body.get().getStatements()), NodeList.nodeList(catchClause), new BlockStmt()));
                    } else {
                        stmts.addAll(body.get().getStatements());
                    }
                }
            }
            for (int i = 0; i < annotatedMethods.size() ; i++) {
                stmts.add(new ExpressionStmt(new MethodCallExpr(null, annotatedMethods.get(i))));
            }
        }

        return stmts;
    }

    private ExpressionStmt getHelperCallStmt(JavaMethod polluterMethod, boolean newTestClass) {
        Expression objectCreation = null;
        if (newTestClass) {
            objectCreation = new ObjectCreationExpr(null, new ClassOrInterfaceType(null, polluterMethod.getClassName()), NodeList.nodeList());
        }
        Expression helperCall = new MethodCallExpr(objectCreation, "polluterHelper");
        ExpressionStmt helperCallStmt = new ExpressionStmt(helperCall);
        return helperCallStmt;
    }

    private JavaMethod addHelperMethod(JavaMethod polluterMethod, JavaMethod methodToModify, boolean newTestClass) throws Exception {
        // The modification is to modify the cleaner class to add a helper, then have the other method call the helper
        ExpressionStmt helperCallStmt = getHelperCallStmt(polluterMethod, newTestClass);
        methodToModify.prepend(NodeList.nodeList(helperCallStmt));

        methodToModify.javaFile().writeAndReloadCompilationUnit();

        String helperName = polluterMethod.getClassName() + ".polluterHelper";
        polluterMethod = JavaMethod.find(polluterMethod.methodName(), testSources(), classpath()).get();    // Reload, just in case
        polluterMethod.javaFile().addMethod(helperName, "org.junit.Test");
        polluterMethod.javaFile().writeAndReloadCompilationUnit();
        JavaMethod helperMethod = JavaMethod.find(helperName, testSources(), classpath()).get();
        helperMethod.javaFile().writeAndReloadCompilationUnit();

        methodToModify = JavaMethod.find(methodToModify.methodName(), testSources(), classpath()).get();   // Reload, just in case

        return helperMethod;
    }

    private PolluteStatus checkPolluterRemoval(List<String> passingOrder, JavaMethod polluterMethod, NodeList<Statement> polluterStmts) throws Exception {
        try {
            // Try to modify the polluterMethod to remove the polluter statements
            NodeList<Statement> allStatements = polluterMethod.body().getStatements();
            NodeList<Statement> strippedStatements = NodeList.nodeList();
            NodeList<Statement> otherPolluterStmts = NodeList.nodeList(polluterStmts);
            int j = 0;
            for (int i = 0; i < allStatements.size(); i++) {
                // Do not include the statement if we see it from the cleaner statements
                if (otherPolluterStmts.contains(allStatements.get(i))) {
                    otherPolluterStmts.remove(allStatements.get(i));
                } else {
                    strippedStatements.add(allStatements.get(i));
                }
            }

            // If the stripped statements is still the same as all statements, then the cleaner statements must all be in @Before/After
            if (strippedStatements.equals(allStatements)) {
                TestPluginPlugin.info("All polluter statements must be in setup/teardown.");
                return PolluteStatus.FIX_INLINE_SETUPTEARDOWN;  // Indicating statements were in setup/teardown
            }

            // Set the cleaner method body to be the stripped version
            restore(polluterMethod.javaFile());
            polluterMethod = JavaMethod.find(polluterMethod.methodName(), testSources(), classpath()).get();    // Reload, just in case
            polluterMethod.method().setBody(new BlockStmt(strippedStatements));
            polluterMethod.javaFile().writeAndReloadCompilationUnit();
            try {
                MvnCommands.runMvnInstall(this.project, false);
            } catch (Exception ex) {
                TestPluginPlugin.debug("Error building the code after stripping statements, does not compile");
                return PolluteStatus.NOD;   // Indicating did not work (TODO: Make it more clear)
            }
            // First try running in isolation
            List<String> isolationOrder = Collections.singletonList(polluterMethod.methodName());
            if (testOrderFails(isolationOrder)) {
                TestPluginPlugin.info("Running polluter by itself after removing statements does not pass.");
                return PolluteStatus.NOD;   // Indicating did not work (TODO: Make it more clear)
            }
            // Then try running with the failing order, replacing the last test with this one
            List<String> newPassingOrder = new ArrayList<>(passingOrder);
            newPassingOrder.remove(newPassingOrder.size() - 1);
            newPassingOrder.add(polluterMethod.methodName());
            if (testOrderFails(newPassingOrder)) {
                TestPluginPlugin.info("Running cleaner in failing order after polluter still passes."); //****
                return PolluteStatus.NOD;   // Indicating did not work (TODO: Make it more clear)
            }

            //// Restore the state
            //restore(cleanerMethod.javaFile());
            //cleanerMethod = JavaMethod.find(cleanerMethod.methodName(), testSources(), classpath()).get();    // Reload, just in case
            return PolluteStatus.FIX_INLINE_CANREMOVE;  // Indicating statements can be removed
        } finally {
            // Restore the state
            restore(polluterMethod.javaFile());
            polluterMethod = JavaMethod.find(polluterMethod.methodName(), testSources(), classpath()).get();    // Reload, just in case
            MvnCommands.runMvnInstall(this.project, true);
        }
    }

    // Make the polluter statements based on the polluter method and what method needs to be modified in the process
    private NodeList<Statement> makePolluterStatements(JavaMethod polluterMethod, JavaMethod methodToModify) throws Exception {
        // If the polluter method is annotated such that it is expected to fail, then wrap in try catch
        boolean expected = false;
        for (AnnotationExpr annotExpr : polluterMethod.method().getAnnotations()) {
            if (annotExpr instanceof NormalAnnotationExpr) {
                NormalAnnotationExpr normalAnnotExpr = (NormalAnnotationExpr) annotExpr;
                for (MemberValuePair memberValuePair : normalAnnotExpr.getPairs()) {
                    if (memberValuePair.getName().toString().equals("expected")) {
                        expected = true;
                        break;
                    }
                }
            }
        }

        boolean isSameTestClass = sameTestClass(polluterMethod.methodName(), methodToModify.methodName());

        final NodeList<Statement> polluterStmts = NodeList.nodeList();
        // Note: consider both standard imported version (e.g., @Before) and weird non-imported version (e.g., @org.junit.Before)
        // Only include BeforeClass and Before if in separate classes (for both victim and polluter(s))
        if (!isSameTestClass) {
            polluterStmts.add(new BlockStmt(getCodeFromAnnotatedMethod(polluterMethod.getClassName(), polluterMethod.javaFile(), "@org.junit.BeforeClass")));
        }
        polluterStmts.add(new BlockStmt(getCodeFromAnnotatedMethod(polluterMethod.getClassName(), polluterMethod.javaFile(), "@org.junit.Before")));
        if (!expected) {
            polluterStmts.addAll(polluterMethod.body().getStatements());
        } else {
            // Wrap the body inside a big try statement to suppress any exceptions
            ClassOrInterfaceType exceptionType = new ClassOrInterfaceType().setName(new SimpleName("Throwable"));
            CatchClause catchClause = new CatchClause(new Parameter(exceptionType, "ex"), new BlockStmt());
            polluterStmts.add(new TryStmt(new BlockStmt(polluterMethod.body().getStatements()), NodeList.nodeList(catchClause), new BlockStmt()));
        }
        // Only include AfterClass and After if in separate classes (for both victim and polluter(s))
        polluterStmts.add(new BlockStmt(getCodeFromAnnotatedMethod(polluterMethod.getClassName(), polluterMethod.javaFile(), "@org.junit.After")));
        if (!isSameTestClass) {
            polluterStmts.add(new BlockStmt(getCodeFromAnnotatedMethod(polluterMethod.getClassName(), polluterMethod.javaFile(), "@org.junit.AfterClass")));
        }

        return polluterStmts;
    }

    private int statementsSize(NodeList<Statement> stmts) {
        int size = 0;
        for (Statement stmt : stmts) {
            // Take care of try statement block
            if (stmt instanceof TryStmt) {
                size += ((TryStmt) stmt).getTryBlock().getStatements().size();
            } else {
                size++;
            }
        }
        return size;
    }

    // Helper method to try out combinations of including all code from polluter method to make tests fail
    private Object[] findValidMethodToModify(List<String> passingOrder,
                                             JavaMethod victimMethod, JavaMethod polluterMethod) throws Exception {
        Object[] returnValues = new Object[3];

        for (Boolean newTestClass : new boolean[]{true, false}) {
            ImmutablePair<JavaMethod, Boolean> tuple = ImmutablePair.of(victimMethod, true);
            JavaMethod methodToModify = tuple.getLeft();
            if (methodToModify == null) {   // When polluter is null, so brittle
                continue;
            }
            // Start with all polluter statements, based on what method to modify
            final NodeList<Statement> polluterStmts = NodeList.nodeList();
            polluterStmts.addAll(makePolluterStatements(polluterMethod, methodToModify));

            // Get a reference to the setup/teardown method, where we want to add the call to the helper
            //JavaMethod auxiliaryMethodToModify = getAuxiliaryMethod(methodToModify, prepend);

            // Get the helper method reference
            //JavaMethod helperMethod = addHelperMethod(cleanerMethod, auxiliaryMethodToModify, newTestClass, prepend);
            JavaMethod helperMethod = addHelperMethod(polluterMethod, methodToModify, newTestClass);

            // Check if applying these cleaners on the method suffices
            TestPluginPlugin.info("Applying code from polluter and recompiling.");
            PolluterDeltaDebugger debugger = new PolluterDeltaDebugger(this.project, this.runner, helperMethod, passingOrder);
            if (debugger.checkValid(polluterStmts, false)) {
                returnValues[0] = methodToModify;
                returnValues[1] = helperMethod;
                returnValues[2] = polluterStmts;
                return returnValues;
            }
            TestPluginPlugin.error("Applying all of polluter " + polluterMethod.methodName() + " to " + methodToModify.methodName() + " does not make it fail!");
            restore(methodToModify.javaFile());
            restore(helperMethod.javaFile());
            MvnCommands.runMvnInstall(this.project, false);
        }


        return returnValues;

    }

    // Returns if applying the fix was successful or not
    private PatchResult applyPollute(final List<String> passingOrder,
                                 final JavaMethod polluterMethod,
                                 final JavaMethod victimMethod) throws Exception {
        // Back up the files we are going to modify
        if (polluterMethod != null) {
            backup(polluterMethod.javaFile());
        }
        backup(victimMethod.javaFile());

        // Get the starting form
        Object[] startingValues = findValidMethodToModify(passingOrder, victimMethod, polluterMethod);
        JavaMethod methodToModify = (JavaMethod)startingValues[0];
        if (methodToModify == null) {   // If not method returned, means things are broken
            // Restore files back to what they were before and recompile, in preparation for later
            if (polluterMethod != null) {
                backup(polluterMethod.javaFile());
            }
            backup(victimMethod.javaFile());
            MvnCommands.runMvnInstall(this.project, false);
            NodeList<Statement> initialCleanerStmts = makePolluterStatements(polluterMethod, victimMethod);
            Path patch = writePatch(victimMethod, 0, new BlockStmt(initialCleanerStmts), statementsSize(initialCleanerStmts), null, polluterMethod, 0, "CLEANER DOES NOT FIX");
            return new PatchResult(OperationTime.instantaneous(), PolluteStatus.CLEANER_FAIL, victimMethod.methodName(), "N/A", 0, patch.toString());
        }
        final JavaMethod finalHelperMethod = (JavaMethod)startingValues[1];
        final NodeList<Statement> polluterStmts = (NodeList<Statement>)startingValues[2];

        // Minimizing cleaner code, which includes setup and teardown
        TestPluginPlugin.info("Going to modify " + methodToModify.methodName() + " to make passing order fail.");
        final List<OperationTime> elapsedTime = new ArrayList<>();
        int originalsize = statementsSize(polluterStmts);
        final PolluterDeltaDebugger finalDebugger = new PolluterDeltaDebugger(this.project, this.runner, finalHelperMethod, passingOrder);
        final NodeList<Statement> minimalPolluterStmts = OperationTime.runOperation(() -> {
            // Cleaner is good, so now we can start delta debugging
            NodeList<Statement> interPolluterStmts = NodeList.nodeList(polluterStmts);
            NodeList<Statement> currentInterPolluterStmts;
            do {
                currentInterPolluterStmts = NodeList.nodeList(interPolluterStmts);
                interPolluterStmts = NodeList.nodeList();
                interPolluterStmts.addAll(finalDebugger.deltaDebug(currentInterPolluterStmts, 2));

                // Debug each statement further if they contain blocks, so debug within statements in that block(s)
                interPolluterStmts = debugFurther(interPolluterStmts, finalHelperMethod, passingOrder, interPolluterStmts);

                // "Unravel" any blocks and potentially debug some more
                NodeList<Statement> unraveledPolluterStmts = NodeList.nodeList();
                for (int i = 0; i < interPolluterStmts.size(); i++) {
                    Statement stmt = interPolluterStmts.get(i);
                    if (stmt instanceof BlockStmt) {
                        BlockStmt blockStmt = (BlockStmt)stmt;

                        // If block is empty, just move on
                        if (blockStmt.isEmpty()) {
                            continue;
                        }

                        // Try to take all statements from this block out and see if still works combined with others
                        NodeList<Statement> tmpStmts = NodeList.nodeList();
                        tmpStmts.addAll(unraveledPolluterStmts);
                        tmpStmts.addAll(blockStmt.getStatements());
                        for (int j = i + 1; j < interPolluterStmts.size(); j++) {
                            tmpStmts.add(interPolluterStmts.get(j));
                        }
                        // Check if unraveling this block combined with rest still works
                        if (finalDebugger.checkValid(tmpStmts)) {
                            unraveledPolluterStmts.addAll(blockStmt.getStatements());
                        } else {
                            unraveledPolluterStmts.add(blockStmt);
                        }
                    } else {
                        unraveledPolluterStmts.add(stmt);
                    }
                }
                interPolluterStmts = unraveledPolluterStmts;
            // Continually loop and try to minimize more, until reach fixpoint
            // Can end up minimizing more after unraveling blocks and such, revealing more opportunities to minimize
            } while(!interPolluterStmts.equals(currentInterPolluterStmts));

            return interPolluterStmts;
        }, (finalCleanerStmts, time) -> {
            elapsedTime.add(time);
            return finalCleanerStmts;
        });

        int iterations = finalDebugger.getIterations();

        BlockStmt patchedBlock = new BlockStmt(minimalPolluterStmts);

        // Check that the results are valid
        if (!finalDebugger.checkValid(minimalPolluterStmts, false)) {
            TestPluginPlugin.info("Final minimal is not actually working!");
            restore(methodToModify.javaFile());
            restore(finalHelperMethod.javaFile());
            MvnCommands.runMvnInstall(this.project, false);
            Path patch = writePatch(victimMethod, 0, patchedBlock, originalsize, methodToModify, polluterMethod, elapsedTime.get(0).elapsedSeconds(), "BROKEN MINIMAL");
            return new PatchResult(elapsedTime.get(0), PolluteStatus.FIX_INVALID, victimMethod.methodName(), polluterMethod != null ? polluterMethod.methodName() : "N/A", iterations, patch.toString());
        }

        // Try to inline these statements into the method
        restore(methodToModify.javaFile());
        methodToModify = JavaMethod.find(methodToModify.methodName(), testSources(), classpath()).get();   // Reload, just in case
        PolluterDeltaDebugger debugger = new PolluterDeltaDebugger(this.project, this.runner, methodToModify, passingOrder);
        boolean inlineSuccessful = debugger.checkValid(minimalPolluterStmts, false);
        if (!inlineSuccessful) {
            TestPluginPlugin.info("Inlining polluter patch into " + methodToModify.methodName() + " not good enough to run.");
        }

        // Do the check of removing cleaner statements from cleaner itself and see if the cleaner now starts failing
        TestPluginPlugin.info("Trying to remove statements from cleaner to see if it becomes order-dependent.");
        PolluteStatus removalCheck = checkPolluterRemoval(passingOrder, victimMethod, minimalPolluterStmts);

        // Figure out what the final fix status should be
        PolluteStatus polluteStatus;
        String status;
        if (inlineSuccessful) {
            if (removalCheck == PolluteStatus.FIX_INLINE_SETUPTEARDOWN) {
                polluteStatus = PolluteStatus.FIX_INLINE_SETUPTEARDOWN;
                status = "INLINE SUCCESSFUL SETUPTEARDOWN";
            } else if (removalCheck == PolluteStatus.FIX_INLINE_CANREMOVE) {
                polluteStatus = PolluteStatus.FIX_INLINE_CANREMOVE;
                status = "INLINE SUCCESSFUL CANREMOVE";
            } else {
                polluteStatus = PolluteStatus.FIX_INLINE;
                status = "INLINE SUCCESSFUL";
            }
        } else {
            if (removalCheck == PolluteStatus.FIX_INLINE_SETUPTEARDOWN) {
                polluteStatus = PolluteStatus.FIX_NO_INLINE_SETUPTEARDOWN;
                status = "INLINE FAIL SETUPTEARDOWN";
            } else if (removalCheck == PolluteStatus.FIX_INLINE_CANREMOVE) {
                polluteStatus = PolluteStatus.FIX_NO_INLINE_CANREMOVE;
                status = "INLINE FAIL CANREMOVE";
            } else {
                polluteStatus = PolluteStatus.FIX_NO_INLINE;
                status = "INLINE FAIL";
            }
        }

        // Write out the changes in the form of a patch
        int startingLine;
        startingLine = methodToModify.beginLine() + 1;  // Shift one, do not include declaration line

        Path patchFile = writePatch(victimMethod, startingLine, patchedBlock, originalsize, methodToModify, polluterMethod, elapsedTime.get(0).elapsedSeconds(), status);

        patches.add(new Patch(methodToModify, patchedBlock, victimMethod, testSources(), classpath(), inlineSuccessful));

        // Report successful patching, report where the patch is
        TestPluginPlugin.info("Patching successful, patch file for " + victimMethod.methodName() + " found at: " + patchFile);

        // Restore the original file
        restore(methodToModify.javaFile());
        restore(finalHelperMethod.javaFile());
        // Final compile to get state to right place
        MvnCommands.runMvnInstall(this.project, false);

        return new PatchResult(elapsedTime.get(0), polluteStatus, victimMethod.methodName(), polluterMethod != null ? polluterMethod.methodName() : "N/A", iterations, patchFile.toString());
    }

    // Debug list of statements even further, if any statement contains blocks
    private NodeList<Statement> debugFurther(NodeList<Statement> stmts, JavaMethod helperMethod,
                                             List<String> passingOrder, NodeList<Statement> stmtsToRun) {
        PolluterBlockDeltaDebugger debugger;

        // Iterate through all statements and try to debug further if contain block
        for (int i = 0; i < stmts.size(); i++) {
            Statement stmt = stmts.get(i);

            if (stmt instanceof BlockStmt) {
                BlockStmt blockStmt = (BlockStmt)stmt;

                debugger = new PolluterBlockDeltaDebugger(this.project, this.runner, helperMethod, passingOrder, blockStmt, stmtsToRun);
                NodeList<Statement> minimalBlockStmts = NodeList.nodeList();
                minimalBlockStmts.addAll(debugger.deltaDebug(blockStmt.getStatements(), 2));
                blockStmt.setStatements(minimalBlockStmts);

                // Debug further nested blocks
                minimalBlockStmts = debugFurther(minimalBlockStmts, helperMethod, passingOrder, stmtsToRun);
                blockStmt.setStatements(minimalBlockStmts);
            } else if (stmt instanceof TryStmt) {
                TryStmt tryStmt = (TryStmt)stmt;

                // Do the try block part
                debugger = new PolluterBlockDeltaDebugger(this.project, this.runner, helperMethod, passingOrder, tryStmt.getTryBlock(), stmtsToRun);
                NodeList<Statement> minimalBlockStmts = NodeList.nodeList();
                minimalBlockStmts.addAll(debugger.deltaDebug(tryStmt.getTryBlock().getStatements(), 2));
                tryStmt.setTryBlock(new BlockStmt(minimalBlockStmts));

                // Debug further nested blocks
                minimalBlockStmts = debugFurther(minimalBlockStmts, helperMethod, passingOrder, stmtsToRun);
                tryStmt.setTryBlock(new BlockStmt(minimalBlockStmts));

                // If has finally block, do that
                if (tryStmt.getFinallyBlock().isPresent()) {
                    debugger = new PolluterBlockDeltaDebugger(this.project, this.runner, helperMethod, passingOrder, tryStmt.getFinallyBlock().get(), stmtsToRun);
                    minimalBlockStmts = NodeList.nodeList();
                    minimalBlockStmts.addAll(debugger.deltaDebug(tryStmt.getFinallyBlock().get().getStatements(), 2));
                    tryStmt.setFinallyBlock(new BlockStmt(minimalBlockStmts));

                    // Debug further nested blocks
                    minimalBlockStmts = debugFurther(minimalBlockStmts, helperMethod, passingOrder, stmtsToRun);
                    tryStmt.setFinallyBlock(new BlockStmt(minimalBlockStmts));

                    // If the finally block is empty, remove
                    if (minimalBlockStmts.isEmpty()) {
                        tryStmt.removeFinallyBlock();
                    }
                }

                // Special case for try: see if we can remove the try and change just to a normal block
                // This can happen if minimized enough statements as to remove the ones that actually throw exceptions
                if (!tryStmt.getFinallyBlock().isPresent()) {   // For now, only do if finally block is not there
                    BlockStmt blockStmt = new BlockStmt();
                    blockStmt.setStatements(tryStmt.getTryBlock().getStatements());

                    // Manipulate list to add this block at that location and remove the try that got shifted next
                    stmts.add(i, blockStmt);
                    stmts.remove(i + 1);

                    // Use debugger to just check if things work with this block instead of try
                    debugger = new PolluterBlockDeltaDebugger(this.project, this.runner, helperMethod, passingOrder, blockStmt, stmtsToRun);
                    if (!debugger.checkValid(blockStmt.getStatements())) {
                        // If invalid, we should set the try statement back in
                        stmts.add(i, tryStmt);
                        stmts.remove(i + 1);
                    }
                }
            }
        }

        return stmts;
    }

    // Helper method to create a patch file adding in the passed in block
    // Includes a bunch of extra information that may be useful
    private Path writePatch(JavaMethod victimMethod, int begin, BlockStmt blockStmt, int originalsize,
                            JavaMethod modifiedMethod,
                            JavaMethod polluterMethod,
                            double elapsedTime, String status) throws IOException {
        List<String> patchLines = new ArrayList<>();
        patchLines.add("STATUS: " + status);
        patchLines.add("MODIFIED: " + (modifiedMethod == null ? "N/A" : modifiedMethod.methodName()));
        patchLines.add("MODIFIED FILE: " + (modifiedMethod == null ? "N/A" : modifiedMethod.javaFile().path()));
        patchLines.add("POLLUTER: " + (polluterMethod == null ? "N/A" : polluterMethod.methodName()));
        patchLines.add("POLLUTER FILE: " + (polluterMethod == null ? "N/A" : polluterMethod.javaFile().path()));
        patchLines.add("ORIGINAL CLEANER SIZE: " + (originalsize == 0 ? "N/A" : String.valueOf(originalsize)));
        patchLines.add("NEW CLEANER SIZE: " + (blockStmt != null ? String.valueOf(statementsSize(blockStmt.getStatements())) : "N/A"));
        patchLines.add("ELAPSED TIME: " + elapsedTime);

        // If there is a block to add (where it might not be if in error state and need to just output empty)
        if (blockStmt != null) {
            patchLines.add(PATCH_LINE_SEP);
            String[] lines = blockStmt.toString().split("\n");
            patchLines.add("@@ -" + begin +",0 +" + begin + "," + lines.length + " @@");
            for (String line : lines) {
                patchLines.add("+ " + line);
            }
        }
        Path patchFile = PolluterPathManager.fixer().resolve(victimMethod.methodName() + ".patch");  // The patch file is based on the dependent test
        Files.createDirectories(patchFile.getParent());

        // If the file exists, then need to give it a new name
        if (Files.exists(patchFile)) {
            // Keep adding to a counter to make unique name
            Path newPatchFile;
            int counter = 1;
            while (true) {
                newPatchFile = Paths.get(patchFile + "." + counter);
                if (!Files.exists(newPatchFile)) {  // Found a valid file to write to
                    patchFile = newPatchFile;
                    break;
                }
                counter++;
            }
        }
        Files.write(patchFile, patchLines);
        return patchFile;
    }
}
