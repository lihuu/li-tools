package io.github.lihuu.skillkit.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.lihuu.skillkit.core.SourceResolver;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstallationCommandsTest {
    @TempDir Path temporary;

    @Test
    void installsGloballyAndUpdatesOnlyAlreadyInstalledSkills() throws IOException {
        Path alpha = local("alpha", "one");
        local("unused", "do not install");
        Result installed = run("install", "alpha", "--jobs", "2");
        assertEquals(0, installed.code(), installed.err());
        assertEquals("one", Files.readString(home().resolve(".agents/skills/alpha/SKILL.md")));
        assertEquals("one", Files.readString(home().resolve(".claude/skills/alpha/SKILL.md")));
        Files.writeString(alpha.resolve("SKILL.md"), "two");
        assertTrue(run("list").out().contains("alpha"));
        assertEquals(0, run("update", "--jobs", "2").code());
        assertEquals("two", Files.readString(home().resolve(".agents/skills/alpha/SKILL.md")));
        assertEquals("two", Files.readString(home().resolve(".claude/skills/alpha/SKILL.md")));
        assertFalse(Files.exists(home().resolve(".agents/skills/unused")));
        assertTrue(run("list", "--json").out().contains("alpha"));
    }

    @Test
    void uninstallRemovesAllGlobalCopiesAndKeepsOriginalContent() throws IOException {
        local("alpha", "original");
        assertEquals(0, run("install", "alpha").code());
        assertEquals(0, run("uninstall", "alpha").code());
        assertFalse(Files.exists(home().resolve(".agents/skills/alpha")));
        assertFalse(Files.exists(home().resolve(".claude/skills/alpha")));
        assertTrue(Files.exists(repository().resolve("skills/alpha/SKILL.md")));
    }

    @Test
    void symlinkPointsToLocalOriginalAndRemovalDeletesOnlyTheLink() throws IOException {
        Path original = local("linked", "one");
        Result installed = run("install", "linked", "--symlink");
        assertEquals(0, installed.code(), installed.err());
        Path link = home().resolve(".agents/skills/linked");
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(original.toRealPath(), link.toRealPath());
        Files.writeString(original.resolve("SKILL.md"), "edited");
        assertEquals("edited", Files.readString(link.resolve("SKILL.md")));
        assertEquals(0, run("uninstall", "linked").code());
        assertTrue(Files.isRegularFile(original.resolve("SKILL.md")));
    }

    @Test
    void refusesUnmanagedOrModifiedInstalledFiles() throws IOException {
        local("alpha", "source");
        Path target = home().resolve(".agents/skills/alpha");
        Files.createDirectories(target);
        Files.writeString(target.resolve("SKILL.md"), "unmanaged");
        assertEquals(1, run("install", "alpha").code());
        assertEquals("unmanaged", Files.readString(target.resolve("SKILL.md")));
        Files.delete(target.resolve("SKILL.md"));
        Files.delete(target);
        assertEquals(0, run("install", "alpha").code());
        Files.writeString(target.resolve("SKILL.md"), "locally edited");
        assertEquals(1, run("update").code());
        assertEquals(1, run("uninstall", "alpha").code());
        assertEquals("locally edited", Files.readString(target.resolve("SKILL.md")));
    }

    @Test
    void dryRunDoesNotCreateRepositoryCacheOrTargets() {
        Result result = run("install", "alpha", "--repo", "some-owner/not-fetched", "--dry-run");
        assertEquals(0, result.code(), result.err());
        assertFalse(Files.exists(repository()));
        assertFalse(Files.exists(home()));
        assertFalse(Files.exists(project()));
    }

    @Test
    void partialFailureReportsFailureButKeepsSuccessfulInstall() throws IOException {
        local("valid", "good");
        Result result = run("install", "valid", "missing", "--jobs", "2", "--json");
        assertEquals(1, result.code());
        assertTrue(Files.isRegularFile(home().resolve(".agents/skills/valid/SKILL.md")));
        assertTrue(result.out().contains("missing"));
        assertTrue(result.out().contains("failed"));
        assertFalse(Files.exists(home().resolve(".agents/skills/missing")));
    }

    @Test
    void directSourceBatchContinuesAfterMissingSkill() throws IOException {
        Path source = temporary.resolve("external/skills/good");
        Files.createDirectories(source);
        Files.writeString(source.resolve("SKILL.md"), "good content");
        Result result = run("install", "missing", "good", "--repo", temporary.resolve("external").toString(),
                "--jobs", "2", "--json");
        assertEquals(1, result.code());
        assertTrue(result.out().contains("failed"));
        assertEquals("good content", Files.readString(home().resolve(".agents/skills/good/SKILL.md")));
        assertTrue(Files.readString(repository().resolve("skillkit.json")).contains("good"));
        assertFalse(Files.readString(repository().resolve("skillkit.json")).contains("missing"));
    }

    @Test
    void singleSkillLocalGitRootIgnoresGitMetadataWhenPreparingContent() throws IOException {
        Path source = temporary.resolve("one-skill");
        Files.createDirectories(source.resolve(".git"));
        Files.writeString(source.resolve("SKILL.md"), "single skill");
        Files.writeString(source.resolve(".git/synthetic-test-metadata"), "not skill content");
        Result result = run("install", "one-skill", "--repo", source.toString());
        assertEquals(0, result.code(), result.err());
        assertEquals("single skill", Files.readString(home().resolve(".agents/skills/one-skill/SKILL.md")));
        assertFalse(Files.exists(home().resolve(".agents/skills/one-skill/.git")));
    }

    @Test
    void sourceFailureIsSharedWithinOperationButRetriedByNextOperation() throws IOException, InterruptedException {
        Path remote = temporary.resolve("initially-missing-git");
        Path cache = temporary.resolve("source-cache");
        SourceResolver firstOperation = new SourceResolver(cache, temporary.resolve("git-cache"));
        String source = remote.toUri().toString();
        assertThrows(IOException.class, () -> firstOperation.resolve(source, "main"));
        Files.createDirectories(remote.resolve("skills/example"));
        git(remote, "init", "-b", "main");
        Files.writeString(remote.resolve("skills/example/SKILL.md"), "newly available");
        git(remote, "add", "skills");
        git(remote, "commit", "-m", "first");
        assertThrows(IOException.class, () -> firstOperation.resolve(source, "main"));
        assertTrue(Files.isRegularFile(new SourceResolver(cache, temporary.resolve("git-cache")).resolve(source, "main").root().resolve("skills/example/SKILL.md")));
    }

    @Test
    void rejectsInvalidJobsBeforeAnyWrites() throws IOException {
        local("alpha", "one");
        assertEquals(2, run("install", "alpha", "--jobs", "0").code());
        assertFalse(Files.exists(home()));
    }

    @Test
    void trackedUpstreamInstallsAndUpdatesWithoutRepositoryContent() throws IOException {
        Path upstream = temporary.resolve("upstream/skills/external");
        Files.createDirectories(upstream);
        Files.writeString(upstream.resolve("SKILL.md"), "first");
        Result installed = run("install", "external", "--repo", temporary.resolve("upstream").toString());
        assertEquals(0, installed.code(), installed.err());
        assertFalse(Files.exists(repository().resolve("skills/external")));
        assertTrue(Files.readString(repository().resolve("skillkit.json")).contains("external"));
        String firstMetadata = Files.readString(repository().resolve("skillkit.json"));
        Files.writeString(upstream.resolve("SKILL.md"), "next");
        assertEquals(0, run("update").code());
        assertEquals("next", Files.readString(home().resolve(".agents/skills/external/SKILL.md")));
        assertNotEquals(firstMetadata, Files.readString(repository().resolve("skillkit.json")));
        assertFalse(Files.exists(repository().resolve("skills/external")));
    }

    @Test
    void listDoesNotInitializeEmptyTargets() {
        assertEquals(0, run("list").code());
        assertFalse(Files.exists(home()));
        assertFalse(Files.exists(repository()));
    }

    @Test
    void fallbackRepositoryRecordsSourceAndLinksToCacheUnderSkillkitHome() throws IOException, InterruptedException {
        Path remote = temporary.resolve("git-source");
        Files.createDirectories(remote.resolve("skills/external"));
        Files.writeString(remote.resolve("skills/external/SKILL.md"), "cached content");
        git(remote, "init", "-b", "main");
        git(remote, "add", "skills");
        git(remote, "commit", "-m", "first");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(new String[]{"install", "external", "--repo", remote.toUri().toString(),
                        "--ref", "main", "--symlink"}, home(), temporary,
                new PrintWriter(out, true), new PrintWriter(err, true), Map.of(), false);
        assertEquals(0, code, err.toString());
        assertTrue(Files.readString(home().resolve(".skillkit/repository/skillkit.json")).contains("external"));
        Path installed = home().resolve(".agents/skills/external");
        assertEquals("cached content", Files.readString(installed.resolve("SKILL.md")));
        assertTrue(Files.isDirectory(home().resolve(".skillkit/cache")), "Downloads must stay under .skillkit/cache");
        assertTrue(installed.toRealPath().startsWith(home().resolve(".skillkit/cache").toRealPath()));
        assertTrue(Files.isDirectory(home().resolve(".skillkit/cache/git")));
        assertTrue(Files.isDirectory(home().resolve(".skillkit/cache/staging")));
        assertFalse(Files.exists(home().resolve(".cache/skillkit")));
        assertFalse(Files.exists(home().resolve(".skillkit/repository/skills/external")));
    }

    @Test
    void gitSourceTracksBranchUpdatesAndSymlinkUsesVersionedSnapshot() throws IOException, InterruptedException {
        Path remote = temporary.resolve("git-upstream");
        Files.createDirectories(remote.resolve("skills/external"));
        git(remote, "init", "-b", "main");
        Files.writeString(remote.resolve("skills/external/SKILL.md"), "commit one");
        git(remote, "add", "skills");
        git(remote, "commit", "-m", "first");
        String source = remote.toUri().toString();
        Result first = run("install", "external", "--repo", source, "--ref", "main", "--symlink");
        assertEquals(0, first.code(), first.err());
        Path link = home().resolve(".agents/skills/external");
        Path firstSnapshot = link.toRealPath();
        Files.writeString(remote.resolve("skills/external/SKILL.md"), "commit two");
        git(remote, "add", "skills");
        git(remote, "commit", "-m", "second");
        Result update = run("update");
        assertEquals(0, update.code(), update.err());
        assertEquals("commit two", Files.readString(link.resolve("SKILL.md")));
        assertEquals("commit one", Files.readString(firstSnapshot.resolve("SKILL.md")));
        assertNotEquals(firstSnapshot, link.toRealPath());
    }

    @Test
    void unrelatedRepositoryLinkDoesNotPreventInstallingRegularSkill() throws IOException, InterruptedException {
        Path remote = temporary.resolve("repository-with-link");
        Files.createDirectories(remote.resolve("skills/example"));
        git(remote, "init", "-b", "main");
        Files.writeString(remote.resolve("skills/example/SKILL.md"), "regular skill");
        Files.writeString(remote.resolve("README.md"), "repository documentation");
        Files.createSymbolicLink(remote.resolve("README-link"), Path.of("README.md"));
        git(remote, "add", ".");
        git(remote, "commit", "-m", "skill with unrelated link");
        Result result = run("install", "example", "--repo", remote.toUri().toString(), "--ref", "main");
        assertEquals(0, result.code(), result.err());
        assertEquals("regular skill", Files.readString(home().resolve(".agents/skills/example/SKILL.md")));
    }

    @Test
    void gitSourceWorksWhenCachePathsExceedWindowsLegacyLimit() throws IOException, InterruptedException {
        Path remote = temporary.resolve("long-path-upstream");
        Files.createDirectories(remote.resolve("skills/example"));
        git(remote, "init", "-b", "main");
        Files.writeString(remote.resolve("skills/example/SKILL.md"), "long cache content");
        git(remote, "add", "skills");
        git(remote, "commit", "-m", "first");
        Path cache = temporary.resolve("long-cache");
        while (cache.toAbsolutePath().toString().length() < 270) cache = cache.resolve("cache-segment-0123456789");
        SourceResolver.Snapshot snapshot = new SourceResolver(cache, temporary.resolve("git-cache")).resolve(remote.toUri().toString(), "main");
        assertEquals("long cache content", Files.readString(snapshot.root().resolve("skills/example/SKILL.md")));
    }

    private void git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Skillkit Test", "-c", "user.email=skillkit@example.invalid",
                "-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + temporary.resolve("no-hooks")));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private Path local(String name, String content) throws IOException {
        Path directory = repository().resolve("skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
        return directory;
    }

    private Path home() { return temporary.resolve("home"); }
    private Path project() { return temporary.resolve("project"); }
    private Path repository() { return temporary.resolve("repository"); }

    private Result run(String... arguments) {
        List<String> args = new ArrayList<>(List.of("--repository", repository().toString()));
        args.addAll(List.of(arguments));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(args.toArray(String[]::new), home(), project(), new PrintWriter(out, true),
                new PrintWriter(err, true), Map.of(), false);
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}
}
