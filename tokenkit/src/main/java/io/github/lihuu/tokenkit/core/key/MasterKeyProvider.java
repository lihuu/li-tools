package io.github.lihuu.tokenkit.core.key;

import java.security.GeneralSecurityException;
import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface MasterKeyProvider {
    @NotNull byte[] getOrCreateMasterKey() throws GeneralSecurityException;
}
