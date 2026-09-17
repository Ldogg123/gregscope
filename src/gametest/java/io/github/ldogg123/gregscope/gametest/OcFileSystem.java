package io.github.ldogg123.gregscope.gametest;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Node;

/**
 * Reads and writes files on an OpenComputers {@code filesystem} component through its Lua callbacks, exactly as a
 * program on the computer would. Host files are not used: managed disks buffer changes in memory until a world save.
 */
final class OcFileSystem {

    /** OC caps a single {@code read} callback at {@code filesystem.maxReadBuffer} (2048 bytes by default). */
    private static final int READ_CHUNK = 2048;

    private final Component component;
    private final Context context;

    /**
     * @param owner an addressed node on the same network (the computer's machine node): OC keys file handle ownership
     *              on {@code context.node().address()}, so {@link StubContext} (null node) cannot be used here.
     */
    OcFileSystem(Component component, Node owner) {
        this.component = component;
        this.context = new OwnerContext(owner);
    }

    String address() {
        return component.address();
    }

    boolean isReadOnly() {
        return Boolean.TRUE.equals(call("isReadOnly")[0]);
    }

    boolean exists(String path) {
        return Boolean.TRUE.equals(call("exists", path)[0]);
    }

    /** Removes a file (or directory tree); returns false when nothing was removed. */
    boolean remove(String path) {
        return Boolean.TRUE.equals(call("remove", path)[0]);
    }

    void write(String path, byte[] data) {
        Object handle = call("open", path, "wb")[0];
        try {
            call("write", handle, data);
        } finally {
            call("close", handle);
        }
    }

    String readText(String path) {
        Object handle = call("open", path, "rb")[0];
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            while (true) {
                Object[] chunk = call("read", handle, READ_CHUNK);
                if (chunk == null || chunk.length == 0 || chunk[0] == null) {
                    break;
                }
                byte[] data = (byte[]) chunk[0];
                bytes.write(data, 0, data.length);
            }
        } finally {
            call("close", handle);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private Object[] call(String method, Object... args) {
        try {
            Object[] result = component.invoke(method, context, args);
            return result == null ? new Object[] { null } : result;
        } catch (Exception e) {
            throw new IllegalStateException("filesystem " + component.address() + " " + method + " failed: " + e, e);
        }
    }

    /** Context owned by a real network node; never charges call budget (the calls come from the server thread). */
    private static final class OwnerContext implements Context {

        private final Node owner;

        OwnerContext(Node owner) {
            this.owner = owner;
        }

        @Override
        public Node node() {
            return owner;
        }

        @Override
        public boolean canInteract(String player) {
            return true;
        }

        @Override
        public boolean isRunning() {
            return true;
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public boolean start() {
            return false;
        }

        @Override
        public boolean pause(double seconds) {
            return false;
        }

        @Override
        public boolean stop() {
            return false;
        }

        @Override
        public void consumeCallBudget(double callCost) {}

        @Override
        public boolean signal(String name, Object... args) {
            return false;
        }
    }
}
