package randoop.maven;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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

    @Parameter(required = true,
            defaultValue = "${project.build.directory}/generated-test-sources/java")
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

    @Override
    public void execute() throws MojoExecutionException {

        // Collect class and jars for class path
        final List<URL> urls = new LinkedList<>();
        loadRandoopJar(urls);
        urls.add(loadProjectClasses());
        urls.addAll(loadProjectDependencies(project));
        urls.add(loadPluginJarWithRandoop());
        runHandling(urls);
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
        args.add("--time-limit=" + timeoutInSeconds);
        args.add("--debug-checks=true");
        args.add("--junit-output-dir=" + targetDirectory);

        if (extraParameters != null) {
            args.add(extraParameters);
        }

        return args;
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
