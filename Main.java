/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.commons.math3.stat.inference.MannWhitneyUTest;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {
    private static String projectDirPath = "/home/tcagent1/agent/work/668602365d1521fc";
    private static File projectDir = new File(projectDirPath);
    private static Map<String, String> gradleBinary = new HashMap<>();
    private static String jcmdPath = System.getenv("JAVA_HOME") + "/bin/jcmd";
    private static String jfcPath = projectDirPath + "/subprojects/internal-performance-testing/src/main/resources/org/gradle/performance/fixture/gradle.jfc";

    static {
        gradleBinary.put("baseline1",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000/bin/gradle");
        gradleBinary.put("baseline2",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-2/bin/gradle");
        gradleBinary.put("baseline3",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-3/bin/gradle");
        gradleBinary.put("current1",
            projectDirPath + "/subprojects/performance/build/integ test/bin/gradle");
        gradleBinary.put("current2",
            projectDirPath + "/subprojects/performance/build/integ test-2/bin/gradle");
    }

    private static class Experiment {
        String version;
        List<Long> results;

        public Experiment(String version, List<Long> results) {
            this.version = version;
            this.results = results;
        }

        private double[] toDoubleArray() {
            return results.stream().mapToDouble(Long::doubleValue).toArray();
        }

        private void printResult() {
            System.out.println(version + ": " + results.stream().map(s -> s + " ms").collect(Collectors.joining(", ")));
        }
    }

    private static class ExperimentSet {
        Experiment version1;
        Experiment version2;
        Experiment version3;
        double confidence12;
        double confidence13;
        double confidence23;

        public ExperimentSet(Experiment version1, Experiment version2, Experiment version3) {
            this.version1 = version1;
            this.version2 = version2;
            this.version3 = version3;
            this.confidence12 = 1 - new MannWhitneyUTest().mannWhitneyUTest(version1.toDoubleArray(), version2.toDoubleArray());
            this.confidence13 = 1 - new MannWhitneyUTest().mannWhitneyUTest(version1.toDoubleArray(), version3.toDoubleArray());
            this.confidence23 = 1 - new MannWhitneyUTest().mannWhitneyUTest(version2.toDoubleArray(), version3.toDoubleArray());
        }

        public void printResultsAndConfidence() {
            version1.printResult();
            version2.printResult();
            version3.printResult();
            System.out.println(String.format("Confidence is %f %f %f", confidence12, confidence13, confidence23));
        }
    }

    public static void main(String[] args) {
        List<ExperimentSet> allResults = IntStream.range(0, Integer.parseInt(System.getProperty("retryCount"))).mapToObj(i -> runASetOfExperiments()).collect(Collectors.toList());

        if (allResults.size() > 1) {
            System.out.println("All results:");
            allResults.forEach(ExperimentSet::printResultsAndConfidence);
        }
    }

    private static ExperimentSet runASetOfExperiments() {
        String[] versions = System.getProperty("expVersions").split(",");

        Experiment version1 = runExperiment(versions[0]);
        Experiment version2 = runExperiment(versions[1]);
        Experiment version3 = runExperiment(versions[2]);

        ExperimentSet comparison = new ExperimentSet(version1, version2, version3);

        comparison.printResultsAndConfidence();

//        if (comparison.confidence > 0.99) {
//            throw new IllegalStateException("We need to stop.");
//        }
        return comparison;
    }

    private static void prepareForExperiment(String version) {
        initDirectory(getGradleUserHome(version));
        deleteDirectory(getExpProject(version));

        run(projectDir, "cp", "-r",
            projectDirPath + "/subprojects/performance/build/largeJavaMultiProjectKotlinDsl",
            getExpProject(version).getAbsolutePath());
    }

    private static void stopDaemon(String version) {
        run(getExpProject(version), getExpArgs(version, "--stop"));
    }

    private static Experiment runExperiment(String version) {
        prepareForExperiment(version);

        List<String> args = getWarmupExpArgs(version, "help");
        doWarmUp(version, args);

        List<Long> results = doRun(version, getExpArgs(version, "help"));

        stopDaemon(version);
        return new Experiment(version, results);
    }

    private static String readFile(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            handleException(e);
            return null;
        }
    }

    private static void writeFile(File file, String text) {
        try {
            Files.write(file.toPath(), text.getBytes(), StandardOpenOption.WRITE);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static List<Long> doRun(String version, List<String> args) {
        int runCount = Integer.parseInt(System.getProperty("runCount"));
        return IntStream.range(0, runCount).mapToObj(i -> measureOnce(i, version, args)).collect(Collectors.toList());
    }

    private static long measureOnce(int index, String version, List<String> args) {
        String pid = jfrEnabled() ? readFile(getPidFile(version)) : null;
        File workingDir = getExpProject(version);

        if (jfrEnabled()) {
            run(workingDir, jcmdPath, pid, "JFR.start", "name=" + version + "_" + index, "settings=" + jfcPath);
        }

        long t0 = System.currentTimeMillis();
        run(workingDir, args);
        long result = System.currentTimeMillis() - t0;

        if (jfrEnabled()) {
            run(workingDir, jcmdPath, pid, "JFR.stop", "name=" + version + "_" + index, "filename=" + getJfrPath(version, index));
        }

        return result;
    }

    private static boolean jfrEnabled() {
        return Boolean.parseBoolean(System.getProperty("jfrEnabled"));
    }

    private static String getJfrPath(String version, int iteration) {
        return new File(getExpProject(version), version + "_" + iteration + ".jfr").getAbsolutePath();
    }

    private static List<String> getExpArgs(String version, String task) {
        String jvmArgs = jfrEnabled()
            ? "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m -XX:+UnlockCommercialFeatures -XX:+FlightRecorder -XX:FlightRecorderOptions=stackdepth=1024 -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+PreserveFramePointer"
            : "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m -XX:+PreserveFramePointer";

        return Arrays.asList(
            gradleBinary.get(version),
            "--gradle-user-home",
            getGradleUserHome(version).getAbsolutePath(),
            "--stacktrace",
            jvmArgs,
            task
        );
    }

    private static List<String> getWarmupExpArgs(String version, String task) {
        if (jfrEnabled()) {
            List<String> args = new ArrayList<>(getExpArgs(version, task));
            args.add("--init-script");
            args.add(projectDirPath + "/pid-instrumentation.gradle");
            return args;
        } else {
            return getExpArgs(version, task);
        }
    }

    private static File getPidFile(String version) {
        return new File(getExpProject(version), "pid");
    }

    private static File getGradleUserHome(String version) {
        return new File(projectDirPath, version + "GradleUserHome");
    }

    private static File getExpProject(String version) {
        return new File(projectDirPath, version + "ExpProject");
    }

    private static void doWarmUp(String version, List<String> args) {
        File workingDir = getExpProject(version);
        int warmups = Integer.parseInt(System.getProperty("warmUp"));

        Map<String, String> env = new HashMap<>();
        env.put("PID_FILE_PATH", getPidFile(version).getAbsolutePath());

        IntStream.range(0, warmups).forEach(i -> {
            run(workingDir, env, args);
        });
    }

    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            run(projectDir, "rm", "-rf", dir.getAbsolutePath());
        }
    }

    private static void initDirectory(File dir) {
        deleteDirectory(dir);
        assertTrue(dir.mkdir());
    }

    private static void handleException(Exception e) {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        } else {
            throw new RuntimeException(e);
        }
    }

    private static void run(File workingDir, List<String> args) {
        run(workingDir, null, args);
    }

    private static void run(File workingDir, Map<String, String> envs, List<String> args) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args).directory(workingDir).inheritIO();
            if (envs != null) {
                pb.environment().putAll(envs);
            }
            int code = pb.start().waitFor();
            assertTrue(code == 0);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void run(File workingDir, String... args) {
        run(workingDir, Arrays.asList(args));
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new IllegalStateException();
        }
    }
}
