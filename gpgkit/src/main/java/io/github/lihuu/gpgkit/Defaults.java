package io.github.lihuu.gpgkit;

import java.nio.file.Path;

/**
 * The values SPEC leaves open, named once so two commands cannot disagree about them.
 *
 * <p>The one that matters is {@link #backupDir()}: {@code backup} writes to {@code --out} and
 * {@code doctor} reports on what {@code backup} wrote. If the two each had their own idea of the
 * default directory, doctor would cheerfully report "never backed up" on a machine that has
 * backups — a check that lies is worse than no check.
 */
final class Defaults {
    private Defaults() {
    }

    /** SPEC §2: 0 success, 1 failure with the reason on stderr, 2 usage error. */
    static final int EXIT_OK = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    /** SPEC §2 names this keyserver as the default for `publish`. */
    static final String KEYSERVER = "hkps://keyserver.ubuntu.com";

    /**
     * Where `backup` puts its output when `--out` is not given, and therefore where `doctor`
     * looks. Overridable because a backup that only lives on the machine it backs up is not a
     * backup, and the first thing someone does is point this at a synced directory.
     */
    static final String BACKUP_DIR_ENV = "GPGKIT_BACKUP_DIR";

    /**
     * An environment variable and not a `doctor` flag on purpose: SPEC §2 gives `doctor` no
     * parameters, and a passphrase that can be handed to a check on the command line ends up in
     * the shell history.
     */
    static final String PASSPHRASE_FILE_ENV = "GPGKIT_PASSPHRASE_FILE";

    /**
     * The expiry warning line from SPEC §2: inside this many days a key is worth renewing.
     *
     * <p>Named once because `status` prints it in its verdict and `doctor` grades a key with it;
     * two copies of the number is how the two commands end up disagreeing about the same key.
     */
    static final long EXPIRY_WARN_DAYS = 90;

    /** SPEC §2 names the file `backup` leaves beside each destination. */
    static final String MANIFEST_NAME = "manifest.json";

    static Path backupDir() {
        String override = System.getenv(BACKUP_DIR_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".gpgkit", "backups");
    }

    /**
     * The passphrase file to use, in the order SPEC §2 defines: {@link #PASSPHRASE_FILE_ENV} when
     * it is set, and {@code ~/.gpgkit/backup-passphrase.txt} when it is not.
     *
     * <p>The default path belongs here, next to the other defaults, rather than in the class that
     * writes the file: {@code backup} puts its passphrase at that path when nothing names one, and
     * {@code doctor} has to find it to answer "can this backup still be decrypted". A check that
     * only knew about the environment variable would never see a backup made with plain defaults,
     * which is the way nearly every backup is made.
     *
     * <p>Outside the backup directory on purpose (docs/backup-placement.md): a passphrase stored
     * beside the ciphertext is one synced directory away from travelling with it.
     */
    static Path passphraseFile() {
        String override = System.getenv(PASSPHRASE_FILE_ENV);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), ".gpgkit", "backup-passphrase.txt");
    }
}
