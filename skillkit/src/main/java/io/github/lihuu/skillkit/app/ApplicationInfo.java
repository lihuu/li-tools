package io.github.lihuu.skillkit.app;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record ApplicationInfo(@NotNull String name, @NotNull String version) {
    public static @NotNull ApplicationInfo load() {
        Properties properties = new Properties();
        try (InputStream input = ApplicationInfo.class.getResourceAsStream("/application.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing application.properties");
            }
            properties.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read application.properties", failure);
        }
        String name = properties.getProperty("app.name");
        String version = properties.getProperty("app.version");
        if (name == null || name.isBlank() || version == null || version.isBlank()
                || name.contains("${") || version.contains("${")) {
            throw new IllegalStateException("Application metadata must be Maven-filtered");
        }
        return new ApplicationInfo(name, version);
    }
}
