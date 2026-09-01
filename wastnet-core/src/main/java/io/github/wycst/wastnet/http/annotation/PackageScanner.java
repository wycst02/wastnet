package io.github.wycst.wastnet.http.annotation;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans the classpath for classes within a given package.
 * <p>
 * Supports both directory-based and JAR-based classpath resources.
 *
 * @author wangyc
 */
public class PackageScanner {

    private static final AnnotationFilter FILTER_ACCEPT_ALL = clazz -> true;

    /**
     * Scan the given package and return all class files found.
     * <p>
     * Supports both directory-based and JAR-based classpath resources.
     *
     * @param packageName the dot-separated package name (e.g. "com.example.app")
     * @return all classes found in the package; never {@code null}
     * @throws RuntimeException if classpath enumeration fails
     */
    public static List<Class<?>> scan(String packageName) {
        Set<Class<?>> result = scan(packageName, FILTER_ACCEPT_ALL);
        return new ArrayList<>(result);
    }

    /**
     * Scan the given package and return only classes accepted by the filter.
     * <p>
     * Classes are linked ({@link Class#forName(String, boolean, ClassLoader)}
     * with {@code initialize = false}) but NOT initialized, so static blocks
     * of rejected classes are never triggered.
     *
     * @param packageName the dot-separated package name (e.g. "com.example.app")
     * @param filter      the filter to accept or reject each class
     * @return accepted classes; never {@code null}
     * @throws RuntimeException if classpath enumeration fails
     */
    public static Set<Class<?>> scan(String packageName, AnnotationFilter filter) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        String path = packageName.replace('.', '/');
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = PackageScanner.class.getClassLoader();
            }
            Enumeration<URL> resources = cl.getResources(path);
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                scanResource(resource, packageName, path, classes, filter, cl);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to scan package: " + packageName, e);
        }
        return classes;
    }

    private static void scanResource(URL resource, String packageName, String packagePath,
                                     Set<Class<?>> classes, AnnotationFilter filter, ClassLoader cl) throws Exception {
        String protocol = resource.getProtocol();
        if ("file".equals(protocol)) {
            scanDirectory(new File(resource.toURI()), packageName, classes, filter, cl);
        } else if ("jar".equals(protocol)) {
            scanJarResource(resource, packagePath, classes, filter, cl);
        }
    }

    private static void scanJarResource(URL resource, String packagePath,
                                        Set<Class<?>> classes, AnnotationFilter filter, ClassLoader cl) throws IOException {
        JarURLConnection conn = (JarURLConnection) resource.openConnection();
        try (JarFile jar = conn.getJarFile()) {
            scanJar(jar, packagePath, classes, filter, cl);
        }
    }

    private static void scanJar(JarFile jar, String packagePath,
                                Set<Class<?>> classes, AnnotationFilter filter, ClassLoader cl) {
        String prefix = packagePath + "/";
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith(prefix) && name.endsWith(".class")) {
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(className, false, cl);
                    if (filter.accept(c)) {
                        classes.add(c);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // skip classes that can't be loaded
                }
            }
        }
    }

    private static void scanDirectory(File dir, String packageName,
                                      Set<Class<?>> classes, AnnotationFilter filter, ClassLoader cl) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory()) {
                scanDirectory(file, packageName + "." + name, classes, filter, cl);
            } else if (name.endsWith(".class")) {
                String className = packageName + "." + name.substring(0, name.length() - 6);
                try {
                    Class<?> c = Class.forName(className, false, cl);
                    if (filter.accept(c)) {
                        classes.add(c);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    // skip classes that can't be loaded
                }
            }
        }
    }
}
