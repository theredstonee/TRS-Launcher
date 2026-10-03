/*
 * TRS Launcher – Startklasse der iOS-Engine.
 *
 * Vorbereitung wie in Amethyst-iOS PojavLauncher.java / Tools.launchMinecraft
 * (GPL-3.0, Copyright (C) 2021 Tran Hoang Khanh Duy und Mitwirkende); der Klassenpfad
 * und die Spielargumente kommen fertig vom TRS-Kern.
 *
 * Aufruf: java ... -Dtrs.boot.classpath=<a.jar:b.jar> dev.theredstonee.trs.ios.TrsBoot <Hauptklasse> <Spielargumente...>
 *
 * Copyright (C) 2026 Ohev Tamerin (Theredstonee) – GPL-3.0-only
 */
package dev.theredstonee.trs.ios;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class TrsBoot {
    private TrsBoot() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length < 1) {
            throw new IllegalArgumentException("TrsBoot: Hauptklasse fehlt");
        }
        // Ohne macOS-Anwendungsdelegaten starten; manche Stellen nehmen sonst macOS-Code.
        java.beans.Beans.setDesignTime(true);
        try {
            Class<?> app = Class.forName("com.apple.eawt.Application");
            app.getMethod("getApplication").invoke(null);
            Field instance = app.getDeclaredField("sApplication");
            instance.setAccessible(true);
            instance.set(null, null);
            Field linux = Class.forName("sun.font.FontUtilities").getDeclaredField("isLinux");
            linux.setAccessible(true);
            linux.setBoolean(null, true);
            System.setProperty("java.util.prefs.PreferencesFactory", "java.util.prefs.FileSystemPreferencesFactory");
        } catch (Throwable ignored) {
            // Nicht Java 8 – nichts zu tun.
        }

        Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                e.printStackTrace();
                System.exit(1);
            }
        });

        try {
            // Caciocavallo17 vorbereiten (nur Java 17+ vorhanden).
            Class.forName("com.github.caciocavallosilano.cacio.ctc.CTCPreloadClassLoader");
        } catch (ClassNotFoundException ignored) {
        }

        String size = System.getProperty("cacio.managed.screensize");
        if (size != null) {
            System.setProperty("glfw.windowSize", size);
        }
        System.setProperty("org.lwjgl.vulkan.libname", "libMoltenVK.dylib");
        disableForgeSplash();

        ClassLoader loader = gameLoader(ClassLoader.getSystemClassLoader());
        Thread.currentThread().setContextClassLoader(loader);

        Class<?> mainClass = loader.loadClass(args[0]);
        Method main = mainClass.getMethod("main", String[].class);
        String[] gameArgs = Arrays.copyOfRange(args, 1, args.length);
        System.out.println("[TRS] Starte " + args[0]);
        try {
            main.invoke(null, (Object) gameArgs);
        } catch (InvocationTargetException e) {
            throw e.getCause() != null ? e.getCause() : e;
        }
    }

    /**
     * Wie Tools.launchMinecraft: Engine-Jars und Spiel-Klassenpfad in den PojavClassLoader
     * (System-Klassenlader). Ohne ihn (Test am PC) ein eigener URLClassLoader.
     */
    private static ClassLoader gameLoader(ClassLoader loader) throws Exception {
        Method addUrl;
        try {
            addUrl = loader.getClass().getMethod("addURL", URL.class);
        } catch (NoSuchMethodException e) {
            java.util.List<URL> urls = new java.util.ArrayList<URL>();
            for (File file : gameClasspath()) {
                urls.add(file.toURI().toURL());
            }
            return new java.net.URLClassLoader(urls.toArray(new URL[0]), loader);
        }
        Method append = null;
        try {
            append = loader.getClass().getDeclaredMethod("appendToClassPathForInstrumentation", String.class);
            append.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }
        if (append != null) {
            synchronized (loader) {
                for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                    if (!entry.isEmpty()) {
                        append.invoke(loader, entry);
                    }
                }
            }
        }
        for (File file : gameClasspath()) {
            addUrl.invoke(loader, file.toURI().toURL());
        }
        return loader;
    }

    private static java.util.List<File> gameClasspath() {
        java.util.List<File> files = new java.util.ArrayList<File>();
        for (String entry : System.getProperty("trs.boot.classpath", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            File file = new File(entry);
            if (file.exists()) {
                files.add(file);
            } else {
                System.out.println("[TRS] Fehlt im Klassenpfad: " + entry);
            }
        }
        return files;
    }

    /** Forge-Ladebildschirm (eigenes Fenster) abschalten, wie Amethyst. */
    private static void disableForgeSplash() {
        if (System.getProperty("pojav.internal.keepForgeSplash") != null) {
            return;
        }
        File config = new File(System.getProperty("user.dir"), "config");
        File splash = new File(config, "splash.properties");
        try {
            if (!config.isDirectory() && !config.mkdirs()) {
                return;
            }
            String text = "enabled=false\n";
            if (splash.isFile()) {
                byte[] old = java.nio.file.Files.readAllBytes(splash.toPath());
                text = new String(old, StandardCharsets.UTF_8).replace("enabled=true", "enabled=false");
            }
            try (OutputStream out = new FileOutputStream(splash)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            System.out.println("[TRS] splash.properties: " + e);
        }
    }
}
