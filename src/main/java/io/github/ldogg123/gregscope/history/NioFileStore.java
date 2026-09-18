package io.github.ldogg123.gregscope.history;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The real {@link FileStore}: {@code <world>/gregscope/} on disk (design-v0.2 §8.1 to §8.3). Design-v0.2 §2 lists it
 * with {@code HistoryIo} as the I/O adapter of the {@code history} package rather than as a pure class, so it is listed
 * in {@code PureSourcesTest.IMPURE}; it imports nothing from a game, only {@code java.nio}.
 *
 * <p>
 * <b>Atomic replacement.</b> {@link #writeRegistry} writes {@code registry.dat.tmp}, copies the current
 * {@code registry.dat} to {@code registry.dat.bak}, then moves the temporary file into place with
 * {@link StandardCopyOption#ATOMIC_MOVE}, falling back to {@link StandardCopyOption#REPLACE_EXISTING} when the file
 * system does not support an atomic move. A file created by {@link #createHistory} goes the same way, without a
 * {@code .bak}: a brand-new history file has nothing to preserve.
 *
 * <p>
 * <b>The server thread does no file I/O.</b> Every method but the two registry reads asserts, under {@code -ea}, that
 * it runs on the I/O thread. The check is a {@link BooleanSupplier} supplied at construction
 * ({@code HistoryIo::onIoThread} in production) rather than a {@code Thread.currentThread()} call here, so that
 * {@code java.lang.Thread} is named by exactly one shipped class - the one that owns the thread - and
 * {@code ShippedClassesTest} needs only that single lift. A unit test passes its own supplier and can therefore
 * exercise both the allowed and the refused case.
 *
 * <p>
 * The two registry reads are excluded on purpose: design-v0.2 §2 loads the registry in {@code serverStarting}, before
 * the world runs, and §8.4 lists no {@code ReadRegistry} task.
 */
public final class NioFileStore implements FileStore {

    public static final String HISTORY_DIR = "history";
    public static final String HISTORY_SUFFIX = ".gsh";
    public static final String REGISTRY_FILE = "registry.dat";
    public static final String BACKUP_SUFFIX = ".bak";
    public static final String TEMP_SUFFIX = ".tmp";
    /** Discriminators tried when a quarantine name is already taken. */
    private static final int MAX_QUARANTINE_ATTEMPTS = 1000;

    /**
     * The one move primitive, so a test can replace it with a file system that refuses atomic moves and exercise the
     * design-v0.2 §8.3 fallback in the shipped code rather than in a copy of it.
     */
    interface Mover {

        Mover DEFAULT = Files::move;

        void move(Path source, Path target, CopyOption option) throws IOException;
    }

    private final Path root;
    private final BooleanSupplier onIoThread;
    private final Mover mover;

    /** The production store: every write must happen on the {@code HistoryIo} thread. */
    public NioFileStore(Path root) {
        this(root, HistoryIo::onIoThread);
    }

    /**
     * @param onIoThread what the {@code -ea} assertion asks; a test supplies its own so both answers can be tested
     */
    public NioFileStore(Path root, BooleanSupplier onIoThread) {
        this(root, onIoThread, Mover.DEFAULT);
    }

    NioFileStore(Path root, BooleanSupplier onIoThread, Mover mover) {
        if (root == null || onIoThread == null || mover == null) {
            throw new IllegalArgumentException("root, onIoThread and mover are required");
        }
        this.root = root;
        this.onIoThread = onIoThread;
        this.mover = mover;
    }

    /** {@code <world>/gregscope/}. */
    public Path root() {
        return root;
    }

    public Path historyDir() {
        return root.resolve(HISTORY_DIR);
    }

    public Path historyFile(UUID id) {
        return historyDir().resolve(id + HISTORY_SUFFIX);
    }

    public Path registryFile() {
        return root.resolve(REGISTRY_FILE);
    }

    public Path registryBackup() {
        return root.resolve(REGISTRY_FILE + BACKUP_SUFFIX);
    }

    // --- history files ---

    @Override
    public byte[] readHistory(UUID id) throws IOException {
        checkThread();
        return readIfPresent(historyFile(id));
    }

    @Override
    public boolean historyExists(UUID id) throws IOException {
        checkThread();
        return Files.isRegularFile(historyFile(id));
    }

    @Override
    public void createHistory(UUID id, byte[] file) throws IOException {
        checkThread();
        if (file == null || file.length != HistoryFileCodec.FILE_BYTES) {
            throw new IOException(
                "a history file image must be " + HistoryFileCodec.FILE_BYTES
                    + " B, not "
                    + (file == null ? "null" : file.length));
        }
        Path target = historyFile(id);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        Files.write(temp, file);
        move(temp, target);
    }

    @Override
    public void writeSlot(UUID id, int index, byte[] slot) throws IOException {
        checkThread();
        if (slot == null || slot.length != HistoryFileCodec.SLOT_SIZE) {
            throw new IOException("a minute slot is " + HistoryFileCodec.SLOT_SIZE + " B");
        }
        int offset = HistoryFileCodec.slotOffset(index);
        Path target = historyFile(id);
        long size;
        try {
            size = Files.size(target);
        } catch (NoSuchFileException e) {
            throw new NoSuchFileException("no history file for " + id + "; it must be created first");
        }
        if (size != HistoryFileCodec.FILE_BYTES) {
            throw new IOException("history file for " + id + " is " + size + " B, not " + HistoryFileCodec.FILE_BYTES);
        }
        // "rw" without a create: the size check above already proved the file is there and whole.
        RandomAccessFile handle = new RandomAccessFile(target.toFile(), "rw");
        try {
            handle.seek(offset);
            handle.write(slot);
        } finally {
            handle.close();
        }
    }

    @Override
    public void deleteHistory(UUID id) throws IOException {
        checkThread();
        Files.deleteIfExists(historyFile(id));
    }

    @Override
    public void quarantineHistory(UUID id, String suffix) throws IOException {
        checkThread();
        quarantine(historyFile(id), suffix);
    }

    // --- registry ---

    /** Read while the server is starting; the one method pair that is not on the I/O thread (see the class docs). */
    @Override
    public byte[] readRegistry() throws IOException {
        return readIfPresent(registryFile());
    }

    @Override
    public byte[] readRegistryBackup() throws IOException {
        return readIfPresent(registryBackup());
    }

    @Override
    public void writeRegistry(byte[] bytes) throws IOException {
        checkThread();
        if (bytes == null) {
            throw new IOException("no registry bytes");
        }
        Path target = registryFile();
        Files.createDirectories(root);
        Path temp = root.resolve(REGISTRY_FILE + TEMP_SUFFIX);
        Files.write(temp, bytes);
        if (Files.isRegularFile(target)) {
            Files.copy(target, registryBackup(), StandardCopyOption.REPLACE_EXISTING);
        }
        move(temp, target);
    }

    @Override
    public void quarantineRegistry(String suffix) throws IOException {
        checkThread();
        quarantine(registryFile(), suffix);
    }

    // --- helpers ---

    private static byte[] readIfPresent(Path file) throws IOException {
        try {
            return Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    /**
     * Moves {@code temp} onto {@code target} atomically, falling back to a plain replacement on a file system that
     * cannot do it (design-v0.2 §8.3).
     */
    private void move(Path temp, Path target) throws IOException {
        try {
            mover.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            mover.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Renames a file aside, never over an existing one; a file that is not there is not an error. */
    private static void quarantine(Path file, String suffix) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        Path aside = file.resolveSibling(file.getFileName() + suffix);
        for (int i = 1; Files.exists(aside); i++) {
            if (i > MAX_QUARANTINE_ATTEMPTS) {
                throw new IOException("cannot find a free name for " + aside);
            }
            aside = file.resolveSibling(file.getFileName() + suffix + "-" + i);
        }
        Files.move(file, aside);
    }

    private void checkThread() {
        assert onIoThread.getAsBoolean() : "GregScope file I/O must run on the " + HistoryIo.THREAD_NAME + " thread";
    }

    @Override
    public String toString() {
        return "NioFileStore{" + root + "}";
    }
}
