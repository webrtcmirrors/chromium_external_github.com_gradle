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

package org.gradle.plugins.performance;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.PluginContainer;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.testing.junit.JUnitOptions;
import org.gradle.internal.Actions;
import org.gradle.plugins.ide.eclipse.EclipsePlugin;
import org.gradle.plugins.ide.eclipse.model.EclipseClasspath;
import org.gradle.plugins.ide.eclipse.model.EclipseModel;
import org.gradle.plugins.ide.idea.IdeaPlugin;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.gradle.plugins.ide.idea.model.IdeaModule;
import org.gradle.testing.BuildScanPerformanceTest;
import org.gradle.testing.DistributedPerformanceTest;
import org.gradle.testing.PerformanceTest;
import org.gradle.testing.RebaselinePerformanceTests;
import org.gradle.testing.ReportGenerationPerformanceTest;
import org.gradle.testing.performance.generator.tasks.JavaExecProjectGeneratorTask;
import org.gradle.testing.performance.generator.tasks.JvmProjectGeneratorTask;
import org.gradle.testing.performance.generator.tasks.ProjectGeneratorTask;
import org.gradle.testing.performance.generator.tasks.RemoteProject;
import org.gradle.testing.performance.generator.tasks.TemplateProjectGeneratorTask;
import org.gradle.util.GUtil;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class PerformanceTestPlugin implements Plugin<Project> {
    private final Action<PerformanceTest> excludePerformanceExperiments = new Action<PerformanceTest>() {
        @Override
        public void execute(PerformanceTest performanceTest) {
            JUnitOptions jUnitOptions = (JUnitOptions) performanceTest.getOptions();
            jUnitOptions.excludeCategories(Config.performanceExperimentCategory);
        }
    };

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java");
        TaskContainer tasks = project.getTasks();
        ConfigurationContainer configurations = project.getConfigurations();

        // Some of CI builds have parameter `-x performanceReport` so simply removing `performanceReport` would result in an error
        // This task acts as a workaround for transition
        project.getTasks().register("performanceReport");

        JavaPluginConvention java = project.getConvention().getPlugin(JavaPluginConvention.class);
        SourceSet main = java.getSourceSets().getByName("main");
        SourceSet test = java.getSourceSets().getByName("test");
        SourceSet performanceTest = java.getSourceSets().create("performanceTest");
        project.getDependencies().add(performanceTest.getCompileClasspathConfigurationName(), main.getOutput());
        project.getDependencies().add(performanceTest.getCompileClasspathConfigurationName(), test.getOutput());

        Configuration testCompile = configurations.getByName(test.getCompileConfigurationName());
        Configuration performanceTestCompile = configurations.getByName(performanceTest.getCompileConfigurationName());
        performanceTestCompile.extendsFrom(testCompile);

        Configuration testRuntime = configurations.getByName(test.getRuntimeConfigurationName());
        Configuration performanceTestRuntime = configurations.getByName(performanceTest.getRuntimeConfigurationName());
        performanceTestCompile.extendsFrom(testRuntime);

        Configuration performanceTestRuntimeClasspath = configurations.getByName(performanceTest.getRuntimeClasspathConfigurationName());
        Configuration partialDistribution = configurations.maybeCreate("partialDistribution");
        partialDistribution.extendsFrom(performanceTestRuntimeClasspath);

        Configuration junit = configurations.create("junit");

        tasks.withType(ProjectGeneratorTask.class).configureEach(new Action<ProjectGeneratorTask>() {
            @Override
            public void execute(ProjectGeneratorTask task) {
                task.setGroup("Project setup");
            }
        });

        tasks.withType(JvmProjectGeneratorTask.class).configureEach(new Action<JvmProjectGeneratorTask>() {
            @Override
            public void execute(JvmProjectGeneratorTask jvmProjectGeneratorTask) {
                jvmProjectGeneratorTask.setTestDependencies(junit);
            }
        });

        tasks.withType(TemplateProjectGeneratorTask.class).configureEach(new Action<TemplateProjectGeneratorTask>() {
            @Override
            public void execute(TemplateProjectGeneratorTask templateProjectGeneratorTask) {
                // TODO: Remove this reference to an explicit subproject
                templateProjectGeneratorTask.setSharedTemplateDirectory(project.project(":internalPerformanceTesting").file("src/templates"));
            }
        });

        TaskProvider<Task> prepareSamples = prepareSampleTasks(tasks);

        createLocalPerformanceTestTasks(project, performanceTest);
        createDistributionPerformanceTestTasks(project, performanceTest, prepareSamples);

        createRebaselineTask(tasks, performanceTest);

        configureIdePlugins(project, performanceTest, performanceTestCompile, performanceTestRuntime);
    }

    @NotNull
    private TaskProvider<Task> prepareSampleTasks(TaskContainer tasks) {
        TaskProvider<Task> prepareSamplesTask = tasks.register("prepareSamples", new Action<Task>() {
            @Override
            public void execute(Task task) {
                task.setGroup("Project Setup");
                task.setDescription("Generates all sample projects for automated performance tests");
                task.dependsOn(tasks.withType(ProjectGeneratorTask.class));
                task.dependsOn(tasks.withType(RemoteProject.class));
                task.dependsOn(tasks.withType(JavaExecProjectGeneratorTask.class));
            }
        });

        tasks.register("cleanSamples", Delete.class, new Action<Delete>() {
            @Override
            public void execute(Delete task) {
                task.delete(tasks.withType(ProjectGeneratorTask.class));
                task.delete(tasks.withType(RemoteProject.class));
                task.delete(tasks.withType(JavaExecProjectGeneratorTask.class));
            }
        });
        return prepareSamplesTask;
    }

    private void createLocalPerformanceTestTasks(Project project, SourceSet performanceTest) {
        Class performanceTestClass = PerformanceTest.class;
        if (project.getName().equals("buildScanPerformance")) {
            performanceTestClass = BuildScanPerformanceTest.class;
        }
        createLocalPerformanceTestTask(project, "performanceTest", performanceTestClass, performanceTest, excludePerformanceExperiments);
        createLocalPerformanceTestTask(project, "performanceExperiment", performanceTestClass, performanceTest, excludePerformanceExperiments);
        createLocalPerformanceTestTask(project, "fullPerformanceTest", performanceTestClass, performanceTest, Actions.doNothing());
        createLocalPerformanceTestTask(project, "performanceAdhocTest", performanceTestClass, performanceTest, new Action<PerformanceTest>() {
            @Override
            public void execute(PerformanceTest performanceTest) {
                Map<String, String> databaseParameters = new HashMap<>();
                databaseParameters.put(PropertyNames.dbUrl, Config.adhocTestDbUrl);
                performanceTest.addDatabaseParameters(databaseParameters);
                performanceTest.setChannel("adhoc");
                performanceTest.getOutputs().doNotCacheIf("is adhoc performance test", Specs.SATISFIES_ALL);
            }
        });
    }

    private TaskProvider<PerformanceTest> createLocalPerformanceTestTask(
        Project project,
        String name,
        Class<PerformanceTest> performanceTestType,
        SourceSet performanceSourceSet,
        Action<PerformanceTest> configuration) {

        TaskContainer tasks = project.getTasks();
        TaskProvider<PerformanceTest> performanceTest = project.getTasks().register(name, performanceTestType, new Action<PerformanceTest>() {
            @Override
            public void execute(PerformanceTest task) {
                configureForAnyPerformanceTestTask(project, task, performanceSourceSet);

                if (project.hasProperty(PropertyNames.performanceTestVerbose)) {
                    task.getTestLogging().setShowStandardStreams(true);
                }
            }
        });

        TaskProvider<Zip> testResultsZipTask = tasks.register(name + "ResultsZip", Zip.class, new Action<Zip>() {
            @Override
            public void execute(Zip zip) {
                File junitXmlDir = performanceTest.get().getReports().getJunitXml().getDestination();
                zip.from(junitXmlDir, new Action<CopySpec>() {
                    @Override
                    public void execute(CopySpec copySpec) {
                        copySpec.include("**/TEST-*.xml");
                        copySpec.setIncludeEmptyDirs(false);
                        copySpec.eachFile(new Action<FileCopyDetails>() {
                            @Override
                            public void execute(FileCopyDetails fileCopyDetails) {
                                // skip files where all tests were skipped
                                if (allTestsWereSkipped(fileCopyDetails.getFile())) {
                                    fileCopyDetails.exclude();
                                }
                            }
                        });
                    }
                });
                zip.from(performanceTest.get().getDebugArtifactsDirectory());
                zip.setDestinationDir(project.getBuildDir());
                zip.setArchiveName("test-results-" + junitXmlDir.getName() + ".zip");
            }
        });

        performanceTest.configure(new Action<PerformanceTest>() {
            @Override
            public void execute(PerformanceTest performanceTest) {
                performanceTest.finalizedBy(testResultsZipTask);
            }
        });

        return performanceTest;
    }

    private void configureForAnyPerformanceTestTask(Project project, PerformanceTest task, SourceSet performanceSourceSet) {
        task.setGroup("verification");
        task.addDatabaseParameters(propertiesForPerformanceDb(task.getProject()));
        task.setTestClassesDirs(performanceSourceSet.getOutput().getClassesDirs());
        task.setClasspath(performanceSourceSet.getRuntimeClasspath());

        task.getBinaryDistributions().setBinZipRequired(true);
        task.getLibsRepository().setRequired(true);
        task.setMaxParallelForks(1);
        task.getSamplesDirectory().set(project.getLayout().getBuildDirectory().dir("samples"));

        Object baselines = task.getProject().findProperty(PropertyNames.baselines);
        if (baselines!=null) {
            task.setBaselines((String)baselines);
        }

        task.jvmArgs("-Xmx5g", "-XX:+HeapDumpOnOutOfMemoryError");

        task.mustRunAfter(project.getTasks().withType(ProjectGeneratorTask.class));
        task.mustRunAfter(project.getTasks().withType(RemoteProject.class));
        task.mustRunAfter(project.getTasks().withType(JavaExecProjectGeneratorTask.class));

        if (task instanceof ReportGenerationPerformanceTest) {
            ((ReportGenerationPerformanceTest) task).setBuildId(System.getenv("BUILD_ID"));
            // TODO: Use Provider instead
            ((ReportGenerationPerformanceTest) task).setReportDir(new File(project.getBuildDir(), Config.performanceTestReportsDir));
        }
    }

    private Map<String, String> propertiesForPerformanceDb(Project project) {
        Map<String, String> databaseParameters = new HashMap<>();
        addIfPropertyExists(project, PropertyNames.dbUrl, databaseParameters);
        addIfPropertyExists(project, PropertyNames.dbUsername, databaseParameters);
        addIfPropertyExists(project, PropertyNames.dbPassword, databaseParameters);
        return databaseParameters;
    }

    private void addIfPropertyExists(Project project, String property, Map<String, String> map) {
        if (project.hasProperty(property)) {
            map.put(property, (String)project.findProperty(property));
        }
    }

    private void createDistributionPerformanceTestTasks(Project project, SourceSet performanceTest, TaskProvider<Task> prepareSamples) {
        createDistributedPerformanceTestTask(project, "distributedPerformanceTest", performanceTest, new Action<PerformanceTest>() {
            @Override
            public void execute(PerformanceTest performanceTest) {
                performanceTest.setChannel("commits");
                excludePerformanceExperiments.execute(performanceTest);
                performanceTest.dependsOn(prepareSamples);
            }
        });

        createDistributedPerformanceTestTask(project, "distributedPerformanceExperiment", performanceTest, new Action<PerformanceTest>() {
            @Override
            public void execute(PerformanceTest performanceTest) {
                performanceTest.setChannel("experiments");
                excludePerformanceExperiments.execute(performanceTest);
                performanceTest.dependsOn(prepareSamples);
            }
        });

        createDistributedPerformanceTestTask(project, "distributedFullPerformanceTest", performanceTest, new Action<PerformanceTest>() {
            @Override
            public void execute(PerformanceTest performanceTest) {
                performanceTest.setBaselines(Config.baseLineList.toString());
                performanceTest.setChecks("none");
                performanceTest.setChannel("historical");
                performanceTest.dependsOn(prepareSamples);
            }
        });
    }

    private TaskProvider<DistributedPerformanceTest> createDistributedPerformanceTestTask(Project project, String name, SourceSet performanceSourceSet, Action<PerformanceTest> configuration) {
        TaskContainer tasks = project.getTasks();
        TaskProvider<DistributedPerformanceTest> performanceTest = tasks.register(name, DistributedPerformanceTest.class, new Action<DistributedPerformanceTest>() {
            @Override
            public void execute(DistributedPerformanceTest distributedPerformanceTest) {
                configureForAnyPerformanceTestTask(project, distributedPerformanceTest, performanceSourceSet);
                // TODO: Replace with Provider/Property
                distributedPerformanceTest.setScenarioList(new File(project.getBuildDir(), Config.performanceTestScenarioListFileName));
                distributedPerformanceTest.setBuildTypeId((String)project.findProperty(PropertyNames.buildTypeId));
                distributedPerformanceTest.setWorkerTestTaskName(GUtil.elvis((String)project.findProperty(PropertyNames.workerTestTaskName), "fullPerformanceTest"));
                distributedPerformanceTest.setBranchName((String)project.findProperty(PropertyNames.branchName));
                distributedPerformanceTest.setTeamCityUrl(Config.teamCityUrl);
                distributedPerformanceTest.setTeamCityUsername((String)project.findProperty(PropertyNames.teamCityUsername));
                distributedPerformanceTest.setTeamCityPassword((String)project.findProperty(PropertyNames.teamCityPassword));
            }
        });

        // TODO: replace this with a Provider/Property
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project project) {
                performanceTest.configure(new Action<DistributedPerformanceTest>() {
                    @Override
                    public void execute(DistributedPerformanceTest performanceTest) {
                        String branchName = performanceTest.getBranchName();
                        if (branchName!=null && !branchName.isEmpty()) {
                            performanceTest.setChannel(performanceTest.getChannel() + "-" + branchName);
                        }
                    }
                });
            }
        });

        return performanceTest;
    }

    private void createRebaselineTask(TaskContainer tasks, SourceSet performanceTest) {
        tasks.register("rebaselinePerformanceTests", RebaselinePerformanceTests.class, new Action<RebaselinePerformanceTests>() {
            @Override
            public void execute(RebaselinePerformanceTests rebaselinePerformanceTests) {
                rebaselinePerformanceTests.source(performanceTest.getAllSource());
            }
        });
    }

    private void configureIdePlugins(Project project, SourceSet performanceTest, Configuration performanceTestCompile, Configuration performanceTestRuntime) {
        PluginContainer plugins = project.getPlugins();
        plugins.withType(EclipsePlugin.class, new Action<EclipsePlugin>() {
            @Override
            public void execute(EclipsePlugin eclipsePlugin) {
                EclipseModel eclipseModel = project.getExtensions().getByType(EclipseModel.class);
                EclipseClasspath eclipseClasspath = eclipseModel.getClasspath();
                eclipseClasspath.getPlusConfigurations().addAll(Arrays.asList(performanceTestCompile, performanceTestRuntime));
            }
        });
        plugins.withType(IdeaPlugin.class, new Action<IdeaPlugin>() {
            @Override
            public void execute(IdeaPlugin eclipsePlugin) {
                IdeaModel ideaModel = project.getExtensions().getByType(IdeaModel.class);
                IdeaModule module = ideaModel.getModule();
                module.setTestSourceDirs(performanceTest.getAllSource().getSrcDirs());
                module.setTestResourceDirs(performanceTest.getResources().getSrcDirs());
                module.getScopes().get("TEST").get("plus").addAll(Arrays.asList(performanceTestCompile, performanceTestRuntime));
            }
        });
    }

    public static boolean allTestsWereSkipped(File junitXml) {
        try {
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(junitXml);
            Element documentElement = document.getDocumentElement();
            return documentElement.getAttribute("tests").equals(documentElement.getAttribute("skipped"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }
}
