package io.github.lihuu.skillkit.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryCommandsTest {
    @TempDir Path temporary;

    @Test
    void initializesAndCreatesEditableLocalSkill() throws IOException {
        assertEquals(0, execute("init").code());
        assertTrue(Files.isRegularFile(repository().resolve("skillkit.json")));
        assertEquals(0, execute("create", "my-skill").code());
        Path content = repository().resolve("skills/my-skill/SKILL.md");
        assertTrue(Files.readString(content).contains("name: my-skill"));
        Files.writeString(content, "my own instructions");
        assertTrue(execute("catalog").out().contains("my-skill"));
        assertEquals("my own instructions", execute("use", "my-skill").out().strip());
    }

    @Test
    void importCopiesWholeDirectoryWithoutUpstreamTracking() throws IOException {
        Path source = skill("external/skills/tools/copied", "version one");
        Files.writeString(source.resolve("helper.txt"), "resource");
        assertEquals(0, execute("import", "copied", "--source", temporary.resolve("external").toString()).code());
        Path local = repository().resolve("skills/copied");
        assertEquals("version one", Files.readString(local.resolve("SKILL.md")));
        assertEquals("resource", Files.readString(local.resolve("helper.txt")));
        Files.writeString(source.resolve("SKILL.md"), "upstream changed");
        assertEquals("version one", execute("use", "copied").out().strip());
        assertFalse(Files.readString(repository().resolve("skillkit.json")).contains("copied"));
    }

    @Test
    void trackStoresMetadataAndFollowsSourceWithoutVendoringContent() throws IOException {
        Path source = skill("upstream/skills/followed", "upstream one");
        Result tracked = execute("track", "followed", "--source", temporary.resolve("upstream").toString());
        assertEquals(0, tracked.code(), tracked.err());
        assertFalse(Files.exists(repository().resolve("skills/followed")));
        String manifest = Files.readString(repository().resolve("skillkit.json"));
        assertTrue(manifest.contains("followed"));
        assertTrue(manifest.contains("skills/followed"));
        Files.writeString(source.resolve("SKILL.md"), "upstream two");
        assertEquals("upstream two", execute("use", "followed").out().strip());
        assertTrue(execute("catalog", "--json").out().contains("upstream"));
    }

    @Test
    void discoversExistingCategorizedRepositoryAndRejectsAmbiguousNames() throws IOException {
        skill("repository/skills/category/alpha", "alpha instructions");
        skill("repository/skills/other/beta", "beta instructions");
        assertTrue(execute("find", "alpha").out().contains("alpha"));
        assertFalse(Files.exists(repository().resolve("skillkit.json")));
        skill("repository/skills/another/alpha", "different alpha");
        Result ambiguous = execute("use", "alpha");
        assertEquals(2, ambiguous.code());
        assertTrue(ambiguous.err().contains("alpha"));
    }

    @Test
    void rejectsContentSubscriptionCollisionAndTraversalWithoutOverwriting() throws IOException {
        skill("repository/skills/keep", "original");
        skill("external/skills/keep", "external");
        assertEquals(2, execute("track", "keep", "--source", temporary.resolve("external").toString()).code());
        assertEquals(2, execute("import", "keep", "--source", temporary.resolve("external").toString()).code());
        assertEquals(2, execute("create", "../escape").code());
        assertEquals("original", Files.readString(repository().resolve("skills/keep/SKILL.md")));
        assertFalse(Files.exists(repository().resolve("escape")));
    }

    @Test
    void dryRunDoesNotInitializeRepositoryOrCopyContent() throws IOException {
        skill("external/skills/example", "example");
        Result result = execute("import", "example", "--source", temporary.resolve("external").toString(), "--dry-run");
        assertEquals(0, result.code(), result.err());
        assertFalse(Files.exists(repository()));
    }

    @Test
    void localDirectoryCannotSilentlyIgnoreRequestedGitRef() throws IOException {
        skill("external/skills/example", "example");
        Result result = execute("track", "example", "--source", temporary.resolve("external").toString(), "--ref", "some-branch");
        assertEquals(2, result.code());
        assertFalse(Files.exists(repository().resolve("skillkit.json")));
    }

    private Path repository() { return temporary.resolve("repository"); }

    private Path skill(String relative, String content) throws IOException {
        Path directory = temporary.resolve(relative);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), content);
        return directory;
    }

    private Result execute(String... arguments) {
        List<String> args = new ArrayList<>(List.of("--repository", repository().toString()));
        args.addAll(List.of(arguments));
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliApplication.run(args.toArray(String[]::new), new PrintWriter(out, true),
                new PrintWriter(err, true), Map.of(), false);
        return new Result(code, out.toString(), err.toString());
    }

    private record Result(int code, String out, String err) {}
}
