package io.github.lihuu.skillkit.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SimpleInstallCommandsTest {
    @TempDir Path temporary;

    @Test
    void exposesOnlyTheFourDailyCommands() {
        Result help = run("--help");
        Set<String> commands = Pattern.compile("(?m)^  ([a-z][a-z-]*)\\s{2,}").matcher(help.out())
                .results().map(match -> match.group(1)).collect(Collectors.toSet());
        assertEquals(Set.of("install", "update", "uninstall", "list"), commands);
        for (String command : List.of("import", "track", "untrack", "init", "create", "catalog", "find", "use", "check", "list-available", "add")) {
            assertEquals(2, run(command).code(), command);
        }
    }

    @Test
    void installsOwnSkillGloballyForAllSupportedAgentsWithoutSetup() throws IOException {
        local("mine", "my instructions");
        Result result = run("install", "mine");
        assertEquals(0, result.code(), result.err());
        for (String root : List.of(".agents/skills", ".claude/skills", ".qwen/skills", ".cursor/skills",
                ".gemini/skills", ".hermes/skills", ".config/opencode/skills", ".gemini/antigravity/skills")) {
            assertEquals("my instructions", Files.readString(home().resolve(root).resolve("mine/SKILL.md")), root);
        }
        assertEquals("my instructions", Files.readString(repository().resolve("skills/mine/SKILL.md")));
    }

    @Test
    void sameInstallCommandHandlesExternalSourceAndRecordsMetadataAutomatically() throws IOException {
        Path upstream = temporary.resolve("upstream/skills/external");
        Files.createDirectories(upstream);
        Files.writeString(upstream.resolve("SKILL.md"), "first");
        Result result = run("--repository", repository().toString(), "install", "external", "--repo", temporary.resolve("upstream").toString());
        assertEquals(0, result.code(), result.err());
        assertTrue(Files.readString(repository().resolve("skillkit.json")).contains("external"));
        assertFalse(Files.exists(repository().resolve("skills/external")));
        Files.writeString(upstream.resolve("SKILL.md"), "next");
        assertEquals(0, run("update").code());
        assertEquals("next", Files.readString(home().resolve(".agents/skills/external/SKILL.md")));
        assertEquals("next", Files.readString(home().resolve(".claude/skills/external/SKILL.md")));
    }

    @Test
    void uninstallRemovesGlobalCopiesButPreservesOwnOriginal() throws IOException {
        local("mine", "original");
        assertEquals(0, run("install", "mine").code());
        assertTrue(run("list", "--json").out().contains("mine"));
        assertEquals(0, run("uninstall", "mine").code());
        assertFalse(Files.exists(home().resolve(".agents/skills/mine")));
        assertFalse(Files.exists(home().resolve(".claude/skills/mine")));
        assertEquals("original", Files.readString(repository().resolve("skills/mine/SKILL.md")));
    }

    @Test
    void dryRunRequiresNoAgentSelectionAndCreatesNothing() {
        Result result = run("--repository", repository().toString(), "install", "example", "--repo", "owner/not-fetched", "--dry-run");
        assertEquals(0, result.code(), result.err());
        assertFalse(Files.exists(home()));
        assertFalse(Files.exists(repository()));
    }

    @Test
    void installHelpDoesNotRequireStorageOrScopeChoices() {
        Result result = run("install", "--help");
        assertEquals(0, result.code());
        for (String removed : List.of("--agent", "--project", "--target-dir", "--global")) {
            assertFalse(result.out().contains(removed), removed);
        }
    }

    @Test
    void discoversCategorizedOwnSkillsAndRejectsAmbiguousNames() throws IOException {
        Path first = repository().resolve("skills/category/example");
        Files.createDirectories(first);
        Files.writeString(first.resolve("SKILL.md"), "first");
        assertEquals(0, run("install", "example").code());
        Path second = repository().resolve("skills/another/example");
        Files.createDirectories(second);
        Files.writeString(second.resolve("SKILL.md"), "second");
        assertEquals(1, run("install", "example").code());
        assertEquals("first", Files.readString(home().resolve(".agents/skills/example/SKILL.md")));
    }

    @Test
    void movedRepositoryKeepsLocalExternalSourcePortable() throws IOException {
        Path upstream = temporary.resolve("upstream/skills/portable");
        Files.createDirectories(upstream);
        Files.writeString(upstream.resolve("SKILL.md"), "portable");
        assertEquals(0, run("--repository", repository().toString(), "install", "portable", "--repo", temporary.resolve("upstream").toString()).code());
        Path moved = temporary.resolve("moved");
        Files.createDirectories(moved);
        Files.move(repository(), moved.resolve("repository"));
        Files.move(temporary.resolve("upstream"), moved.resolve("upstream"));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Path newHome = temporary.resolve("new-home");
        int code = CliApplication.run(new String[]{"install", "portable"}, newHome, moved.resolve("repository"),
                new PrintWriter(out, true), new PrintWriter(err, true), Map.of(), false);
        assertEquals(0, code, err.toString());
        assertEquals("portable", Files.readString(newHome.resolve(".agents/skills/portable/SKILL.md")));
    }

    @Test
    void rejectsTraversalAndLocalDirectoryRefWithoutInstalling() throws IOException {
        local("mine", "original");
        assertEquals(2, run("install", "../escape").code());
        Path source = temporary.resolve("external/skills/foreign");
        Files.createDirectories(source);
        Files.writeString(source.resolve("SKILL.md"), "foreign");
        Result result = run("install", "foreign", "--repo", temporary.resolve("external").toString(), "--ref", "some-branch");
        assertEquals(1, result.code());
        assertFalse(Files.exists(home().resolve(".agents/skills/foreign")));
        assertEquals("original", Files.readString(repository().resolve("skills/mine/SKILL.md")));
    }

    private void local(String name, String content) throws IOException {
        Path directory = repository().resolve("skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
    }

    private Path home() { return temporary.resolve("home"); }
    private Path repository() { return temporary.resolve("repository"); }

    private Result run(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(arguments, home(), repository(), new PrintWriter(out, true),
                new PrintWriter(err, true), Map.of(), false);
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}
}
