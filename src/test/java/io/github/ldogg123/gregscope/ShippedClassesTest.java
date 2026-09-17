package io.github.ldogg123.gregscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Static checks over the compiled classes of the shipped mod (the {@code main} output on the test classpath). Class
 * files are read as bytes and never loaded, so no Minecraft class is touched. Only the constant pool and the super
 * class are inspected: every class, field and method a class references, every descriptor and every string literal
 * ends up there.
 */
class ShippedClassesTest {

    private static final String ROOT = "io/github/ldogg123/gregscope/";
    /** Classes that must be found, so an empty or wrong scan root cannot pass silently. */
    private static final List<String> EXPECTED = Arrays.asList(
        ROOT + "GregScope",
        ROOT + "CommonProxy",
        ROOT + "integration/opencomputers/GregTechMachineEnvironment",
        ROOT + "integration/opencomputers/GregTechMachineDriver",
        ROOT + "probe/GregTechMachineProbe",
        ROOT + "probe/StateClassifier",
        ROOT + "model/MachineSnapshot");

    private static final List<String> CLIENT_PACKAGES = Arrays
        .asList("net/minecraft/client/", "cpw/mods/fml/client/", "net.minecraft.client.", "cpw.mods.fml.client.");
    private static final String SIDE_ONLY = "Lcpw/mods/fml/relauncher/SideOnly;";

    /**
     * References that periodic work would need in 1.7.10: event bus subscriptions (tick events arrive on the FML bus),
     * world generators, tile entity registration and ticking, OpenComputers' environment update hook, and threads or
     * timers.
     */
    private static final List<String> PERIODIC_WORK_REFERENCES = Arrays.asList(
        "cpw/mods/fml/common/eventhandler/SubscribeEvent",
        "cpw/mods/fml/common/eventhandler/EventBus",
        "cpw/mods/fml/common/gameevent/TickEvent",
        "cpw/mods/fml/common/FMLCommonHandler",
        "net/minecraftforge/common/MinecraftForge",
        "cpw/mods/fml/common/registry/GameRegistry",
        "cpw/mods/fml/common/IWorldGenerator",
        "java/lang/Thread",
        "java/util/Timer",
        "java/util/concurrent/Executor",
        "java/util/concurrent/ScheduledExecutorService");
    /** Member names of tick hooks: TileEntity.canUpdate/updateEntity, OC Environment canUpdate/update. */
    private static final Set<String> TICK_MEMBER_NAMES = new HashSet<>(
        Arrays.asList("canUpdate", "update", "updateEntity"));
    private static final String TILE_ENTITY = "net/minecraft/tileentity/TileEntity";

    private static TreeMap<String, ClassInfo> classes;

    @BeforeAll
    static void readShippedClasses() throws IOException, URISyntaxException {
        URL marker = ShippedClassesTest.class.getClassLoader()
            .getResource(ROOT + "GregScope.class");
        if (marker == null) {
            fail("shipped GregScope.class is not on the test classpath");
        }
        classes = new TreeMap<>();
        if ("jar".equals(marker.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) marker.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName()
                        .endsWith(".class")) {
                        try (InputStream in = jar.getInputStream(entry)) {
                            add(read(in));
                        }
                    }
                }
            }
        } else {
            // .../main/io/github/ldogg123/gregscope/GregScope.class -> the output root .../main
            Path root = Paths.get(marker.toURI())
                .getParent();
            for (int i = 0; i < ROOT.split("/").length; i++) {
                root = root.getParent();
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(
                    p -> p.toString()
                        .endsWith(".class"))
                    .collect(Collectors.toList())) {
                    try (InputStream in = Files.newInputStream(file)) {
                        add(read(in));
                    }
                }
            }
        }
    }

    private static void add(ClassInfo info) {
        classes.put(info.name, info);
    }

    @Test
    void scanFindsTheShippedClassesOnly() {
        for (String expected : EXPECTED) {
            assertTrue(
                classes.containsKey(expected),
                "shipped class not scanned: " + expected + " in " + classes.keySet());
        }
        for (String name : classes.keySet()) {
            assertTrue(name.startsWith(ROOT), "scanned a class outside the mod: " + name);
            assertTrue(!name.contains("/gametest/") && !name.endsWith("Test"), "scanned a test class: " + name);
        }
    }

    @Test
    void noClientClassReferences() {
        List<String> violations = new ArrayList<>();
        for (ClassInfo info : classes.values()) {
            for (String utf8 : info.utf8) {
                for (String client : CLIENT_PACKAGES) {
                    if (utf8.contains(client)) {
                        violations.add(info.name + " references " + utf8);
                    }
                }
            }
            if (info.utf8.contains(SIDE_ONLY) && info.utf8.contains("CLIENT")) {
                violations.add(info.name + " uses @SideOnly(CLIENT)");
            }
        }
        assertEquals(new ArrayList<String>(), violations, "client-only references in shipped classes");
    }

    @Test
    void noPeriodicWorkHooks() {
        List<String> violations = new ArrayList<>();
        for (ClassInfo info : classes.values()) {
            for (String utf8 : info.utf8) {
                for (String reference : PERIODIC_WORK_REFERENCES) {
                    if (utf8.contains(reference)) {
                        violations.add(info.name + " references " + utf8);
                    }
                }
                if (TICK_MEMBER_NAMES.contains(utf8)) {
                    violations.add(info.name + " declares or calls " + utf8);
                }
            }
            if (TILE_ENTITY.equals(info.superName)) {
                violations.add(info.name + " extends " + TILE_ENTITY);
            }
        }
        assertEquals(new ArrayList<String>(), violations, "periodic-work hooks in shipped classes");
    }

    /** A negative control for the scanner itself: it must see what it looks for in a real class file. */
    @Test
    void scannerSeesReferencesInThisTestClass() throws IOException {
        try (InputStream in = ShippedClassesTest.class.getClassLoader()
            .getResourceAsStream(
                ShippedClassesTest.class.getName()
                    .replace('.', '/') + ".class")) {
            ClassInfo self = read(in);
            assertEquals("io/github/ldogg123/gregscope/ShippedClassesTest", self.name);
            assertEquals("java/lang/Object", self.superName);
            assertTrue(self.utf8.contains("Lorg/junit/jupiter/api/Test;"), "annotation descriptor not seen");
            assertTrue(self.utf8.contains("net/minecraft/client/"), "string literal not seen");
        }
    }

    private static final class ClassInfo {

        final String name;
        final String superName;
        final Set<String> utf8;

        ClassInfo(String name, String superName, Set<String> utf8) {
            this.name = name;
            this.superName = superName;
            this.utf8 = utf8;
        }
    }

    /** Minimal class-file reader (JVMS 4.1, 4.4): constant pool UTF-8 entries, this class and super class. */
    private static ClassInfo read(InputStream stream) throws IOException {
        byte[] bytes = readAll(stream);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: // Utf8
                    utf8[i] = in.readUTF();
                    break;
                case 7: // Class
                    classNameIndex[i] = in.readUnsignedShort();
                    break;
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    in.readUnsignedShort();
                    break;
                case 15: // MethodHandle
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    in.readInt();
                    break;
                case 5: // Long
                case 6: // Double
                    in.readLong();
                    i++;
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort(); // access flags
        String name = utf8[classNameIndex[in.readUnsignedShort()]];
        int superIndex = in.readUnsignedShort();
        String superName = superIndex == 0 ? null : utf8[classNameIndex[superIndex]];
        Set<String> strings = new HashSet<>();
        for (String s : utf8) {
            if (s != null) {
                strings.add(s);
            }
        }
        return new ClassInfo(name, superName, strings);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
