package io.github.lihuu.clicommon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeSecurityConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void pruningPreservesTheBuildJdksSecurityPolicy() throws Exception {
        String baseline = run(new ProcessBuilder(java(), "-cp", classpath(), NativeSecurityProbe.class.getName()));
        Path config = root().resolve("scripts/pruned-security.properties");
        String pruned = run(new ProcessBuilder(java(), "-Djava.security.properties==" + config,
                "-cp", classpath(), NativeSecurityProbe.class.getName(), "pruned"));
        assertEquals(baseline, pruned, "Provider pruning must preserve the JDK's security policy");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void nativeBuildScriptsPreserveSecurityArgumentsInPathsWithSpaces() throws Exception {
        Path checkout = Files.createDirectories(tempDir.resolve("checkout with spaces"));
        Path scripts = Files.createDirectories(checkout.resolve("scripts"));
        Files.copy(root().resolve("scripts/pruned-security.properties"), scripts.resolve("pruned-security.properties"));
        Path bin = Files.createDirectories(tempDir.resolve("bin"));
        Files.writeString(bin.resolve("mvn"), "#!/bin/sh\nexit 0\n");
        Files.writeString(bin.resolve("native-image"), """
                #!/bin/sh
                set -eu
                security_arg=
                output=
                while [ "$#" -gt 0 ]; do
                    case "$1" in
                        -J-Djava.security.properties==*) security_arg=${1#-J} ;;
                        -o) shift; output=$1 ;;
                    esac
                    shift
                done
                test -n "$security_arg"
                if [ ! -f "${security_arg#-Djava.security.properties==}" ]; then
                    echo "Security configuration argument was split" >&2
                    exit 1
                fi
                "$TEST_JAVA" "$security_arg" -cp "$TEST_CLASSPATH" io.github.lihuu.clicommon.NativeSecurityProbe pruned
                mkdir -p target
                : > "$output"
                """);
        bin.resolve("mvn").toFile().setExecutable(true);
        bin.resolve("native-image").toFile().setExecutable(true);
        for (String tool : List.of("certkit", "gpgkit", "tokenkit")) {
            Path module = Files.createDirectories(checkout.resolve(tool));
            Files.copy(root().resolve(tool + "/build-native.sh"), module.resolve("build-native.sh"));
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", module.resolve("build-native.sh").toString());
            Map<String, String> environment = builder.environment();
            environment.put("PATH", bin + ":" + environment.get("PATH"));
            environment.put("TEST_JAVA", java());
            environment.put("TEST_CLASSPATH", classpath());
            run(builder);
        }
    }

    private static Path root() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    private static String java() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static String run(ProcessBuilder builder) throws IOException, InterruptedException {
        Process process = builder.redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
