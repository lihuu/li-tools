package io.github.lihuu.dukit.core.trash;

import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Selected when the platform has no supported Trash implementation. It refuses everything
 * rather than degrading to a permanent delete.
 */
public final class UnsupportedTrashStore implements TrashStore {

    @Override
    public @NotNull TrashTarget prepare(@NotNull Path target) throws TrashException {
        throw new TrashException(TrashException.Reason.UNSUPPORTED_PLATFORM,
                TrashException.Outcome.NOT_STARTED,
                "moving to the Trash is not supported on this platform: " + target);
    }

    @Override
    public @NotNull TrashReceipt move(@NotNull TrashTarget target) throws TrashException {
        throw new TrashException(TrashException.Reason.UNSUPPORTED_PLATFORM,
                TrashException.Outcome.NOT_STARTED,
                "moving to the Trash is not supported on this platform: " + target.source());
    }
}
