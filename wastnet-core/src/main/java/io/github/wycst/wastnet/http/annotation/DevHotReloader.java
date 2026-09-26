package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.log.Log;
import io.github.wycst.wastnet.log.LogFactory;
import io.github.wycst.wastnet.util.Utils;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Dev hot-reload engine, extracted from the core router so the router itself carries no
 * hot-reload logic of its own.
 *
 * <p>On every {@link #reload(Path)} it rebuilds a fresh child ("restart") class loader that points only at
 * the project's compiled output directories, so recompiled {@code .class} files are re-read from disk
 * while library/framework classes stay in the parent loader. A {@link WatchService} watches those
 * directories and triggers a debounced {@link #reload(Path)} when a {@code .class} file changes.
 *
 * <p>Owns all of its live state ({@code devWatcher}, {@code devScheduler}, {@code devPendingReload} and
 * {@code scanClassLoader}); the scan path reads the active loader back through this engine, so the
 * caller carries no hot-reload state of its own.
 */
public class DevHotReloader {

    private static final Log log = LogFactory.getLog(DevHotReloader.class);

    /**
     * Reload action executed when a watched file changes (or a manual reload fires).
     * Supplied by the caller via the constructor; the engine is fully decoupled from any
     * specific handler.
     */
    @FunctionalInterface
    public interface ReloadAction {
        /**
         * @param newClassLoader freshly built restart class loader (project classes re-read from disk)
         * @param trigger       the changed file that triggered the reload, or {@code null} for a manual trigger
         */
        void reload(ClassLoader newClassLoader, Path trigger) throws Throwable;
    }

    private final ReloadAction action;

    volatile boolean hotReloadDisabled;
    volatile ScheduledFuture<?> devPendingReload;
    volatile Boolean devEnvironment;
    // Hot reload debounce window in ms (-Dwastnet.http.hot-reload.debounce, default 1000).
    private final long hotReloadDebounceMillis = Integer.getInteger("wastnet.http.hot-reload.debounce", 1000);
    // Packages whose compiled dirs are never watched, pre-converted to platform relative paths
    // ("com.example.stable" -> "com/example/stable") by setWatchExcludes, so the per-directory
    // check during the tree walk needs no conversion.
    String[] hotReloadWatchExcludes = new String[0];
    // Per-reload console feedback; see hotReload(enabled, log).
    private volatile boolean consoleOnReload = true;
    // Live watcher handles.
    volatile WatchService devWatcher;
    ScheduledExecutorService devScheduler;
    // Active app loader used by the scan path: swapped on every reload, and read back by the
    // caller's scan path through its hotReloader reference.
    volatile ClassLoader scanClassLoader;

    // On-disk config file paths (besides .class) that additionally trigger a reload; null = .class only.
    volatile Set<String> reloadPaths = null;

    // Non-jar classpath dirs except the framework's own (reloading it breaks annotation identity).
    // Constant for the JVM's lifetime, so it is computed once and reused by every reload.
    private static final List<URL> PROJECT_DIRS = Collections.unmodifiableList(computeProjectDirs());

    public DevHotReloader(ReloadAction action) {
        this.action = Objects.requireNonNull(action, "reload action must not be null");
    }

    // ============ API called by the router ============

    /** Enable / disable the watcher (called from the router's hot-reload toggle). */
    void setEnabled(boolean enabled) {
        hotReloadDisabled = !enabled;
        if (!enabled) destroyWatcher();
    }

    /** Turn the per-reload console feedback on / off. */
    void setConsoleOnReload(boolean consoleOnReload) {
        this.consoleOnReload = consoleOnReload;
    }

    /** Configure packages whose compiled dirs are never watched (called from {@code hotReloadWatchExclude}). */
    void setWatchExcludes(String... packageNames) {
        hotReloadWatchExcludes = Arrays.stream(packageNames)
                .filter(Objects::nonNull)
                .map(s -> s.trim().replace('.', File.separatorChar))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    /** Set the on-disk config file paths that additionally trigger a reload. */
    void setReloadTriggerPaths(Set<String> paths) {
        this.reloadPaths = paths;
    }

    /** Whether the current run is a development environment. Result is cached. */
    boolean isDevEnvironment() {
        if (devEnvironment == null) {
            devEnvironment = detectDevEnvironment();
        }
        return devEnvironment;
    }

    /** Start the dev watcher over the project dirs (called from the router's prepare()). */
    void startWatcher() {
        List<URL> dirs = PROJECT_DIRS;
        if (devWatcher != null || hotReloadDisabled || !isDevEnvironment() || dirs.isEmpty()) return;
        WatchService ws;
        try {
            ws = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            log.warn("Dev hot reload disabled: failed to create watch service: {}", e.getMessage());
            return;
        }
        for (URL url : dirs) {
            try {
                Path root = Paths.get(url.toURI());
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (isWatchExcluded(dir, root)) return FileVisitResult.SKIP_SUBTREE;
                        dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException | URISyntaxException e) {
                log.warn("Dev hot reload: failed to watch {}: {}", url, e.getMessage());
            }
        }
        devWatcher = ws;
        devScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wastnet-dev-hotreload");
            t.setDaemon(true);
            return t;
        });
        Thread t = new Thread(this::devWatchLoop, "wastnet-dev-hotreload-watch");
        t.setDaemon(true);
        t.start();
    }

    /** Stop the dev watcher and release its resources. */
    void destroyWatcher() {
        WatchService ws = devWatcher;
        if (ws == null) return;
        synchronized (this) {
            if (devPendingReload != null) {
                devPendingReload.cancel(false);
                devPendingReload = null;
            }
        }
        try {
            ws.close();
        } catch (IOException ignored) {
        }
        Utils.shutdownExecutorService(devScheduler);
        devWatcher = null;
        devScheduler = null;
    }

    /**
     * Rebuild route/bean state from the recompiled project classes. Works by rebuilding a fresh child
     * class loader (the "app loader") that points only at the project's compiled output dirs, so
     * recompiled {@code .class} files are re-read from disk. For development use only.
     * <p>
     * The rebuilt class loader is handed to the configured {@link ReloadAction} supplied via the
     * constructor, e.g. an in-place re-scan or a rebuild-and-swap.
     *
     * @param trigger the changed file that caused this reload, or null when triggered manually
     */
    void reload(Path trigger) {
        if (hotReloadDisabled || !isDevEnvironment()) return;
        Object what = trigger != null ? trigger.getFileName() : "classes";
        reloadLog("[dev] hot reload: {} changed, reloading...", what);
        long start = System.nanoTime();
        ClassLoader old = scanClassLoader;
        ClassLoader fresh = buildAppLoader();
        scanClassLoader = fresh;
        try {
            action.reload(fresh, trigger);
        } catch (Throwable t) {
            log.error("[dev] hot reload failed, retaining previous loader", t);
            if (consoleOnReload) {
                log.console("[dev] hot reload: FAIL {} - {} (kept previous state, see error.log)", what, t);
            }
            scanClassLoader = old;
            if (fresh != getBaseClassLoader()) closeQuietly(fresh);
            return;
        }
        if (old != null && old != getBaseClassLoader()) closeQuietly(old);
        long elapsedMs = (System.nanoTime() - start) / 1000000L;
        reloadLog("[dev] hot reload: OK {} reloaded in {} ms", what, elapsedMs);
    }

    // File log (info) + optional console feedback, sharing one template/args.
    private void reloadLog(String template, Object... args) {
        log.info(template, args);
        if (consoleOnReload) log.console(template, args);
    }

    // ============ internals ============

    /** Build a fresh app class loader over the project's own compiled output directories. */
    ClassLoader buildAppLoader() {
        ClassLoader base = getBaseClassLoader();
        List<URL> dirs = PROJECT_DIRS;
        if (dirs.isEmpty()) return base;
        return new RestartClassLoader(dirs.toArray(new URL[0]), base);
    }

    /** Framework's defining loader (deterministic): parent of the restart loader, never closed on reload. */
    static ClassLoader getBaseClassLoader() {
        return DevHotReloader.class.getClassLoader();
    }

    // Child-first loader over the project's output dirs: project classes are re-read from disk on
    // every reload, library/framework classes are delegated to the parent.
    static final class RestartClassLoader extends URLClassLoader {
        RestartClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                // Take child-first ownership only of classes physically present on our own URLs
                // (the project's output dirs); everything else delegates to the parent loader.
                if (findResource(name.replace('.', '/') + ".class") != null) {
                    try {
                        Class<?> c = findClass(name);
                        if (resolve) resolveClass(c);
                        return c;
                    } catch (ClassNotFoundException ignored) {
                        // not on our classpath after all — fall through to the parent
                    }
                }
                return super.loadClass(name, resolve);
            }
        }
    }

    private static List<URL> computeProjectDirs() {
        List<URL> dirs = new ArrayList<>();
        // Exclude the framework's own dir (reloading it breaks annotation identity).
        // null = "can't tell" -> don't exclude (safe).
        String frameworkPath = null;
        try {
            CodeSource cs = DevHotReloader.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                URL loc = cs.getLocation();
                if (loc != null && "file".equals(loc.getProtocol())) {
                    frameworkPath = normalize(loc);
                }
            }
        } catch (Exception ignored) {
        }
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(Pattern.quote(File.pathSeparator))) {
            File f = new File(entry);
            if (!f.isDirectory()) continue; // skip jars and non-existent entries
            try {
                URL url = f.toURI().toURL();
                String p = normalize(url); // throws on malformed -> outer catch skips the entry
                if (frameworkPath != null && frameworkPath.equals(p)) continue;
                if (!dirs.contains(url)) dirs.add(url);
            } catch (Exception ignored) {
                // malformed classpath entry — skip it
            }
        }
        return dirs;
    }

    // Decode + normalize a file URL path: percent-decoded, separators normalized.
    // Throws on a malformed URL so the caller can skip the entry instead of silently adding a bogus path.
    private static String normalize(URL url) throws URISyntaxException {
        return new File(url.toURI()).getPath();
    }

    static void closeQuietly(ClassLoader cl) {
        if (cl instanceof URLClassLoader) {
            try {
                ((URLClassLoader) cl).close();
            } catch (IOException ignored) {
            }
        }
    }

    // Detect dev by checking the main class is loaded from a directory (file protocol), not a jar.
    private boolean detectDevEnvironment() {
        String mainName = getMainClassName();
        if (mainName == null) return false;
        String resource = mainName.replace('.', '/') + ".class";
        URL url = getBaseClassLoader().getResource(resource);
        return url != null && "file".equals(url.getProtocol());
    }

    /**
     * Resolve the application entry (main) class name on a best-effort basis: prefer the
     * {@code sun.java.command} system property, falling back to scanning the current thread's
     * stack for a {@code main} frame. Returns null when it cannot be determined.
     */
    static String getMainClassName() {
        String cmd = System.getProperty("sun.java.command");
        if (cmd != null) {
            int sp = cmd.indexOf(' ');
            String className = (sp > 0 ? cmd.substring(0, sp) : cmd).trim();
            if (!className.isEmpty() && !className.endsWith(".jar")) {
                return className;
            }
        }
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = stack.length - 1; i >= 0; i--) {
            if ("main".equals(stack[i].getMethodName())) {
                return stack[i].getClassName();
            }
        }
        return null;
    }

    // True when dir (relative to the watched root) sits under a configured watch-exclude package.
    boolean isWatchExcluded(Path dir, Path root) {
        String[] excludes = hotReloadWatchExcludes;
        if (excludes.length == 0) return false;
        String rel = root.relativize(dir).toString();
        for (String ex : excludes) {
            if (rel.equals(ex) || rel.startsWith(ex + File.separatorChar)) return true;
        }
        return false;
    }

    /** Blocking watch loop: on a .class change, schedule a debounced {@link #reload(Path)}. */
    void devWatchLoop() {
        WatchService ws = devWatcher;
        while (ws != null && devWatcher == ws) {
            WatchKey key;
            try {
                key = ws.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
            if (key == null) break;
            Path changed = null;
            for (WatchEvent<?> event : key.pollEvents()) {
                // context() is a Path for ENTRY_*; it is null for OVERFLOW events, so the guard is required.
                if (event.context() instanceof Path) {
                    Path p = (Path) event.context();
                    String fn = p.getFileName().toString();
                    if (fn.endsWith(".class")) {
                        changed = p;
                        break;
                    }
                    Path watchable = (Path) key.watchable();
                    Path full = watchable.resolve(p).toAbsolutePath().normalize();
                    if (reloadPaths != null && reloadPaths.contains(full.toString())) {
                        changed = p;
                        break;
                    }
                }
            }
            key.reset();
            if (changed != null) scheduleReload(changed);
        }
    }

    /**
     * Arm (or re-arm) the debounced reload, cancelling any pending one.
     *
     * @param trigger the changed file that caused this reload, or null
     */
    void scheduleReload(Path trigger) {
        if (devScheduler == null || devScheduler.isShutdown()) return;
        synchronized (this) {
            if (devPendingReload != null) devPendingReload.cancel(false);
            devPendingReload = devScheduler.schedule(() -> {
                devPendingReload = null;
                reload(trigger);
            }, hotReloadDebounceMillis, TimeUnit.MILLISECONDS);
        }
    }
}
