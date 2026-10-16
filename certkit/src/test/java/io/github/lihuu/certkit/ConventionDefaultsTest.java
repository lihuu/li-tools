package io.github.lihuu.certkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConventionDefaultsTest {

    @Test
    void sniffHostsCorrectlyClassifiesIpAndDomains() {
        var split = Defaults.splitHosts(List.of("192.168.1.1", "api.local", "10.0.0.1", "dev.mycompany.org"));
        assertEquals(List.of("api.local", "dev.mycompany.org"), split.domains());
        assertEquals(List.of("192.168.1.1", "10.0.0.1"), split.ips());
    }

    @Test
    void defaultGenProducesLocalhostAnd127001(@TempDir Path tempDir) throws Exception {
        // Run certkit gen with ZERO host arguments
        int exit = Main.run("gen", "--out", tempDir.toString(), "--self-signed");
        assertEquals(0, exit);

        Path cert = tempDir.resolve("cert.pem");
        assertTrue(Files.exists(cert));
        CertInfo info = CertParser.parse(cert);

        assertEquals("localhost", info.subjectCn());
        assertTrue(info.sans().contains("DNS:localhost"));
        assertTrue(info.sans().contains("IP:127.0.0.1"));
    }
}
