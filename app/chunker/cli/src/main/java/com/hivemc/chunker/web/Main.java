package com.hivemc.chunker.web;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the local application.
 * <p>
 * Everything this tool needs sits next to the jar: two BlueMap builds for the before/after viewers, and a folder
 * for converted worlds. The intent is that a user can copy the folder anywhere, double-click, and be looking at a
 * comparison a minute later - no installer, no service, no configuration.
 */
public class Main {
    private static final int FIRST_PORT = 8123;
    private static final int LAST_PORT = 8133;

    /**
     * Start the tool.
     *
     * @param args pass "cli" to use the original command-line converter instead of the interface.
     * @throws Exception if the application could not start.
     */
    public static void main(String[] args) throws Exception {
        // The command line path stays available: it is what makes the converter scriptable, and it is how the
        // behaviour was verified before any interface existed.
        if (args.length > 0 && args[0].equals("cli")) {
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            com.hivemc.chunker.cli.CLI.main(rest);
            return;
        }

        Path home = locateHome();
        Path blueMapDirectory = home.resolve("bluemap");
        Path workDirectory = home.resolve("work");
        Files.createDirectories(workDirectory);

        Path javaExecutable = locateJava();

        LocalApp app = new LocalApp(workDirectory, blueMapDirectory, javaExecutable);
        int port = startOnFreePort(app);
        if (port == -1) {
            System.err.println("Could not find a free port between " + FIRST_PORT + " and " + LAST_PORT + ".");
            System.exit(1);
        }

        String url = "http://localhost:" + port + "/";
        System.out.println("Map downgrade tool is running.");
        System.out.println("Open " + url + " in your browser.");
        System.out.println("Close this window to stop.");

        openBrowser(url);

        // Keep the process alive for as long as the window is open. Nothing else holds a non-daemon thread, and
        // the viewers exit with us anyway.
        Runtime.getRuntime().addShutdownHook(new Thread(app::stopViewers));
        Thread.currentThread().join();
    }

    /**
     * Try the candidate ports in turn, so a second copy of the tool does not fight the first.
     */
    private static int startOnFreePort(LocalApp app) {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try {
                app.start(port);
                return port;
            } catch (IOException ignored) {
                // Port is taken; try the next one.
            }
        }
        return -1;
    }

    /**
     * Work out where the tool lives.
     * <p>
     * When run from a jar, that is the folder holding the jar so the BlueMap builds travel with it. When run from
     * a build directory during development there is no jar, so the working directory is used instead.
     */
    private static Path locateHome() {
        try {
            Path location = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) {
                Path parent = location.getParent();
                if (parent != null) return parent;
            }
        } catch (Exception ignored) {
            // Fall through to the working directory.
        }
        return Paths.get("").toAbsolutePath();
    }

    /**
     * Find a Java 21 runtime to launch BlueMap with.
     * <p>
     * Both BlueMap builds run on Java 21, which is also what this tool requires, so the runtime running this code
     * is by definition suitable. Using it rather than searching the system keeps the packaged version to a single
     * bundled runtime and avoids depending on whatever {@code java} happens to be on the PATH.
     */
    private static Path locateJava() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";

        // Prefer a runtime shipped alongside the tool. That is what makes the packaged copy self-contained: it runs
        // on the bundled runtime no matter what, or whether anything, is installed on the machine.
        Path bundled = locateHome().resolve("runtime").resolve("bin").resolve(executable);
        if (Files.isExecutable(bundled)) return bundled;

        // Both BlueMap builds run on Java 21, which is also what this tool requires, so the runtime running this
        // code is by definition suitable. Using it rather than searching the system avoids depending on whatever
        // java happens to be on the PATH.
        Path fromHome = Paths.get(System.getProperty("java.home"), "bin", executable);
        if (Files.isExecutable(fromHome)) return fromHome;
        return Paths.get(executable);
    }

    /**
     * Open the interface in the user's browser, if this machine has one.
     */
    private static void openBrowser(String url) {
        // Allow the browser to be suppressed, which is what tests and scripted runs want.
        if (Boolean.getBoolean("chunker.noBrowser")) return;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // A headless or restricted environment simply means the user opens the URL themselves.
        }
        System.out.println("Could not open a browser automatically; please open the address above manually.");
    }
}
