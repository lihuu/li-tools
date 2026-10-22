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

class PublishCommandsTest {
    @TempDir Path temporary;

    @Test
    void pushInitializesCommitsAndPublishesOwnSkillForAnotherMachine() throws IOException, InterruptedException {
        Path repository = personal();
        write(repository.resolve("skills/mine/SKILL.md"), "own instructions");
        Path remote = bare("distribution.git");
        Result pushed = run(repository, "push", "--repo", remote.toUri().toString(), "--json");
        assertEquals(0, pushed.code(), pushed.err());
        assertTrue(pushed.out().contains("pushed"));
        assertEquals("own instructions", git(remote, "show", "main:skills/mine/SKILL.md"));
        assertEquals("skills/mine/SKILL.md", git(remote, "ls-tree", "-r", "--name-only", "main"));
        Path consumer = temporary.resolve("consumer");
        Result installed = run(consumer, "install", "mine", "--repo", remote.toUri().toString(), "--ref", "main");
        assertEquals(0, installed.code(), installed.err());
        assertEquals("own instructions", Files.readString(home(consumer).resolve(".agents/skills/mine/SKILL.md")));
    }

    @Test
    void pushCommitsManagedChangesAndPreservesUnrelatedStagedWork() throws IOException, InterruptedException {
        Path repository = personal();
        Files.createDirectories(repository);
        git(repository, "init", "-b", "main");
        write(repository.resolve("README.md"), "original README");
        git(repository, "add", "--", "README.md");
        git(repository, "commit", "-m", "initial documentation");
        write(repository.resolve("skills/mine/SKILL.md"), "instructions");
        write(repository.resolve("skillkit.json"), "{\"version\":1,\"upstreams\":[]}");
        write(repository.resolve("README.md"), "staged private draft");
        git(repository, "add", "--", "README.md");
        Path remote = bare("distribution.git");
        Result result = run(repository, "push", "--repo", remote.toUri().toString(), "-m", "publish my skills");
        assertEquals(0, result.code(), result.err());
        assertEquals("original README", git(remote, "show", "main:README.md"));
        assertEquals("README.md", git(repository, "diff", "--cached", "--name-only"));
        assertEquals("staged private draft", Files.readString(repository.resolve("README.md")));
        assertEquals("publish my skills", git(repository, "log", "-1", "--format=%s"));
        assertTrue(git(remote, "show", "main:skillkit.json").contains("upstreams"));
        String revision = git(repository, "rev-parse", "HEAD");
        assertEquals(0, run(repository, "push").code());
        assertEquals(revision, git(repository, "rev-parse", "HEAD"));
    }

    @Test
    void pushPublishesSkillDeletion() throws IOException, InterruptedException {
        Path repository = personal();
        write(repository.resolve("skills/mine/SKILL.md"), "instructions");
        write(repository.resolve("skillkit.json"), "{\"version\":1,\"upstreams\":[]}");
        Path remote = bare("distribution.git");
        assertEquals(0, run(repository, "push", "--repo", remote.toUri().toString()).code());
        Files.delete(repository.resolve("skills/mine/SKILL.md"));
        Files.delete(repository.resolve("skills/mine"));
        assertEquals(0, run(repository, "push").code());
        assertEquals("skillkit.json", git(remote, "ls-tree", "-r", "--name-only", "main"));
    }

    @Test
    void dryRunDoesNotInitializeOrPublish() throws IOException, InterruptedException {
        Path repository = personal();
        write(repository.resolve("skills/mine/SKILL.md"), "instructions");
        Path remote = bare("distribution.git");
        Result result = run(repository, "push", "--repo", remote.toUri().toString(), "--dry-run");
        assertEquals(0, result.code(), result.err());
        assertFalse(Files.exists(repository.resolve(".git")));
        assertFalse(Files.exists(repository.resolve(".skillkit.lock")));
        assertEquals("", git(remote, "for-each-ref"));
    }

    @Test
    void rejectedPushPreservesRemoteAndNewLocalCommitForRetry() throws IOException, InterruptedException {
        Path repository = personal();
        write(repository.resolve("skills/mine/SKILL.md"), "one");
        Path remote = bare("distribution.git");
        assertEquals(0, run(repository, "push", "--repo", remote.toUri().toString()).code());
        Path peer = temporary.resolve("peer");
        git(temporary, "clone", "-b", "main", "--", remote.toString(), peer.toString());
        write(peer.resolve("skills/mine/SKILL.md"), "remote edit");
        git(peer, "add", "--", "skills");
        git(peer, "commit", "-m", "peer edit");
        git(peer, "push", "origin", "main");
        String remoteRevision = git(remote, "rev-parse", "main");
        write(repository.resolve("skills/mine/SKILL.md"), "my edit");
        Result result = run(repository, "push");
        assertEquals(1, result.code());
        assertEquals(remoteRevision, git(remote, "rev-parse", "main"));
        assertEquals("my edit", git(repository, "show", "HEAD:skills/mine/SKILL.md"));
        assertEquals("", git(repository, "status", "--porcelain", "--", "skills"));
    }

    @Test
    void explicitRepositoryReplacesEveryConfiguredPushDestination() throws IOException, InterruptedException {
        Path repository = personal();
        write(repository.resolve("skills/mine/SKILL.md"), "instructions");
        git(repository, "init", "-b", "main");
        Path old = bare("old.git");
        Path other = bare("other.git");
        Path destination = bare("destination.git");
        git(repository, "remote", "add", "origin", old.toUri().toString());
        git(repository, "config", "--add", "remote.origin.pushurl", old.toUri().toString());
        git(repository, "config", "--add", "remote.origin.pushurl", other.toUri().toString());
        Result result = run(repository, "push", "--repo", destination.toUri().toString());
        assertEquals(0, result.code(), result.err());
        assertFalse(git(destination, "for-each-ref").isEmpty());
        assertEquals("", git(old, "for-each-ref"));
        assertEquals("", git(other, "for-each-ref"));
    }

    @Test
    void emptyPublishingRepositoryIsUsageErrorWithoutWrites() throws IOException {
        Path repository = personal();
        write(repository.resolve("skills/mine/SKILL.md"), "instructions");
        assertEquals(2, run(repository, "push", "--repo", "", "--dry-run").code());
        assertFalse(Files.exists(repository.resolve(".git")));
        assertFalse(Files.exists(repository.resolve(".skillkit.lock")));
    }

    @Test
    void publishedMetadataCanBeInstalledAndUpdatedFromPersonalDistribution() throws IOException, InterruptedException {
        Path upstream = temporary.resolve("upstream");
        write(upstream.resolve("skills/external/SKILL.md"), "upstream one");
        git(upstream, "init", "-b", "main");
        git(upstream, "add", "--", "skills");
        git(upstream, "commit", "-m", "upstream one");
        Path repository = personal();
        Result collected = run(repository, "install", "external", "--repo", upstream.toUri().toString(), "--ref", "main");
        assertEquals(0, collected.code(), collected.err());
        Path remote = bare("distribution.git");
        Result pushed = run(repository, "push", "--repo", remote.toUri().toString());
        assertEquals(0, pushed.code(), pushed.err());
        assertEquals("skillkit.json", git(remote, "ls-tree", "-r", "--name-only", "main"));
        Path consumer = temporary.resolve("consumer");
        Result installed = run(consumer, "install", "external", "--repo", remote.toUri().toString(), "--ref", "main");
        assertEquals(0, installed.code(), installed.err());
        assertEquals("upstream one", Files.readString(home(consumer).resolve(".agents/skills/external/SKILL.md")));
        write(upstream.resolve("skills/external/SKILL.md"), "upstream two");
        git(upstream, "add", "--", "skills");
        git(upstream, "commit", "-m", "upstream two");
        Result updated = run(consumer, "update");
        assertEquals(0, updated.code(), updated.err());
        assertEquals("upstream two", Files.readString(home(consumer).resolve(".agents/skills/external/SKILL.md")));
        assertTrue(Files.readString(consumer.resolve("skillkit.json")).contains(remote.toUri().toString()));
    }

    @Test
    void updateFollowsDistributionWhenContentBecomesMetadataAndBack() throws IOException {
        Path distribution = temporary.resolve("distribution");
        Path upstream = temporary.resolve("upstream");
        write(distribution.resolve("skills/example/SKILL.md"), "personal copy");
        write(upstream.resolve("skills/example/SKILL.md"), "upstream content");
        assertEquals(0, run(personal(), "install", "example", "--repo", distribution.toString()).code());
        Files.delete(distribution.resolve("skills/example/SKILL.md"));
        Files.delete(distribution.resolve("skills/example"));
        write(distribution.resolve("skillkit.json"), "{\"version\":1,\"upstreams\":[{\"name\":\"example\",\"source\":\"../upstream\",\"path\":\"skills/example\",\"ref\":\"HEAD\",\"revision\":\"directory\"}]}");
        Result followed = run(personal(), "update");
        assertEquals(0, followed.code(), followed.err());
        assertEquals("upstream content", Files.readString(home(personal()).resolve(".agents/skills/example/SKILL.md")));
        Files.delete(distribution.resolve("skillkit.json"));
        write(distribution.resolve("skills/example/SKILL.md"), "personal content again");
        assertEquals(0, run(personal(), "update").code());
        assertEquals("personal content again", Files.readString(home(personal()).resolve(".agents/skills/example/SKILL.md")));
    }

    @Test
    void distributionDoesNotFollowMetadataSymlinkOutsideItsRoot() throws IOException {
        Path distribution = temporary.resolve("distribution");
        Path upstream = temporary.resolve("upstream");
        write(upstream.resolve("skills/example/SKILL.md"), "outside metadata content");
        Path outside = temporary.resolve("outside.json");
        String source = upstream.toString().replace('\\', '/');
        write(outside, "{\"version\":1,\"upstreams\":[{\"name\":\"example\",\"source\":\"" + source + "\",\"path\":\"skills/example\",\"ref\":\"HEAD\",\"revision\":\"directory\"}]}");
        Files.createDirectories(distribution);
        Files.createSymbolicLink(distribution.resolve("skillkit.json"), outside);
        Result result = run(personal(), "install", "example", "--repo", distribution.toString());
        assertEquals(1, result.code(), result.out());
        assertFalse(Files.exists(home(personal()).resolve(".agents/skills/example")));
    }

    @Test
    void cyclicDistributionMetadataFailsClearly() throws IOException {
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        write(first.resolve("skillkit.json"), "{\"version\":1,\"upstreams\":[{\"name\":\"loop\",\"source\":\"../second\",\"path\":\"skillkit.json\",\"ref\":\"HEAD\",\"revision\":\"directory\"}]}");
        write(second.resolve("skillkit.json"), "{\"version\":1,\"upstreams\":[{\"name\":\"loop\",\"source\":\"../first\",\"path\":\"skillkit.json\",\"ref\":\"HEAD\",\"revision\":\"directory\"}]}");
        Result result = run(personal(), "install", "loop", "--repo", first.toString());
        assertEquals(1, result.code());
        assertTrue(result.err().contains("Cyclic"), result.err());
    }

    private Path personal() { return temporary.resolve("personal"); }
    private Path home(Path repository) { return temporary.resolve(repository.getFileName() + "-home"); }

    private Path bare(String name) throws IOException, InterruptedException {
        Path remote = temporary.resolve(name);
        Files.createDirectories(remote);
        git(remote, "init", "--bare", "-b", "main");
        return remote;
    }

    private void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private Map<String, String> environment() {
        return Map.of("GIT_AUTHOR_NAME", "Skillkit Test", "GIT_AUTHOR_EMAIL", "skillkit@example.invalid",
                "GIT_COMMITTER_NAME", "Skillkit Test", "GIT_COMMITTER_EMAIL", "skillkit@example.invalid",
                "GIT_CONFIG_GLOBAL", temporary.resolve("no-global-config").toString(), "GIT_CONFIG_NOSYSTEM", "1",
                "GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "commit.gpgSign", "GIT_CONFIG_VALUE_0", "false");
    }

    private String git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + temporary.resolve("no-hooks")));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().putAll(environment());
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private Result run(Path repository, String... arguments) {
        List<String> command = new ArrayList<>(List.of("--repository", repository.toString()));
        command.addAll(List.of(arguments));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(command.toArray(String[]::new), home(repository), temporary,
                new PrintWriter(out, true), new PrintWriter(err, true), environment(), false);
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}
}
