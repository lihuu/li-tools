package io.github.lihuu.tokenkit.app;

import java.io.InputStream;
import java.util.Properties;

public final class ApplicationInfo {
    private static final String DEFAULT_NAME = "tokenkit";
    private static final String DEFAULT_VERSION = "0.1.0";

    private final String name;
    private final String version;

    public ApplicationInfo(String name, String version) {
        this.name = name;
        this.version = version;
    }

    public static ApplicationInfo load() {
        try (InputStream in = ApplicationInfo.class.getResourceAsStream("/application.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String name = props.getProperty("app.name", DEFAULT_NAME);
                String version = props.getProperty("app.version", DEFAULT_VERSION);
                return new ApplicationInfo(name, version);
            }
        } catch (Exception ignored) {
        }
        return new ApplicationInfo(DEFAULT_NAME, DEFAULT_VERSION);
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }
}
