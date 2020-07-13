package randoop.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Mojo(name = "gentests", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class RandoopMojo extends AbstractMojo {

  @Parameter(required = true)
  private String packageName;

  @Parameter(required = true, defaultValue = "${project.build.outputDirectory}")
  private String sourceDirectory;

  @Parameter(required = true, defaultValue = "${project.build.directory}/generated-test-sources/java")
  private String targetDirectory;

  @Parameter(required = true, defaultValue = "30")
  private int timeoutInSeconds;

  @Parameter
  private String randoopPath;

  @Parameter
  private String extraParameters;

  @Parameter(required = true, defaultValue = "1")
  private int threadCount;

  @Component
  private MavenProject project;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    // Collect class and jars for class path
    final List<URL> urls = new LinkedList<>();
    loadRandoopJar(urls);
    urls.add(loadProjectClasses());
    urls.addAll(loadProjectDependencies(project));
    urls.add(loadPluginJarWithRandoop());
    runHandling(urls);
  }

  private List<String> buildArgs(final List<URL> urls) {
    String classPath = generateClasspath(urls);

    // Build up Randoop command line
    final List<String> args = new LinkedList<>();
    args.add("java");
    args.add("-ea");
    args.add("-classpath");
    args.add(classPath);
    args.add("randoop.main.Main");
    args.add("gentests");
    
    // Code under test
    args.add("--only-test-public-members=false");
    args.add("--flaky-test-behavior=OUTPUT");
    args.add("--nondeterministic-methods-to-output=1000");

    // Which tests to output:
    args.add("--no-error-revealing-tests=false");
    args.add("--no-regression-tests=false");
    args.add("--no-regression-assertions=false");
    args.add("--check-compilable=true");
    args.add("--minimize-error-test=false");

    // Test classification
    args.add("--checked-exception=EXPECTED");
    args.add("--unchecked-exception=EXPECTED");
    args.add("--cm-exception=INVALID");
    args.add("--ncdf-exception=INVALID");
    args.add("--npe-on-null-input=EXPECTED");
    args.add("--npe-on-non-null-input=ERROR");
    args.add("--oom-exception=INVALID");
    args.add("--sof-exception=INVALID");
    args.add("--use-jdk-specifications=true");
    args.add("--ignore-condition-compilation-error=false");
    args.add("--ignore-condition-exception=false");

    // Limiting test generation
    args.add("--time-limit=" + timeoutInSeconds);
    args.add("--attempted-limit=100000000");
    args.add("--generated-limit=100000000");
    args.add("--output-limit=100000000");
    args.add("--maxsize=1000");
    args.add("--stop-on-error-test=false");

    // Values used in tests
    args.add("--null-ratio=0.05");
    args.add("--forbid-null=false");
    args.add("--literals-level=CLASS");
    args.add("--method-selection=UNIFORM");
    args.add("--string-maxlen=1000");

    // Varying the nature of generated tests:
    args.add("--alias-ratio=0.0");
    args.add("--input-selection=UNIFORM");
    args.add("--clear=100000000");

    // Outputting the JUnit tests:
    args.add("--junit-package-name=" + packageName);
    args.add("--junit-output-dir=" + targetDirectory);
    args.add("--dont-output-tests=false");
    args.add("--junit-reflection-allowed=true");

    // Controlling randomness:
    args.add("--randomseed=0");
    args.add("--deterministic=false");

    // Logging, notifications, and troubleshooting Randoop:
    args.add("--progressdisplay=false");
    args.add("--debug-checks=false");
    // args.add("--log=<filename>");
    // args.add("--operation-history-log=<filename>");

    // Threading
    args.add("--usethreads=false");
    args.add("--call-timeout=5000");

    if (extraParameters != null) {
      args.add(extraParameters);
    }

    return args;
  }

    private static List<URL> loadProjectDependencies(final MavenProject project) throws MojoExecutionException {
        final List<URL> urls = new LinkedList<>();
        try {
            for (Artifact artifact : project.getArtifacts()) {
                urls.add(artifact.getFile().toURI().toURL());
            }
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Could not add artifact!", e);
        }
        return urls;
    }

    private static URL[] convert(final Collection<URL> urls) {
        return urls.toArray(new URL[urls.size()]);
    }

    private static URL createUrlFrom(final String path) throws MalformedURLException {
        return new File(path).toURI().toURL();
    }

    private void loadRandoopJar(List<URL> urls) {
        if (randoopPath != null) {
            File randoopJar = new File(randoopPath);
            if (randoopJar.exists() && randoopJar.isFile()) {
                URL randoopUrl;
                try {
                    randoopUrl = randoopJar.toURI().toURL();
                    urls.add(randoopUrl);
                } catch (MalformedURLException e) {
                    getLog().error("Could not get an url", e);
                }

            } else {
                getLog().error("randoop file is not found  by this path: " + randoopPath);
            }
        }
    }

    private List<Class<?>> getClassesFromPackage(final List<URL> urls) {
        final URLClassLoader classLoader = new URLClassLoader(convert(urls));
        return ClassFinder.find(packageName, classLoader);
    }

    private List<Class<?>> filterClassList(List<Class<?>> classList) {
        List<Class<?>> resultList = new ArrayList<>();
        for (Class<?> clazz : classList) {
            if (clazz.isInterface()) {
                getLog().info(clazz.getName() + " removed from queue. Because it is an interface");
                continue;
            }
            resultList.add(clazz);
        }
        return resultList;
    }

    private void runHandling(final List<URL> urls) {
        List<Class<?>> classesFromPackage = getClassesFromPackage(urls);
        List<Class<?>> filteredList = filterClassList(classesFromPackage);
        CountDownLatch countDownLatch = new CountDownLatch(filteredList.size());
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        final List<String> args = buildArgs(urls);
        for (Class<?> clazz : filteredList) {
            executorService.submit(new MavenProcessThread(countDownLatch, args, clazz, project.getBasedir(), timeoutInSeconds));
        }

        getLog().info("countDownLatch.await()");
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            getLog().error("countDownLatch problem  " + e.getMessage(), e);
        } finally {
            executorService.shutdown();
        }
        getLog().info("handling done!");
    }

    private String generateClasspath(final List<URL> urls) {
        return urls.stream()
                .map(u -> {
                    final String firstIllegalCharacter = "/";
                    String path = u.getPath();
                    return path.startsWith(firstIllegalCharacter) ? path.replaceFirst(firstIllegalCharacter, "") : path;
                })
                .collect(Collectors.joining(File.pathSeparator));
    }

    private URL loadPluginJarWithRandoop() {
        return getClass().getProtectionDomain().getCodeSource().getLocation();
    }

    private URL loadProjectClasses() throws MojoExecutionException {
        final URL source;
        try {
            source = createUrlFrom(sourceDirectory);
        } catch (MalformedURLException e) {
            throw new MojoExecutionException("Could not create source path!", e);
        }
        return source;
    }
}
