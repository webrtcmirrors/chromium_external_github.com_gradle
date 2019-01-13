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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.text.*;
import java.util.stream.*;

public class Main {
    private static String projectDirPath = "/home/tcagent1/agent/work/668602365d1521fc";
    private static File projectDir = new File(projectDirPath);
    private static Map<String, String> gradleBinary = new HashMap<>();
    private static String javaHome = System.getenv("JAVA_HOME");
    private static String jcmdPath = javaHome + "/bin/jcmd";
    private static String jfcPath = projectDirPath + "/subprojects/internal-performance-testing/src/main/resources/org/gradle/performance/fixture/gradle.jfc";
    private static int runCount = Integer.parseInt(System.getProperty("runCount"));
    private static String flameGraphDir = "/root/FlameGraph";
    private static String cpuTempCmd = "/root/msr-cloud-tools/cputemp";
    private static ExecutorService threadPool = Executors.newSingleThreadExecutor();

    static {
        gradleBinary.put("baseline1",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000/bin/gradle");
        gradleBinary.put("baseline2",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-2/bin/gradle");
        gradleBinary.put("baseline3",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-3/bin/gradle");
        gradleBinary.put("baseline4",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-4/bin/gradle");
        gradleBinary.put("current1",
            projectDirPath + "/subprojects/performance/build/integ test/bin/gradle");
        gradleBinary.put("current2",
            projectDirPath + "/subprojects/performance/build/integ test-2/bin/gradle");
    }

    private static class Experiment {
        String version;
        List<ExecutionResult> results;

        public Experiment(String version, List<ExecutionResult> results) {
            this.version = version;
            this.results = results;
        }

        public String getVersion() {
            return version;
        }

        private double[] toDoubleArray() {
            return results.stream().map(ExecutionResult::getTime).mapToDouble(Long::doubleValue).toArray();
        }

        public String toString() {
            return version + ": " + results.stream().map(result -> result.output + "\n" + result.time + " ms").collect(Collectors.joining("\n"));
        }
    }

    private static class ExperimentSet {
        Experiment[] versions;

        public ExperimentSet(Experiment[] versions) {
            this.versions = versions;
        }

        public void printResultsAndConfidence() {
            System.out.println(toString());

            String fileName = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-result.txt";
            run(projectDir, "touch", fileName);
            writeFile(new File(fileName), toString());
        }

        public String toString() {
            String versionString = Stream.of(versions).map(Experiment::toString).collect(Collectors.joining("\n")) + "\n";

            for (int i = 0; i < versions.length; ++i) {
                for (int j = i + 1; j < versions.length; ++j) {
                    versionString += String.format("Confidence of %s and %s: %f\n", versions[i].version, versions[j].version, 1 - new MannWhitneyUTest().mannWhitneyUTest(versions[i].toDoubleArray(), versions[j].toDoubleArray()));
                }
            }
            return versionString;
        }
    }

    public static void main(String[] args) {
        List<ExperimentSet> allResults = IntStream.range(0, Integer.parseInt(System.getProperty("retryCount"))).mapToObj(i -> runASetOfExperiments()).collect(Collectors.toList());

        if (allResults.size() > 1) {
            System.out.println("All results:");
            allResults.forEach(ExperimentSet::printResultsAndConfidence);
        }

        threadPool.shutdown();
    }

    private static ExperimentSet runASetOfExperiments() {
        String[] versions = System.getProperty("expVersions").split(",");

        Stream.of(versions).forEach(Main::prepareForExperiment);

        List<Experiment> results = Stream.of(versions).map(Main::runExperiment).collect(Collectors.toList());

        ExperimentSet comparison = new ExperimentSet(results.toArray(new Experiment[0]));

        comparison.printResultsAndConfidence();

//        if (1 - new MannWhitneyUTest().mannWhitneyUTest(results.get(0).toDoubleArray(), results.get(1).toDoubleArray()) > 0.999) {
//            throw new IllegalStateException("Stop!");
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
        run(getExpProject(version), getWarmupExpArgs(version, "--stop"));
    }

    private static Experiment runExperiment(String version) {
        doWarmUp(version);

        String pid = readFile(getPidFile(version));

        List<ExecutionResult> results = doRun(version, getExpArgs(version, "help", pid), pid);

        stopDaemon(version);

        return new Experiment(version, results);
    }

    private static List<ExecutionResult> doRun(String version, List<String> args, String daemonPid) {
        return IntStream.range(0, runCount).mapToObj(i -> measureOnce(i, version, args, daemonPid)).collect(Collectors.toList());
    }

    private static ExecutionResult measureOnce(int index, String version, List<String> args, String daemonPid) {
        File workingDir = getExpProject(version);

        long t0 = System.currentTimeMillis();
        String output = runGetStderr(workingDir, args);
        long time = System.currentTimeMillis() - t0;

        String cpuTemp = runGetStdout(workingDir, Arrays.asList(cpuTempCmd));

        return new ExecutionResult(output + "\n" + cpuTemp, time);
    }

    private static class ExecutionResult {
        String output;
        long time;

        public long getTime() {
            return time;
        }

        public ExecutionResult(String output, long time) {
            this.output = output;
            this.time = time;
        }
    }

    private static List<String> getWarmupExpArgs(String version, String task) {
        return Arrays.asList(
            gradleBinary.get(version),
            "--gradle-user-home",
            getGradleUserHome(version).getAbsolutePath(),
            "--stacktrace",
            "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m",
            task
        );
    }

    private static List<String> getExpArgs(String version, String task, String pid) {
        List<String> args = new ArrayList<>(Arrays.asList("perf", "stat", "-p", pid, "--"));
        args.addAll(getWarmupExpArgs(version, task));
        return args;
    }

    private static File getGradleUserHome(String version) {
        return new File(projectDirPath, version + "GradleUserHome");
    }

    private static File getExpProject(String version) {
        return new File(projectDirPath, version + "ExpProject");
    }

    private static File getPidFile(String version) {
        return new File(getExpProject(version), "pid");
    }

    private static void doWarmUp(String version) {
        File workingDir = getExpProject(version);
        int warmups = Integer.parseInt(System.getProperty("warmUp"));

        List<String> args = new ArrayList<>(getWarmupExpArgs(version, "help"));
        args.add("--init-script");
        args.add(projectDirPath + "/pid-instrumentation.gradle");

        Map<String, String> env = new HashMap<>();
        env.put("PID_FILE_PATH", getPidFile(version).getAbsolutePath());

        run(workingDir, env, args);

        IntStream.range(1, warmups).forEach(i -> {
            run(workingDir, getWarmupExpArgs(version, "help"));
        });
    }

    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            run(projectDir, "rm", "-rf", dir.getAbsolutePath());
        }
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

    private static String runGetStdout(File workingDir, List<String> args) {
        return runGetOutput(workingDir, args, true);
    }

    private static String runGetStderr(File workingDir, List<String> args) {
        return runGetOutput(workingDir, args, false);
    }

    private static String runGetOutput(File workingDir, List<String> args, boolean stdout) {
        try {
            Process p = new ProcessBuilder(args).directory(workingDir).start();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout ? p.getInputStream() : p.getErrorStream()));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            int code = p.waitFor();
            assertTrue(code == 0);
            return sb.toString();
        } catch (Exception e) {
            handleException(e);
            return null;
        }
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
