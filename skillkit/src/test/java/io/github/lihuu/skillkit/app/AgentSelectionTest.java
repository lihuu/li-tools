package io.github.lihuu.skillkit.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentSelectionTest {
    @TempDir Path temporary;

    @Test
    void defaultInstallWritesSharedDirectoryWithoutCreatingAgentSpecificDirectories() throws IOException {
        skill("original");
        assertEquals(0, run("install", "mine").code());
        assertEquals("original", content(".agents"));
        assertFalse(Files.exists(home().resolve(".claude")));
        assertFalse(Files.exists(home().resolve(".cursor")));
        assertFalse(Files.exists(home().resolve(".hermes")));
        skill("updated");
        assertEquals(0, run("update").code());
        assertEquals("updated", content(".agents"));
        assertFalse(Files.exists(home().resolve(".claude")));
    }

    @Test
    void repeatedAndCommaSeparatedAgentsSelectOnlyTheirDirectories() throws IOException {
        skill("original");
        Result installed = run("install", "mine", "--agent", "claude-code,cursor", "--agent", "hermes");
        assertEquals(0, installed.code(), installed.err());
        for (String agent : new String[]{".claude", ".cursor", ".hermes"}) assertEquals("original", content(agent));
        assertFalse(Files.exists(home().resolve(".agents")));
        assertFalse(Files.exists(home().resolve(".qwen")));
        skill("updated");
        assertEquals(0, run("update", "mine", "--agent", "claude-code").code());
        assertEquals("updated", content(".claude"));
        assertEquals("original", content(".cursor"));
        Result listed = run("list", "--agent", "cursor", "--json");
        assertTrue(listed.out().contains(".cursor"));
        assertFalse(listed.out().contains(".claude"));
        assertEquals(0, run("uninstall", "mine", "--agent", "cursor").code());
        assertFalse(Files.exists(home().resolve(".cursor/skills/mine")));
        assertEquals("updated", content(".claude"));
        assertEquals(0, run("update").code());
        assertEquals("updated", content(".hermes"));
        assertFalse(Files.exists(home().resolve(".agents")));
    }

    @Test
    void sharedAgentAliasesAndOverriddenDirectoriesAreDeduplicated() throws IOException {
        skill("original");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(new String[]{"install", "mine", "--agent", "codex,pi,zcode,claude-code,hermes"},
                home(), repository(), new PrintWriter(out, true), new PrintWriter(err, true),
                Map.of("CLAUDE_CONFIG_DIR", home().resolve("custom").toString(),
                        "HERMES_HOME", home().resolve("custom").toString()), false);
        assertEquals(0, code, err.toString());
        assertEquals(2, out.toString().lines().count());
        assertEquals("original", content("custom"));
        assertEquals("original", content(".agents"));
    }

    @Test
    void unknownAgentIsUsageErrorEvenDuringDryRunAndCreatesNothing() {
        Result result = run("install", "mine", "--agent", "not-an-agent", "--dry-run");
        assertEquals(2, result.code(), result.err());
        assertFalse(Files.exists(home()));
        assertFalse(Files.exists(repository()));
    }

    private void skill(String content) throws IOException {
        Path directory = repository().resolve("skills/mine");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
    }

    private String content(String agent) throws IOException { return Files.readString(home().resolve(agent).resolve("skills/mine/SKILL.md")); }
    private Path home() { return temporary.resolve("home"); }
    private Path repository() { return temporary.resolve("repository"); }
    private Result run(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(arguments, home(), repository(), new PrintWriter(out, true), new PrintWriter(err, true), Map.of(), false);
        return new Result(code, out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) {}
}
