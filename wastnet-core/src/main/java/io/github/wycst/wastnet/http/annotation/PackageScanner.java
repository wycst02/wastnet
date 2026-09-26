package io.github.wycst.wastnet.http.annotation;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans the classpath for classes within the given packages.
 *
 * <p>A two-pass strategy is used: {@code getResources("")} walks directory roots, while
 * {@code getResources(packagePrefix)} reaches classes inside {@code jar:} elements. Each physical JAR is enumerated once
 * and deduplicated by path; matched classes are resolved with {@code forName(.., false, ..)} so static
 * initializers are not run while scanning.
 *
 * <p>Two entry points: {@link #scan(AnnotationFilter, String...)} uses the context loader;
 * {@link #scan(AnnotationFilter, ClassLoader, String...)} takes an explicit loader (honours hot-reload
 * class loaders used by {@code AnnotationRouterHandler}).
 *
 * @author wangyc
 */
public class PackageScanner {

    private PackageScanner() {}

    /**
     * Convenience entry: scan the given packages using the context class loader.
     *
     * @param filter       the filter to accept or reject each class
     * @param packageNames the packages to scan; the last (varargs) argument
     * @return accepted classes; never {@code null}
     */
    public static Set<Class<?>> scan(AnnotationFilter filter, String... packageNames) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = PackageScanner.class.getClassLoader();
        }
        return scan(filter, cl, packageNames);
    }

    /**
     * Scan the given packages using an explicit class loader, in a single classpath traversal.
     *
     * <p>Classes in the default package (directly under a classpath root) are never collected, since they
     * match no scanned package prefix; an empty package name therefore yields no classes.
     *
     * @param filter       the filter to accept or reject each class
     * @param cl           the class loader to scan with (must not be {@code null})
     * @param packageNames the packages to scan; the last (varargs) argument
     * @return accepted classes; never {@code null}
     */
    public static Set<Class<?>> scan(AnnotationFilter filter, ClassLoader cl, String... packageNames) {
        Set<Class<?>> classes = new HashSet<>();
        // Prefixes derived from packageNames, slash-separated with a trailing slash (e.g. "io/github/foo/").
        Set<String> prefixes = new LinkedHashSet<>();
        for (String pkg : packageNames) {
            if (pkg != null && !pkg.isEmpty()) {
                prefixes.add(pkg.replace('.', '/') + "/");
            }
        }
        // Physical jar paths already scanned; each jar is opened at most once even when several
        // packages live inside it.
        Set<String> scannedJars = new HashSet<>();
        try {
            // Pass 1: scan directory classpath roots from getResources(""); jar: roots are covered by pass 2.
            Enumeration<URL> roots = cl.getResources("");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    File file = new File(root.toURI());
                    if (file.isDirectory()) {
                        scanDirectoryRoot(file, "", prefixes, classes, filter, cl);
                    }
                }
            }
            // Pass 2: per-package getResources(prefix) scans each jar: element (deduped by physical path); directories ignored (covered by pass 1).
            for (String prefix : prefixes) {
                Enumeration<URL> resources = cl.getResources(prefix);
                while (resources.hasMoreElements()) {
                    URL url = resources.nextElement();
                    if ("jar".equals(url.getProtocol())) {
                        scanJarRoot(url, prefixes, classes, filter, cl, scannedJars);
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("Failed to scan packages: " + Arrays.toString(packageNames), e);
        }
        return classes;
    }

    // Open a jar: URL classpath element once (deduped by its physical path) and enumerate it
    // against the package prefixes.
    private static void scanJarRoot(URL root, Set<String> prefixes,
                                    Set<Class<?>> classes, AnnotationFilter filter, ClassLoader cl,
                                    Set<String> scannedJars) {
        try {
            JarURLConnection conn = (JarURLConnection) root.openConnection();
            try (JarFile jar = conn.getJarFile()) {
                if (scannedJars.add(jar.getName())) {
                    scanJar(jar, prefixes, classes, filter, cl);
                }
            }
        } catch (IOException ignore) {
            // skip jars that cannot be opened
        }
    }

    // Enumerate a JAR, keeping .class entries whose path starts with any of the package prefixes.
    static void scanJar(JarFile jar, Set<String> prefixes,
                       Set<Class<?>> classes, AnnotationFilter filter, ClassLoader cl) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.endsWith(".class") && startsWithAny(name, prefixes)) {
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                loadAndAccept(className, classes, filter, cl);
            }
        }
    }

    // Walk a directory classpath root, keeping .class entries whose path starts with any of the
    // package prefixes (relative path uses '/' separators).
    static void scanDirectoryRoot(File dir, String relative, Set<String> prefixes,
                                  Set<Class<?>> classes, AnnotationFilter filter, ClassLoader cl) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String childRel = relative.isEmpty() ? file.getName() : relative + "/" + file.getName();
            if (file.isDirectory()) {
                scanDirectoryRoot(file, childRel, prefixes, classes, filter, cl);
            } else if (file.getName().endsWith(".class") && startsWithAny(childRel, prefixes)) {
                String className = childRel.substring(0, childRel.length() - 6).replace('/', '.');
                loadAndAccept(className, classes, filter, cl);
            }
        }
    }

    // Link a class (without initializing it) and keep it only if the filter accepts it.
    private static void loadAndAccept(String className, Set<Class<?>> classes,
                                      AnnotationFilter filter, ClassLoader cl) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            if (filter.accept(c)) {
                classes.add(c);
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
            // skip classes that can't be loaded
        }
    }

    private static boolean startsWithAny(String name, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
