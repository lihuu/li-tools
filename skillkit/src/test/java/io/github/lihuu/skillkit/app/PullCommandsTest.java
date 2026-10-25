package io.github.lihuu.skillkit.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PullCommandsTest {
    @TempDir Path temporary;

    @Test
    void firstPullClonesPersonalRepositoryThenInstallsWithoutSourceAndPushesBack() throws IOException, InterruptedException {
        Path source = source();
        Result pulled = run("pull", "--repo", source.toUri().toString(), "--json");
        assertEquals(0, pulled.code(), pulled.err());
        assertTrue(pulled.out().contains("cloned"));
        assertEquals("one", Files.readString(repository().resolve("skills/mine/SKILL.md")));
        assertTrue(Files.isDirectory(repository().resolve(".git")));
        assertEquals(source.toUri().toString(), git(repository(), "remote", "get-url", "origin"));
        assertFalse(Files.exists(home().resolve(".agents")));
        assertEquals(0, run("install", "mine").code());
        assertEquals("one", Files.readString(home().resolve(".agents/skills/mine/SKILL.md")));
        Path destination = temporary.resolve("published.git");
        git(temporary, "init", "--bare", "-b", "main", destination.toString());
        Files.writeString(repository().resolve("skills/mine/SKILL.md"), "my changes");
        assertEquals(0, run("push", "--repo", destination.toUri().toString()).code());
        assertEquals("my changes", git(destination, "show", "main:skills/mine/SKILL.md"));
    }

    @Test
    void laterPullFastForwardsPersonalContentAndMetadataButDoesNotInstall() throws IOException, InterruptedException {
        Path source = source();
        assertEquals(0, run("pull", "--repo", source.toUri().toString()).code());
        assertEquals(0, run("install", "mine").code());
        Files.writeString(source.resolve("skills/mine/SKILL.md"), "two");
        Files.writeString(source.resolve("skillkit.json"), "{\"version\":1,\"upstreams\":[]}");
        commit(source, "two");
        Result pulled = run("pull");
        assertEquals(0, pulled.code(), pulled.err());
        assertEquals(git(source, "rev-parse", "HEAD"), git(repository(), "rev-parse", "HEAD"));
        assertTrue(Files.readString(repository().resolve("skillkit.json")).contains("upstreams"));
        assertEquals("one", Files.readString(home().resolve(".agents/skills/mine/SKILL.md")));
        assertEquals(0, run("update").code());
        assertEquals("two", Files.readString(home().resolve(".agents/skills/mine/SKILL.md")));
    }

    @Test
    void refusesDirtyRepositoryAndPreservesLocalContent() throws IOException, InterruptedException {
        Path source = source();
        assertEquals(0, run("pull", "--repo", source.toUri().toString()).code());
        String revision = git(repository(), "rev-parse", "HEAD");
        Files.writeString(repository().resolve("skills/mine/SKILL.md"), "local draft");
        Files.writeString(source.resolve("skills/mine/SKILL.md"), "remote change");
        commit(source, "remote");
        Result pulled = run("pull");
        assertEquals(1, pulled.code(), pulled.err());
        assertTrue(pulled.err().contains("local changes"), pulled.err());
        assertEquals(revision, git(repository(), "rev-parse", "HEAD"));
        assertEquals("local draft", Files.readString(repository().resolve("skills/mine/SKILL.md")));
    }

    @Test
    void refusesToOverwriteIgnoredLocalFilesDuringFastForward() throws IOException, InterruptedException {
        Path source = source();
        Files.writeString(source.resolve(".gitignore"), "local.txt\n");
        git(source, "add", ".gitignore");
        git(source, "commit", "-m", "ignore local drafts");
        assertEquals(0, run("pull", "--repo", source.toUri().toString()).code());
        String revision = git(repository(), "rev-parse", "HEAD");
        Files.writeString(repository().resolve("local.txt"), "local draft");
        Files.writeString(source.resolve("local.txt"), "remote content");
        git(source, "add", "-f", "local.txt");
        git(source, "commit", "-m", "track conflicting path");
        Result pulled = run("pull");
        assertEquals(1, pulled.code(), pulled.err());
        assertEquals("local draft", Files.readString(repository().resolve("local.txt")));
        assertEquals(revision, git(repository(), "rev-parse", "HEAD"));
    }

    @Test
    void refusesDivergedHistoryWithoutCreatingMergeOrDiscardingCommits() throws IOException, InterruptedException {
        Path source = source();
        assertEquals(0, run("pull", "--repo", source.toUri().toString()).code());
        Files.writeString(repository().resolve("skills/mine/SKILL.md"), "local commit");
        commit(repository(), "local");
        String revision = git(repository(), "rev-parse", "HEAD");
        Files.writeString(source.resolve("skills/mine/SKILL.md"), "remote commit");
        commit(source, "remote");
        assertEquals(1, run("pull").code());
        assertEquals(revision, git(repository(), "rev-parse", "HEAD"));
        assertEquals("local commit", Files.readString(repository().resolve("skills/mine/SKILL.md")));
    }

    @Test
    void refusesToCloneOverExistingPersonalContentOrChangeAnExistingOrigin() throws IOException, InterruptedException {
        Path source = source();
        Files.createDirectories(repository().resolve("skills/mine"));
        Files.writeString(repository().resolve("skills/mine/SKILL.md"), "existing personal skill");
        assertEquals(2, run("pull", "--repo", source.toUri().toString()).code());
        assertEquals("existing personal skill", Files.readString(repository().resolve("skills/mine/SKILL.md")));
        assertFalse(Files.exists(repository().resolve(".git")));
        Files.delete(repository().resolve("skills/mine/SKILL.md"));
        Files.delete(repository().resolve("skills/mine"));
        Files.delete(repository().resolve("skills"));
        assertEquals(0, run("pull", "--repo", source.toUri().toString()).code());
        assertEquals(2, run("pull", "--repo", "other/repository").code());
        assertEquals(source.toUri().toString(), git(repository(), "remote", "get-url", "origin"));
    }

    @Test
    void dryRunAndMissingInitialSourceCreateNothing() {
        assertEquals(2, run("pull").code());
        assertEquals(2, run("pull", "--repo", " ").code());
        Result dryRun = run("pull", "--repo", "owner/not-fetched", "--dry-run", "--json");
        assertEquals(0, dryRun.code(), dryRun.err());
        assertTrue(dryRun.out().contains("planned"));
        assertFalse(Files.exists(home()));
    }

    @Test
    void cloneFailureLeavesNoPartialRepositoryContentAndAllowsRetry() throws IOException, InterruptedException {
        Result failed = run("pull", "--repo", temporary.resolve("missing.git").toUri().toString());
        assertEquals(1, failed.code());
        assertFalse(Files.exists(repository().resolve(".git")));
        Path source = source();
        Result retry = run("pull", "--repo", source.toUri().toString());
        assertEquals(0, retry.code(), retry.err());
        assertEquals("one", Files.readString(repository().resolve("skills/mine/SKILL.md")));
    }

    private Path source() throws IOException, InterruptedException {
        Path source = temporary.resolve("source");
        Files.createDirectories(source.resolve("skills/mine"));
        Files.writeString(source.resolve("skills/mine/SKILL.md"), "one");
        git(source, "init", "-b", "main");
        commit(source, "one");
        return source;
    }
    private void commit(Path directory, String message) throws IOException, InterruptedException {
        git(directory, "add", "--", "skills");
        if (Files.exists(directory.resolve("skillkit.json"))) git(directory, "add", "--", "skillkit.json");
        git(directory, "commit", "-m", message);
    }
    private Path home() { return temporary.resolve("home"); }
    private Path repository() { return home().resolve(".skillkit/repository"); }
    private Map<String, String> environment() {
        return Map.ofEntries(Map.entry("GIT_AUTHOR_NAME", "Skillkit Test"), Map.entry("GIT_AUTHOR_EMAIL", "skillkit@example.invalid"),
                Map.entry("GIT_COMMITTER_NAME", "Skillkit Test"), Map.entry("GIT_COMMITTER_EMAIL", "skillkit@example.invalid"),
                Map.entry("GIT_CONFIG_GLOBAL", temporary.resolve("no-global-config").toString()), Map.entry("GIT_CONFIG_NOSYSTEM", "1"),
                Map.entry("GIT_CONFIG_COUNT", "2"), Map.entry("GIT_CONFIG_KEY_0", "commit.gpgSign"), Map.entry("GIT_CONFIG_VALUE_0", "false"),
                Map.entry("GIT_CONFIG_KEY_1", "core.hooksPath"), Map.entry("GIT_CONFIG_VALUE_1", temporary.resolve("no-hooks").toString()));
    }
    private String git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        return output;
    }
    private Result run(String... arguments) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(arguments, home(), temporary, new PrintWriter(out, true),
                new PrintWriter(err, true), environment(), false);
        return new Result(code, out.toString(), err.toString());
    }
    private record Result(int code, String out, String err) {}
}
