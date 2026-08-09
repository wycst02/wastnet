package io.github.wycst.wastnet.http.annotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thorough coverage for PackageScanner.
 */
public class PackageScannerTest {

    // ================ Public API tests ================

    @Test
    public void testScanAll() {
        List<Class<?>> classes = PackageScanner.scan("io.github.wycst.wastnet.http.annotation");
        assertTrue(classes.size() >= 22);
        assertTrue(classes.contains(AnnotationFilter.class));
    }

    @Test
    public void testScanWithFilter() {
        Set<Class<?>> result = PackageScanner.scan("io.github.wycst.wastnet.http.annotation",
                clazz -> AnnotationFilter.class.isAssignableFrom(clazz));
        assertTrue(result.contains(AnnotationFilter.class));
        assertTrue(result.contains(AnnotationResolver.class));
    }

    @Test
    public void testScanInvalidPackage() {
        assertTrue(PackageScanner.scan("nonexistent.pkg", c -> true).isEmpty());
    }

    @Test
    public void testScanEmptyPackageName() {
        Set<Class<?>> result = PackageScanner.scan("", c -> true);
        assertNotNull(result);
    }

    @Test
    public void testScanFilterRejectAll() {
        Set<Class<?>> result = PackageScanner.scan("io.github.wycst.wastnet.http.annotation", clazz -> false);
        assertTrue(result.isEmpty());
    }

    // ================ JAR protocol coverage ================

    /** Create a JAR from target/test-classes and scan its contents via jar: protocol */
    @Test
    public void testScanJarProtocol(@TempDir File tmpDir) throws Exception {
        // Locate a compiled .class file to put into JAR
        String cls = "io/github/wycst/wastnet/http/annotation/AnnotationFilter.class";
        URL classUrl = getClass().getClassLoader().getResource(cls);
        assertNotNull(classUrl, "Test class file not found: " + cls);

        // Read the .class bytes
        java.io.InputStream in = classUrl.openStream();
        byte[] classBytes = new byte[in.available()];
        in.read(classBytes);
        in.close();

        // Create JAR file
        File jarFile = new File(tmpDir, "test-ann.jar");
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile));
        jos.putNextEntry(new JarEntry(cls));
        jos.write(classBytes);
        jos.closeEntry();
        jos.close();

        // Create URLClassLoader pointing to the JAR
        URLClassLoader jarLoader = new URLClassLoader(
                new URL[]{jarFile.toURI().toURL()},
                getClass().getClassLoader());

        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(jarLoader);

            // Scan the package from JAR - this triggers "jar" protocol in scanResource
            Set<Class<?>> result = PackageScanner.scan(
                    "io.github.wycst.wastnet.http.annotation",
                    clazz -> clazz == AnnotationFilter.class);
            assertTrue(result.contains(AnnotationFilter.class));
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
            jarLoader.close();
        }
    }

    // ================ Private method coverage via reflection ================

    /** Test scanDirectory with null listFiles (file not a directory) */
    @Test
    public void testScanDirectoryNullFiles(@TempDir File tmpDir) throws Exception {
        Method scanDir = PackageScanner.class.getDeclaredMethod("scanDirectory",
                File.class, String.class, Set.class, AnnotationFilter.class, ClassLoader.class);
        scanDir.setAccessible(true);

        File notADir = new File(tmpDir, "file.txt");
        assertTrue(notADir.createNewFile());

        Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        scanDir.invoke(null, notADir, "test.pkg", classes,
                (AnnotationFilter) c -> true, getClass().getClassLoader());
        assertTrue(classes.isEmpty());
    }

    /** Test scanDirectory with subdirectory (recursion) */
    @Test
    public void testScanDirectoryWithSubdir(@TempDir File tmpDir) throws Exception {
        Method scanDir = PackageScanner.class.getDeclaredMethod("scanDirectory",
                File.class, String.class, Set.class, AnnotationFilter.class, ClassLoader.class);
        scanDir.setAccessible(true);

        File subDir = new File(tmpDir, "subpkg");
        assertTrue(subDir.mkdir());
        File classFile = new File(subDir, "Dummy.class");
        try (FileOutputStream fos = new FileOutputStream(classFile)) {
            fos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        }

        Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        scanDir.invoke(null, tmpDir, "test", classes,
                (AnnotationFilter) c -> true, getClass().getClassLoader());
        assertTrue(classes.isEmpty());
    }

    /** Test ClassNotFoundException is caught silently */
    @Test
    public void testScanDirectoryClassNotFound(@TempDir File tmpDir) throws Exception {
        Method scanDir = PackageScanner.class.getDeclaredMethod("scanDirectory",
                File.class, String.class, Set.class, AnnotationFilter.class, ClassLoader.class);
        scanDir.setAccessible(true);

        File badClass = new File(tmpDir, "BadClass.class");
        try (FileOutputStream fos = new FileOutputStream(badClass)) {
            fos.write(new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00});
        }

        Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        scanDir.invoke(null, tmpDir, "test", classes,
                (AnnotationFilter) c -> true, getClass().getClassLoader());
        assertTrue(classes.isEmpty());
    }

    /** Test scanJar(JarFile, ...) entry iteration via reflection */
    @Test
    public void testScanJarEntries(@TempDir File tmpDir) throws Exception {
        String entryPath = "io/github/wycst/wastnet/http/annotation/AnnotationFilter.class";
        java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(entryPath);
        assertNotNull(in);
        byte[] buf = new byte[in.available()];
        in.read(buf); in.close();

        File jarFile = new File(tmpDir, "test.jar");
        java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jarFile));
        jos.putNextEntry(new java.util.jar.JarEntry(entryPath));
        jos.write(buf); jos.closeEntry(); jos.close();

        // Directly test the scanJar(JarFile, String, Set, AnnotationFilter, ClassLoader) method
        Method scanJarFile = PackageScanner.class.getDeclaredMethod("scanJar",
                java.util.jar.JarFile.class, String.class, Set.class, AnnotationFilter.class, ClassLoader.class);
        scanJarFile.setAccessible(true);

        java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile);
        Set<Class<?>> classes = new LinkedHashSet<Class<?>>();
        scanJarFile.invoke(null, jar,
                "io/github/wycst/wastnet/http/annotation",
                classes, (AnnotationFilter) c -> true, getClass().getClassLoader());
        jar.close();
        assertTrue(classes.contains(AnnotationFilter.class));
    }



    /** Test catch branch when getResources throws IOException */
    @Test
    public void testScanWithFailingClassLoader() {
        ClassLoader failing = new ClassLoader() {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("simulated failure");
            }
        };
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(failing);
            assertThrows(RuntimeException.class,
                    () -> PackageScanner.scan("any.pkg", c -> true));
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    /** Test ClassLoader fallback branch (cl == null) */
    @Test
    public void testScanWithNullClassLoader() {
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            List<Class<?>> classes = PackageScanner.scan("io.github.wycst.wastnet.http.annotation");
            assertFalse(classes.isEmpty());
            assertTrue(classes.contains(AnnotationFilter.class));
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }
}
