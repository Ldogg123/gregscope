package io.github.ldogg123.gregscope.history;

import java.io.IOException;
import java.util.UUID;

/**
 * Every file GregScope touches under {@code <world>/gregscope/} (design-v0.2 §8.1), as a seam. [pure]
 *
 * <p>
 * The interface names only {@code java.*} types, so a unit test can implement it in memory (GS-110's
 * {@code PersistenceScenarioTest}) while {@code NioFileStore} is the real one. Every method here is mechanical: what a
 * file <em>means</em> is {@link HistoryFileCodec}'s and {@code RegistryNbtCodec}'s business, and the decision to rename
 * a bad file aside is the caller's.
 *
 * <p>
 * <b>Threading.</b> Except for {@link #readRegistry()} and {@link #readRegistryBackup()}, which run once while the
 * server is starting, every method is called from the {@code HistoryIo} thread. {@code NioFileStore} asserts that
 * under {@code -ea}.
 */
public interface FileStore {

    /** The {@code history/<uuid>.gsh} file as it is on disk, or null if there is none. */
    byte[] readHistory(UUID id) throws IOException;

    /** True if {@code history/<uuid>.gsh} exists. */
    boolean historyExists(UUID id) throws IOException;

    /**
     * Creates {@code history/<uuid>.gsh} from a whole {@link HistoryFileCodec#FILE_BYTES} B image, by writing a
     * {@code .tmp} and moving it into place, so no half-written file is ever visible under the real name.
     */
    void createHistory(UUID id, byte[] file) throws IOException;

    /**
     * Overwrites one 64-byte slot in place (design-v0.2 §8.2: open, seek, write 64 B, close; no fsync, no handle
     * cache, because Windows cannot delete an open file).
     *
     * @throws IOException if the file does not exist or does not have the fixed size, so a slot can never create a
     *                     headerless or sparse file
     */
    void writeSlot(UUID id, int index, byte[] slot) throws IOException;

    /** Deletes {@code history/<uuid>.gsh}; a file that is not there is not an error. */
    void deleteHistory(UUID id) throws IOException;

    /**
     * Renames {@code history/<uuid>.gsh} to {@code history/<uuid>.gsh<suffix>} (design-v0.2 §8.1: corrupt, unsupported
     * and mismatched files are kept for ever, never deleted). A name that is taken gets a numeric discriminator.
     */
    void quarantineHistory(UUID id, String suffix) throws IOException;

    /** {@code registry.dat} as it is on disk, or null if there is none. */
    byte[] readRegistry() throws IOException;

    /** {@code registry.dat.bak} as it is on disk, or null if there is none. */
    byte[] readRegistryBackup() throws IOException;

    /**
     * Replaces {@code registry.dat}: write {@code registry.dat.tmp}, copy the old file to {@code registry.dat.bak},
     * then move the temporary file into place atomically where the file system supports it (design-v0.2 §8.3).
     */
    void writeRegistry(byte[] bytes) throws IOException;

    /** Renames {@code registry.dat} aside, as {@link #quarantineHistory} does for a history file. */
    void quarantineRegistry(String suffix) throws IOException;
}
