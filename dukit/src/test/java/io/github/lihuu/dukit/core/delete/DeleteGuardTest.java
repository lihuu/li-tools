package io.github.lihuu.dukit.core.delete;

import io.github.lihuu.dukit.core.trash.TrashStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DeleteGuardTest {
    @TempDir Path temporary;

    @Test
    void acceptsADirectoryInsideTheTree() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path target = Files.createDirectory(root.resolve("target"));

        assertDoesNotThrow(() -> DeleteGuard.validate(root, target));
        assertNull(DeleteGuard.explain(root, target));
    }

    @Test
    void refusesTheRoot() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));

        DeleteException refusal = assertThrows(DeleteException.class, () -> DeleteGuard.validate(root, root));
        assertEquals(DeleteException.Reason.ROOT, refusal.reason());
    }

    @Test
    void refusesPathsOutsideTheTree() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));

        DeleteException refusal = assertThrows(DeleteException.class, () -> DeleteGuard.validate(root, outside));
        assertEquals(DeleteException.Reason.NOT_IN_TREE, refusal.reason());
    }

    @Test
    void refusesSymbolicLinks() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path real = Files.createDirectory(root.resolve("real"));
        Path link = Files.createSymbolicLink(root.resolve("link"), real);

        DeleteException refusal = assertThrows(DeleteException.class, () -> DeleteGuard.validate(root, link));
        assertEquals(DeleteException.Reason.SYMBOLIC_LINK, refusal.reason());
    }

    @Test
    void refusesRegularFiles() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path file = Files.writeString(root.resolve("file.txt"), "x");

        DeleteException refusal = assertThrows(DeleteException.class, () -> DeleteGuard.validate(root, file));
        assertEquals(DeleteException.Reason.NOT_A_DIRECTORY, refusal.reason());
    }

    @Test
    void refusesAMissingTarget() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));

        DeleteException refusal = assertThrows(DeleteException.class,
                () -> DeleteGuard.validate(root, root.resolve("gone")));
        assertEquals(DeleteException.Reason.NOT_FOUND, refusal.reason());
    }

    @Test
    void refusesWhenTheTrashLivesInsideTheTarget() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path trashRoot = Files.createDirectory(target.resolve(".Trash"));
        Path destination = trashRoot.resolve("files").resolve("item");

        DeleteException refusal = assertThrows(DeleteException.class,
                () -> DeleteGuard.validateTrashRelation(target,
                        new TrashStore.TrashTarget(target, destination, trashRoot, trashRoot.toString())));
        assertEquals(DeleteException.Reason.CONTAINS_TRASH, refusal.reason(),
                "moving a directory into a Trash that sits inside it would consume it");
    }

    @Test
    void refusesWhenTheTargetIsInsideTheTrash() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path trash = Files.createDirectory(temporary.resolve("Trash"));
        Path target = Files.createDirectory(trash.resolve("item"));

        DeleteException refusal = assertThrows(DeleteException.class,
                () -> DeleteGuard.validateTrashRelation(target,
                        new TrashStore.TrashTarget(target, trash.resolve("other"), trash, trash.toString())));
        assertEquals(DeleteException.Reason.IS_TRASH, refusal.reason());
    }
}
