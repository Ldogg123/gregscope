package io.github.ldogg123.gregscope.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** GS-109: the real {@link FileStore} of design-v0.2 sections 8.1 to 8.3, over a temporary directory. */
class NioFileStoreTest {

    private static final UUID ID = new UUID(0x3FA2C1D0A1B24C3DL, -0x5E6F708192A3B4C5L);

    /** The production check would refuse every call from the JUnit thread, so the tests answer it themselves. */
    private static NioFileStore store(Path root) {
        return new NioFileStore(root, () -> true);
    }

    @Test
    void aCreatedHistoryFileIsExactlyOneImageAndNoTempFileIsLeftBehind(@TempDir Path root) throws IOException {
        NioFileStore store = store(root);
        assertFalse(store.historyExists(ID));
        assertNull(store.readHistory(ID));
        byte[] image = HistoryFileCodec.newFile(ID, 0, 1_759_000_000L);
        store.createHistory(ID, image);
        assertTrue(store.historyExists(ID));
        assertArrayEquals(image, store.readHistory(ID));
        assertEquals(HistoryFileCodec.FILE_BYTES, Files.size(store.historyFile(ID)));
        assertEquals(
            Arrays.asList(ID + ".gsh"),
            Arrays.asList(
                store.historyDir()
                    .toFile()
                    .list()));
    }

    @Test
    void aSlotIsWrittenInPlaceAndNothingElseMoves(@TempDir Path root) throws IOException {
        NioFileStore store = store(root);
        byte[] image = HistoryFileCodec.newFile(ID, 0, 1L);
        store.createHistory(ID, image);
        byte[] slot = new byte[HistoryFileCodec.SLOT_SIZE];
        Arrays.fill(slot, (byte) 0x5A);
        store.writeSlot(ID, 7, slot);
        byte[] read = store.readHistory(ID);
        assertEquals(HistoryFileCodec.FILE_BYTES, read.length);
        assertArrayEquals(
            slot,
            Arrays.copyOfRange(read, HistoryFileCodec.slotOffset(7), HistoryFileCodec.slotOffset(8)));
        assertArrayEquals(
            Arrays.copyOf(image, HistoryFileCodec.HEADER_BYTES),
            Arrays.copyOf(read, HistoryFileCodec.HEADER_BYTES));
        assertArrayEquals(
            new byte[HistoryFileCodec.SLOT_SIZE],
            Arrays.copyOfRange(read, HistoryFileCodec.slotOffset(8), HistoryFileCodec.slotOffset(9)));
    }

    /** A slot must never create a headerless or sparse file: without a whole file there is nothing to write into. */
    @Test
    void aSlotWriteWithoutAFileFailsInsteadOfCreatingOne(@TempDir Path root) throws IOException {
        NioFileStore store = store(root);
        byte[] slot = new byte[HistoryFileCodec.SLOT_SIZE];
        assertThrows(NoSuchFileException.class, () -> store.writeSlot(ID, 0, slot));
        assertFalse(store.historyExists(ID));

        Files.createDirectories(store.historyDir());
        Files.write(store.historyFile(ID), new byte[10]);
        IOException tooShort = assertThrows(IOException.class, () -> store.writeSlot(ID, 0, slot));
        assertTrue(
            tooShort.getMessage()
                .contains("10 B"),
            tooShort.getMessage());
        assertEquals(10, Files.size(store.historyFile(ID)));
        assertThrows(IOException.class, () -> store.writeSlot(ID, 0, new byte[63]));
    }

    @Test
    void deleteIsIdempotentAndQuarantineNeverDeletes(@TempDir Path root) throws IOException {
        NioFileStore store = store(root);
        store.deleteHistory(ID);
        store.createHistory(ID, HistoryFileCodec.newFile(ID, 0, 1L));
        store.deleteHistory(ID);
        assertFalse(store.historyExists(ID));

        store.createHistory(ID, HistoryFileCodec.newFile(ID, 0, 2L));
        store.quarantineHistory(ID, ".corrupt-123");
        assertFalse(store.historyExists(ID));
        assertTrue(
            Files.isRegularFile(
                store.historyDir()
                    .resolve(ID + ".gsh.corrupt-123")));
        // A second bad file with the same suffix does not overwrite the first one.
        store.createHistory(ID, HistoryFileCodec.newFile(ID, 0, 3L));
        store.quarantineHistory(ID, ".corrupt-123");
        assertTrue(
            Files.isRegularFile(
                store.historyDir()
                    .resolve(ID + ".gsh.corrupt-123-1")));
        // Quarantining what is not there is not an error.
        store.quarantineHistory(ID, ".mismatch");
    }

    @Test
    void theRegistryIsReplacedThroughATempFileAndTheOldOneBecomesTheBackup(@TempDir Path root) throws IOException {
        NioFileStore store = store(root);
        assertNull(store.readRegistry());
        assertNull(store.readRegistryBackup());

        store.writeRegistry(bytes("first"));
        assertArrayEquals(bytes("first"), store.readRegistry());
        // Nothing to back up on the first write.
        assertNull(store.readRegistryBackup());
        assertFalse(Files.exists(root.resolve("registry.dat.tmp")));

        store.writeRegistry(bytes("second"));
        assertArrayEquals(bytes("second"), store.readRegistry());
        assertArrayEquals(bytes("first"), store.readRegistryBackup());

        store.writeRegistry(bytes("third"));
        assertArrayEquals(bytes("third"), store.readRegistry());
        assertArrayEquals(bytes("second"), store.readRegistryBackup());
        assertFalse(Files.exists(root.resolve("registry.dat.tmp")));

        store.quarantineRegistry(".unsupported-v2");
        assertNull(store.readRegistry());
        assertArrayEquals(bytes("third"), Files.readAllBytes(root.resolve("registry.dat.unsupported-v2")));
        // The backup survives the quarantine, so the .bak fallback still has something to offer.
        assertArrayEquals(bytes("second"), store.readRegistryBackup());
    }

    /**
     * Design-v0.2 section 8.3: the atomic move falls back to a plain replacement where the file system cannot do it.
     * The decorator throws {@link AtomicMoveNotSupportedException} for exactly the atomic attempt, so the fallback in
     * the shipped store is the only way the data can arrive.
     */
    @Test
    void anAtomicMoveThatIsNotSupportedFallsBackToAPlainReplacement(@TempDir Path root) throws IOException {
        int[] atomicAttempts = { 0 };
        int[] plainMoves = { 0 };
        NioFileStore store = new NioFileStore(root, () -> true, (source, target, option) -> {
            if (option == StandardCopyOption.ATOMIC_MOVE) {
                atomicAttempts[0]++;
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test");
            }
            plainMoves[0]++;
            Files.move(source, target, option);
        });
        store.writeRegistry(bytes("one"));
        store.writeRegistry(bytes("two"));
        assertArrayEquals(bytes("two"), store.readRegistry());
        assertArrayEquals(bytes("one"), store.readRegistryBackup());
        store.createHistory(ID, HistoryFileCodec.newFile(ID, 0, 1L));
        assertTrue(store.historyExists(ID));
        assertEquals(3, atomicAttempts[0], "the atomic move must be tried first, every time");
        assertEquals(3, plainMoves[0], "the fallback must have done the work");
        assertFalse(Files.exists(root.resolve("registry.dat.tmp")));
        assertFalse(
            Files.exists(
                store.historyFile(ID)
                    .resolveSibling(ID + ".gsh.tmp")));
        // The production store really does ask for an atomic move first.
        int[] productionAtomic = { 0 };
        NioFileStore atomic = new NioFileStore(root.resolve("atomic"), () -> true, (source, target, option) -> {
            if (option == StandardCopyOption.ATOMIC_MOVE) {
                productionAtomic[0]++;
            }
            Files.move(source, target, option);
        });
        atomic.writeRegistry(bytes("x"));
        assertEquals(1, productionAtomic[0]);
    }

    /**
     * The design-v0.2 section 14 acceptance criterion "the server thread does no file I/O": every write asserts, under
     * {@code -ea}, that it runs on the I/O thread. Gradle's test task runs with assertions on, so a store whose check
     * answers "no" must throw.
     */
    @Test
    void everyWriteAssertsItRunsOnTheIoThread(@TempDir Path root) throws IOException {
        assertTrue(
            NioFileStore.class.desiredAssertionStatus(),
            "assertions are off in this JVM, so the -ea check cannot be tested");
        NioFileStore refused = new NioFileStore(root, () -> false);
        byte[] image = HistoryFileCodec.newFile(ID, 0, 1L);
        assertThrows(AssertionError.class, () -> refused.createHistory(ID, image));
        assertThrows(AssertionError.class, () -> refused.writeSlot(ID, 0, new byte[64]));
        assertThrows(AssertionError.class, () -> refused.deleteHistory(ID));
        assertThrows(AssertionError.class, () -> refused.quarantineHistory(ID, ".mismatch"));
        assertThrows(AssertionError.class, () -> refused.writeRegistry(image));
        assertThrows(AssertionError.class, () -> refused.quarantineRegistry(".mismatch"));
        assertThrows(AssertionError.class, () -> refused.readHistory(ID));
        assertThrows(AssertionError.class, () -> refused.historyExists(ID));
        assertFalse(Files.exists(root.resolve("registry.dat")), "a refused call still wrote something");
        // The two registry reads are the documented exception: serverStarting loads the registry synchronously.
        assertNull(refused.readRegistry());
        assertNull(refused.readRegistryBackup());
    }

    @Test
    void theProductionStoreAsksTheIoThreadItself(@TempDir Path root) {
        NioFileStore production = new NioFileStore(root);
        assertFalse(HistoryIo.onIoThread(), "the JUnit thread must not be called " + HistoryIo.THREAD_NAME);
        assertThrows(AssertionError.class, () -> production.deleteHistory(ID));
        assertThrows(IllegalArgumentException.class, () -> new NioFileStore(null));
        assertThrows(IllegalArgumentException.class, () -> new NioFileStore(root, null));
    }

    @Test
    void pathsFollowTheDesignedLayout(@TempDir Path root) {
        NioFileStore store = store(root);
        assertEquals(root, store.root());
        assertEquals(root.resolve("history"), store.historyDir());
        assertEquals(
            root.resolve("history")
                .resolve(ID + ".gsh"),
            store.historyFile(ID));
        assertEquals(root.resolve("registry.dat"), store.registryFile());
        assertEquals(root.resolve("registry.dat.bak"), store.registryBackup());
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

}
