package io.github.wycst.wastnet.http.annotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thorough for PackageScanner.
 */
public class PackageScannerTest {

    // ================ Public API tests ================

    @Test
    public void testScanAll() {
        Set<Class<?>> classes = PackageScanner.scan(c -> true, Thread.currentThread().getContextClassLoader(), "io.github.wycst.wastnet.http.annotation");
        assertTrue(classes.size() >= 22);
        assertTrue(classes.contains(AnnotationFilter.class));
    }

    @Test
    public void testScanWithFilter() {
        Set<Class<?>> result = PackageScanner.scan(clazz -> AnnotationFilter.class.isAssignableFrom(clazz),
                Thread.currentThread().getContextClassLoader(),
                "io.github.wycst.wastnet.http.annotation");
        assertTrue(result.contains(AnnotationFilter.class));
        assertTrue(result.contains(AnnotationResolver.class));
    }

    @Test
    public void testScanInvalidPackage() {
        assertTrue(PackageScanner.scan(c -> true, Thread.currentThread().getContextClassLoader(), "nonexistent.pkg").isEmpty());
    }

    @Test
    public void testScanEmptyPackageName() {
        Set<Class<?>> result = PackageScanner.scan(c -> true, Thread.currentThread().getContextClassLoader(), "");
        assertNotNull(result);
    }

    @Test
    public void testScanFilterRejectAll() {
        Set<Class<?>> result = PackageScanner.scan(clazz -> false, Thread.currentThread().getContextClassLoader(), "io.github.wycst.wastnet.http.annotation");
        assertTrue(result.isEmpty());
    }

    // ================ Convenience overload (context class loader) ================

    /** The convenience overload (no explicit loader) scans with the current context class loader. */
    @Test
    public void testScanConvenienceUsesContextClassLoader() {
        Set<Class<?>> classes = PackageScanner.scan(c -> true, "io.github.wycst.wastnet.http.annotation");
        assertTrue(classes.size() >= 22);
        assertTrue(classes.contains(AnnotationFilter.class));
    }

    /** When the context class loader is null, the convenience overload falls back to the defining loader. */
    @Test
    public void testScanConvenienceFallsBackWhenContextNull() {
        ClassLoader orig = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            Set<Class<?>> classes = PackageScanner.scan(c -> true, "io.github.wycst.wastnet.http.annotation");
            assertTrue(classes.contains(AnnotationFilter.class));
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
        }
    }

    // ================ JAR protocol ================

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

            // Scan the package from JAR - this triggers "jar" protocol in scanJarRoot
            Set<Class<?>> result = PackageScanner.scan(
                    clazz -> clazz == AnnotationFilter.class, jarLoader,
                    "io.github.wycst.wastnet.http.annotation");
            assertTrue(result.contains(AnnotationFilter.class));
        } finally {
            Thread.currentThread().setContextClassLoader(orig);
            jarLoader.close();
        }
    }



    /** Test catch branch when getResources throws IOException (a real class loader is supplied). */
    @Test
    public void testScanWithFailingClassLoader() {
        ClassLoader failing = new ClassLoader() {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("simulated failure");
            }
        };
        assertThrows(RuntimeException.class,
                () -> PackageScanner.scan(c -> true, failing, "any.pkg"));
    }

    /** A null class loader is a programming error: the framework always supplies a non-null loader, so a raw NPE surfaces. */
    @Test
    public void testScanWithNullClassLoader() {
        assertThrows(NullPointerException.class,
                () -> PackageScanner.scan(c -> true, (ClassLoader) null, "io.github.wycst.wastnet.http.annotation"));
    }
}
