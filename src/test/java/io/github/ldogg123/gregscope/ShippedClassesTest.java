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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
        ROOT + "ClientProxy",
        ROOT + "GregScopeTestHooks",
        ROOT + "config/Settings",
        ROOT + "config/GregScopeConfig",
        // GS-103 pure history/identity model; scanned like every shipped class (no client or periodic-work refs).
        ROOT + "model/StateCodes",
        ROOT + "history/MinuteSlot",
        ROOT + "history/SecondRing",
        ROOT + "sensor/SensorNbtCodec",
        ROOT + "sampling/LogHistogram",
        // GS-104 access policy and the GTNHLib team adapter; scanned like every shipped class.
        ROOT + "access/AccessPolicy",
        ROOT + "access/GtnhlibTeamResolver",
        // GS-105 Machine Sensor item, cover and registration.
        ROOT + "GregScopeAssets",
        ROOT + "sensor/ItemMachineSensor",
        ROOT + "sensor/MachineSensorCover",
        ROOT + "sensor/SensorCovers",
        ROOT + "sensor/SensorCover",
        ROOT + "sensor/SensorKind",
        // GS-106 cover behaviour: the NBT seam adapter, the registry event interface and the description text.
        ROOT + "sensor/NbtKeyValue",
        ROOT + "sensor/SensorEvents",
        ROOT + "sensor/SensorDescription",
        // GS-107 registry: the pure state machine and its MC adapter.
        ROOT + "registry/SensorRegistryCore",
        ROOT + "registry/SensorEntry",
        ROOT + "registry/SensorState",
        ROOT + "registry/SensorRegistry",
        // GS-108 sampler: the pure schedule, frame and fold, the world resolver and the one tick handler.
        ROOT + "sampling/SamplerSchedule",
        ROOT + "sampling/SampleFolder",
        ROOT + "sampling/TelemetryFrame",
        ROOT + "sampling/SensorView",
        ROOT + "sampling/TargetResolver",
        ROOT + "sampling/TelemetrySampler",
        // GS-109 persistence: the pure file codec, the file store seam and its NIO adapter, the I/O thread, and the
        // registry NBT codec with its runs table.
        ROOT + "history/HistoryFileCodec",
        ROOT + "history/Crc32",
        ROOT + "history/SlotLayouts",
        ROOT + "history/FileStore",
        ROOT + "history/IoListener",
        ROOT + "history/NioFileStore",
        ROOT + "history/HistoryIo",
        ROOT + "registry/RunsTable",
        ROOT + "registry/RegistryNbtCodec",
        // GS-110 persistence wiring: the two services and the one Forge-bus hook the saves hang on.
        ROOT + "history/HistoryPersistence",
        ROOT + "registry/RegistryPersistence",
        ROOT + "GregScopeWorldEvents",
        // GS-111 commands: the pure argument parser and the one CommandBase, plus the shared rename cooldown.
        ROOT + "command/CommandArgs",
        ROOT + "command/GregScopeCommand",
        ROOT + "sensor/RenameCooldown",
        // GS-112 Telemetry Hub: the pure record codec and view cap, the block, the one tile entity, registration.
        ROOT + "hub/HubNbtCodec",
        ROOT + "hub/HubViews",
        ROOT + "hub/BlockTelemetryHub",
        ROOT + "hub/TileTelemetryHub",
        ROOT + "hub/TelemetryHubs",
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

    /**
     * GS-112 lifts "no tile entities" (design-v0.2 §1.4) for <b>exactly one</b> class: the Telemetry Hub's tile
     * entity extends {@code TileEntity} and must declare {@code canUpdate} to answer <b>false</b>, which is what keeps
     * it out of every world's tick list. The lift is exactly that name: {@code update} and {@code updateEntity} stay
     * forbidden here too, which {@link #theOnlyTileEntityIsTheHub()} checks together with the super class and the
     * constant {@code false} the running server sees through {@code HubBlockTests}.
     */
    private static final String HUB_TILE = ROOT + "hub/TileTelemetryHub";
    private static final Set<String> HUB_TILE_MEMBER_NAMES = new HashSet<>(Arrays.asList("canUpdate"));

    private static final String GAME_REGISTRY = "cpw/mods/fml/common/registry/GameRegistry";
    /**
     * GS-105 lifts "no items" and GS-112 lifts "no blocks, no tile entities" (design-v0.2 §1.4): {@code GameRegistry}
     * may be referenced by these two registration classes only, and each only for the calls its ticket needs. The
     * value is the exact set of {@code register*} member names that class may name - the reader does not tie a member
     * name to its owner, so GT's {@code registerCover} is listed with the cover registration too. Registering world
     * generators stays forbidden everywhere, and so does {@code registerTileEntityWithAlternatives}.
     */
    private static final Map<String, Set<String>> GAME_REGISTRY_USERS = new TreeMap<>();
    static {
        GAME_REGISTRY_USERS
            .put(ROOT + "sensor/SensorCovers", new HashSet<>(Arrays.asList("registerItem", "registerCover")));
        GAME_REGISTRY_USERS
            .put(ROOT + "hub/TelemetryHubs", new HashSet<>(Arrays.asList("registerBlock", "registerTileEntity")));
    }
    private static final List<String> REGISTRATION_MEMBER_NAMES = Arrays
        .asList("registerWorldGenerator", "registerTileEntity", "registerTileEntityWithAlternatives", "registerBlock");

    /**
     * GS-108 lifts "no global tick handler" (design-v0.2 section 1.4) for <b>exactly one</b> class: the sampler is the
     * only shipped class that may name the FML event bus and the tick event. Everything else in
     * {@link #PERIODIC_WORK_REFERENCES} stays forbidden for it too, so it can still not start a thread, a timer or an
     * executor (GS-109 lifts the I/O thread) and cannot register a world generator or a tile entity.
     */
    private static final String TICK_HANDLER = ROOT + "sampling/TelemetrySampler";
    private static final List<String> TICK_HANDLER_REFERENCES = Arrays.asList(
        "cpw/mods/fml/common/eventhandler/SubscribeEvent",
        "cpw/mods/fml/common/eventhandler/EventBus",
        "cpw/mods/fml/common/gameevent/TickEvent",
        "cpw/mods/fml/common/FMLCommonHandler");

    /**
     * GS-109 lifts "no threads" for <b>exactly one</b> class and its inner classes: {@code HistoryIo} owns the single
     * daemon thread of design-v0.2 §8.4, which is what keeps file I/O off the server thread. Timers, executors, event
     * subscriptions, world generators and tile entity registration stay forbidden for it as well, and
     * {@code NioFileStore} deliberately does not name {@code Thread} either: it asks {@code HistoryIo.onIoThread()}
     * through a {@code BooleanSupplier}, so this lift stays a single class wide.
     */
    private static final String IO_THREAD = ROOT + "history/HistoryIo";
    private static final List<String> IO_THREAD_REFERENCES = Arrays.asList("java/lang/Thread");

    private static boolean isIoThreadClass(String name) {
        return name.equals(IO_THREAD) || name.startsWith(IO_THREAD + "$");
    }

    /** True when this class is allowed to name this {@code register*} member (GS-105 item, GS-112 block and tile). */
    private static boolean allowedRegistration(String className, String member) {
        Set<String> allowed = GAME_REGISTRY_USERS.get(className);
        return allowed != null && allowed.contains(member);
    }

    /**
     * GS-110 lifts the event-bus part of {@link #PERIODIC_WORK_REFERENCES} for <b>exactly one more</b> class:
     * {@code GregScopeWorldEvents} carries the two hooks design-v0.2 §8.3 and §8.4 name, the overworld's
     * {@code WorldEvent.Save} (save the registry when dirty) and {@code WorldEvent.Unload} (finalize and flush). A
     * world save is not periodic work, but it needs {@code @SubscribeEvent} on the <b>Forge</b> bus, which no shipped
     * class named before. The lift is exactly {@code MinecraftForge}, its bus type and {@code SubscribeEvent}: the
     * tick event, {@code FMLCommonHandler}, {@code GameRegistry}, world generators, threads, timers and executors stay
     * forbidden here, which {@link #theOnlyWorldEventHandlerIsThePersistenceHook()} checks together with the two
     * events it really subscribes to.
     */
    private static final String WORLD_EVENTS = ROOT + "GregScopeWorldEvents";
    private static final List<String> WORLD_EVENT_REFERENCES = Arrays.asList(
        "cpw/mods/fml/common/eventhandler/SubscribeEvent",
        "cpw/mods/fml/common/eventhandler/EventBus",
        "net/minecraftforge/common/MinecraftForge");

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
                    if (GAME_REGISTRY.equals(reference) && GAME_REGISTRY_USERS.containsKey(info.name)
                        && utf8.equals(GAME_REGISTRY)) {
                        continue;
                    }
                    if (TICK_HANDLER.equals(info.name) && TICK_HANDLER_REFERENCES.contains(reference)) {
                        continue;
                    }
                    if (isIoThreadClass(info.name) && IO_THREAD_REFERENCES.contains(reference)) {
                        continue;
                    }
                    if (WORLD_EVENTS.equals(info.name) && WORLD_EVENT_REFERENCES.contains(reference)) {
                        continue;
                    }
                    if (utf8.contains(reference)) {
                        violations.add(info.name + " references " + utf8);
                    }
                }
                if (TICK_MEMBER_NAMES.contains(utf8)
                    && !(HUB_TILE.equals(info.name) && HUB_TILE_MEMBER_NAMES.contains(utf8))) {
                    violations.add(info.name + " declares or calls " + utf8);
                }
                if (REGISTRATION_MEMBER_NAMES.contains(utf8) && !allowedRegistration(info.name, utf8)) {
                    violations.add(info.name + " declares or calls " + utf8);
                }
            }
            if (TILE_ENTITY.equals(info.superName) && !HUB_TILE.equals(info.name)) {
                violations.add(info.name + " extends " + TILE_ENTITY);
            }
        }
        assertEquals(new ArrayList<String>(), violations, "periodic-work hooks in shipped classes");
    }

    /**
     * v0.2 has a real client proxy (design-v0.2 §2). FML instantiates it by name from the {@code @SidedProxy} string
     * only on a client, so no shipped class may reference it as a class (that could load it on a dedicated server), and
     * it must set the marker property that the Horizon-QA {@code SafetyTests.clientProxyNotLoaded} checks.
     */
    @Test
    void clientProxyIsOnlyNamedByTheSidedProxy() {
        String clientProxy = ROOT + "ClientProxy";
        List<String> violations = new ArrayList<>();
        for (ClassInfo info : classes.values()) {
            if (info.name.equals(clientProxy)) {
                continue;
            }
            for (String utf8 : info.utf8) {
                if (utf8.contains(clientProxy)) {
                    violations.add(info.name + " references " + utf8);
                }
            }
        }
        assertEquals(new ArrayList<String>(), violations, "shipped classes referencing ClientProxy");
        assertTrue(
            classes.get(ROOT + "GregScope").utf8.contains("io.github.ldogg123.gregscope.ClientProxy"),
            "@SidedProxy clientSide does not name ClientProxy");
        assertTrue(
            classes.get(ROOT + "GregScope").utf8.contains("io.github.ldogg123.gregscope.CommonProxy"),
            "@SidedProxy serverSide does not name CommonProxy");
        assertTrue(classes.get(clientProxy).utf8.contains("gregscope.clientProxyLoaded"), "marker property not set");
        assertEquals(ROOT + "CommonProxy", classes.get(clientProxy).superName);
    }

    /**
     * The GS-105 and GS-112 lifts are narrow: each registration class references {@code GameRegistry} and really makes
     * every call it is allowed (so the skips in {@code noPeriodicWorkHooks} cannot pass vacuously), and neither names
     * any other {@code register*} member - the sensor registration cannot register a block or a tile entity, and the
     * Hub registration cannot register an item or a world generator.
     */
    @Test
    void gameRegistryIsUsedOnlyToRegisterTheSensorItemAndTheHubBlock() {
        for (Map.Entry<String, Set<String>> user : GAME_REGISTRY_USERS.entrySet()) {
            ClassInfo info = classes.get(user.getKey());
            assertTrue(info != null, "registration class not scanned: " + user.getKey());
            assertTrue(
                info.utf8.contains(GAME_REGISTRY),
                user.getKey() + " no longer references GameRegistry; narrow the lift");
            for (String member : new TreeSet<>(user.getValue())) {
                assertTrue(info.utf8.contains(member), user.getKey() + " does not call " + member);
            }
        }
        // This reader does not tie member names to their owner, so every register* name in the class must be allowed.
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> user : GAME_REGISTRY_USERS.entrySet()) {
            for (String utf8 : classes.get(user.getKey()).utf8) {
                if (utf8.startsWith("register") && !user.getValue()
                    .contains(utf8)) {
                    violations.add(user.getKey() + " references " + utf8);
                }
            }
        }
        assertEquals(new ArrayList<String>(), violations, "unexpected register* calls in a registration class");
    }

    /**
     * The GS-112 lift is narrow too: {@code TileTelemetryHub} really is a {@code TileEntity} that declares
     * {@code canUpdate} (so the skips in {@code noPeriodicWorkHooks} cannot pass vacuously), it declares neither
     * {@code update} nor {@code updateEntity}, it uses none of the periodic-work references, and no other shipped
     * class extends {@code TileEntity} or names any tick member - which {@code noPeriodicWorkHooks} enforces for every
     * class but this one. That {@code canUpdate()} really answers <b>false</b> on a placed Hub is checked on the
     * running server by {@code HubBlockTests}: this reader sees the constant pool, not the method bodies.
     */
    @Test
    void theOnlyTileEntityIsTheHub() {
        ClassInfo hub = classes.get(HUB_TILE);
        assertTrue(hub != null, "the Hub tile entity was not scanned: " + HUB_TILE);
        assertEquals(TILE_ENTITY, hub.superName, HUB_TILE + " no longer extends TileEntity; narrow the lift");
        assertTrue(hub.utf8.contains("canUpdate"), HUB_TILE + " does not declare canUpdate; narrow the lift");
        for (String member : TICK_MEMBER_NAMES) {
            if (!HUB_TILE_MEMBER_NAMES.contains(member)) {
                assertTrue(!hub.utf8.contains(member), HUB_TILE + " declares or calls " + member);
            }
        }
        List<String> violations = new ArrayList<>();
        for (String utf8 : hub.utf8) {
            for (String reference : PERIODIC_WORK_REFERENCES) {
                if (utf8.contains(reference)) {
                    violations.add(HUB_TILE + " references " + utf8);
                }
            }
        }
        assertEquals(new ArrayList<String>(), violations, "the Hub tile entity may only be a non-ticking TileEntity");
        // And nothing else may be a tile entity at all.
        List<String> others = new ArrayList<>();
        for (ClassInfo info : classes.values()) {
            if (!info.name.equals(HUB_TILE) && TILE_ENTITY.equals(info.superName)) {
                others.add(info.name + " extends " + TILE_ENTITY);
            }
        }
        assertEquals(new ArrayList<String>(), others, "shipped classes other than the Hub extending TileEntity");
    }

    /**
     * The GS-108 lift is narrow too: the sampler really is a {@code ServerTickEvent} handler on the FML bus (so the
     * skip in {@code noPeriodicWorkHooks} cannot pass vacuously), and no other shipped class names any of the four
     * tick references, which {@code noPeriodicWorkHooks} enforces for every class but this one. That there is exactly
     * <b>one</b> {@code @SubscribeEvent} method, and that it takes a {@code ServerTickEvent}, is checked on the
     * running server by {@code IdleCostTests.exactlyOneServerTickHandler}: this reader sees the constant pool, not the
     * method table.
     */
    @Test
    void theOnlyTickHandlerIsTheSampler() {
        ClassInfo sampler = classes.get(TICK_HANDLER);
        assertTrue(sampler != null, "the sampler was not scanned: " + TICK_HANDLER);
        for (String reference : TICK_HANDLER_REFERENCES) {
            boolean seen = false;
            for (String utf8 : sampler.utf8) {
                seen |= utf8.contains(reference);
            }
            assertTrue(seen, TICK_HANDLER + " no longer references " + reference + "; narrow the lift");
        }
        assertTrue(
            sampler.utf8.contains("Lcpw/mods/fml/common/eventhandler/SubscribeEvent;"),
            "the sampler has no @SubscribeEvent annotation");
        assertTrue(
            sampler.utf8.contains("cpw/mods/fml/common/gameevent/TickEvent$ServerTickEvent"),
            "the sampler does not name ServerTickEvent");
        assertTrue(
            sampler.utf8.contains("cpw/mods/fml/common/gameevent/TickEvent$Phase"),
            "the sampler does not name TickEvent.Phase, so it cannot be filtering on END");
        // The lift is only about the four tick references: threads, timers and executors stay forbidden here too.
        List<String> violations = new ArrayList<>();
        for (String utf8 : sampler.utf8) {
            for (String reference : PERIODIC_WORK_REFERENCES) {
                if (!TICK_HANDLER_REFERENCES.contains(reference) && utf8.contains(reference)) {
                    violations.add(TICK_HANDLER + " references " + utf8);
                }
            }
        }
        assertEquals(new ArrayList<String>(), violations, "the sampler may only use the tick references");
    }

    /**
     * The GS-109 lift is narrow too: {@code HistoryIo} really does own a thread (so the skip in
     * {@code noPeriodicWorkHooks} cannot pass vacuously), it names it {@code GregScope-IO} and makes it a daemon, it
     * uses none of the other periodic-work references, and no other shipped class - {@code NioFileStore} included -
     * names {@code java.lang.Thread}, which {@code noPeriodicWorkHooks} enforces for every class but this one.
     */
    @Test
    void theOnlyThreadIsTheIoThread() {
        ClassInfo io = classes.get(IO_THREAD);
        assertTrue(io != null, "the I/O thread class was not scanned: " + IO_THREAD);
        for (String reference : IO_THREAD_REFERENCES) {
            boolean seen = false;
            for (String utf8 : io.utf8) {
                seen |= utf8.contains(reference);
            }
            assertTrue(seen, IO_THREAD + " no longer references " + reference + "; narrow the lift");
        }
        assertTrue(io.utf8.contains("GregScope-IO"), "the I/O thread is not named GregScope-IO");
        assertTrue(io.utf8.contains("setDaemon"), "the I/O thread is not made a daemon");
        // The lift is only about java.lang.Thread: event buses, timers and executors stay forbidden here too.
        List<String> violations = new ArrayList<>();
        for (String name : classes.keySet()) {
            if (!isIoThreadClass(name)) {
                continue;
            }
            for (String utf8 : classes.get(name).utf8) {
                for (String reference : PERIODIC_WORK_REFERENCES) {
                    if (!IO_THREAD_REFERENCES.contains(reference) && utf8.contains(reference)) {
                        violations.add(name + " references " + utf8);
                    }
                }
            }
        }
        assertEquals(new ArrayList<String>(), violations, "the I/O thread class may only use java.lang.Thread");
        // The file store is the other half of "no file I/O on the server thread"; it must not need Thread itself.
        ClassInfo store = classes.get(ROOT + "history/NioFileStore");
        assertTrue(store != null, "the file store was not scanned");
        for (String utf8 : store.utf8) {
            assertTrue(!utf8.contains("java/lang/Thread"), "NioFileStore references " + utf8 + "; widen the lift?");
        }
        assertTrue(store.utf8.contains("onIoThread"), "NioFileStore does not ask HistoryIo which thread it is on");
    }

    /**
     * The GS-110 lift is narrow too: {@code GregScopeWorldEvents} really does subscribe to the Forge bus (so the skip
     * in {@code noPeriodicWorkHooks} cannot pass vacuously), it names exactly the two {@code WorldEvent} types
     * design-v0.2 §8.3 and §8.4 ask for and no other, it uses none of the other periodic-work references, and no
     * other shipped class names {@code MinecraftForge}, which {@code noPeriodicWorkHooks} enforces for every class but
     * this one. That there are exactly <b>two</b> {@code @SubscribeEvent} methods and that the server holds exactly
     * one GregScope listener on the Forge bus is checked on the running server by {@code IdleCostTests}: this reader
     * sees the constant pool, not the method table.
     */
    @Test
    void theOnlyWorldEventHandlerIsThePersistenceHook() {
        ClassInfo hook = classes.get(WORLD_EVENTS);
        assertTrue(hook != null, "the world event hook was not scanned: " + WORLD_EVENTS);
        for (String reference : WORLD_EVENT_REFERENCES) {
            boolean seen = false;
            for (String utf8 : hook.utf8) {
                seen |= utf8.contains(reference);
            }
            assertTrue(seen, WORLD_EVENTS + " no longer references " + reference + "; narrow the lift");
        }
        assertTrue(
            hook.utf8.contains("Lcpw/mods/fml/common/eventhandler/SubscribeEvent;"),
            "the world event hook has no @SubscribeEvent annotation");
        assertTrue(
            hook.utf8.contains("net/minecraftforge/event/world/WorldEvent$Save"),
            "the hook does not name WorldEvent.Save, so it cannot be saving the registry on a world save");
        assertTrue(
            hook.utf8.contains("net/minecraftforge/event/world/WorldEvent$Unload"),
            "the hook does not name WorldEvent.Unload, so it cannot be flushing on the overworld unload");
        // PotentialSpawns is a WorldEvent too and fires several times per chunk per tick; a base-class handler would
        // put GregScope on a hot path, so the two subclasses are named and the base class is not subscribed.
        assertTrue(
            !hook.utf8.contains("Lnet/minecraftforge/event/world/WorldEvent;)V"),
            "the hook subscribes to WorldEvent itself, which also delivers PotentialSpawns every tick");
        // The lift is only about the three Forge-bus references: ticks, threads, timers and executors stay forbidden.
        List<String> violations = new ArrayList<>();
        for (String utf8 : hook.utf8) {
            for (String reference : PERIODIC_WORK_REFERENCES) {
                if (!WORLD_EVENT_REFERENCES.contains(reference) && utf8.contains(reference)) {
                    violations.add(WORLD_EVENTS + " references " + utf8);
                }
            }
        }
        assertEquals(new ArrayList<String>(), violations, "the world event hook may only use the Forge bus");
        // And nothing else may reach the Forge bus: the sampler's own lift does not include MinecraftForge.
        List<String> others = new ArrayList<>();
        for (ClassInfo info : classes.values()) {
            if (info.name.equals(WORLD_EVENTS)) {
                continue;
            }
            for (String utf8 : info.utf8) {
                if (utf8.contains("net/minecraftforge/common/MinecraftForge")) {
                    others.add(info.name + " references " + utf8);
                }
            }
        }
        assertEquals(new ArrayList<String>(), others, "shipped classes other than the hook reaching the Forge bus");
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
