package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.TestPos;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.enums.ItemList;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.GregScopeWorldEvents;
import io.github.ldogg123.gregscope.hub.HubViewLifecycle;
import io.github.ldogg123.gregscope.hub.TileTelemetryHub;
import io.github.ldogg123.gregscope.integration.opencomputers.GregTechMachineEnvironment;
import io.github.ldogg123.gregscope.sampling.SamplerStats;
import io.github.ldogg123.gregscope.sampling.TelemetryFrame;
import io.github.ldogg123.gregscope.sampling.TelemetrySampler;
import li.cil.oc.api.Network;
import li.cil.oc.api.network.Component;
import li.cil.oc.api.network.Environment;
import li.cil.oc.api.network.ManagedEnvironment;

/**
 * Structural evidence for "no idle cost when nobody calls the component" (handoff §12, §13): the running server holds
 * exactly one periodic hook of GregScope's, the design-v0.2 §1.4 {@code ServerTickEvent} END handler, and that handler
 * does nothing while no sensor is LIVE. This is not a benchmark; nothing is timed.
 *
 * <p>
 * GS-108, GS-110 and the GS-114 follow-up changed what these tests assert, deliberately. Before GS-108, no GregScope
 * object was subscribed to any bus. Now exactly three are, and no more: {@code TelemetrySampler} on the FML bus (where
 * 1.7.10 posts {@code ServerTickEvent}), with exactly one {@code @SubscribeEvent} method taking a
 * {@code ServerTickEvent}; {@code HubViewLifecycle} on the FML bus as well, with exactly one {@code @SubscribeEvent}
 * method taking a {@code PlayerLoggedOutEvent}, which releases a Hub view slot a disconnecting client never releases
 * itself (design-v0.2 section 9.1); and {@code GregScopeWorldEvents} on the Forge bus, with exactly two
 * {@code @SubscribeEvent} methods taking {@code WorldEvent.Save} and {@code WorldEvent.Unload} (design-v0.2 sections
 * 8.3 and 8.4). None of them is periodic: a world save happens when Minecraft writes the world, dimension 0 unloads
 * only while the server stops, and a logout fires once per player per session. Everything
 * else is unchanged: nothing on the terrain or ore generation buses, no registered or loaded GregScope tile entity, no
 * world generator, and no ticking OpenComputers environment.
 *
 * <p>
 * Reflection here reads Forge, FML and OpenComputers internals that have no public accessor. It is test code only;
 * shipped code uses none. Every scan also asserts that it found something (other mods' listeners, registered tile
 * entities), so a changed internal field cannot make the check pass by finding nothing.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class IdleCostTests {

    private static final String BATCH = "gregscope.idle";
    private static final String SHIPPED_PACKAGE = "io.github.ldogg123.gregscope.";
    private static final String TEST_PACKAGE = "io.github.ldogg123.gregscope.gametest.";
    private static final TestPos MACHINE = at(1, 0, 1);
    private static final TestPos ADAPTER = at(2, 0, 1);
    private static final int OC_TIMEOUT_TICKS = 20;
    /** Real server ticks the Adapter runs with the component attached before its tick list is inspected. */
    private static final int IDLE_TICKS = 5;

    private IdleCostTests() {}

    /**
     * The only GregScope object subscribed to any Forge/FML event bus is the sampler, on the FML bus (1.7.10 tick
     * events arrive there); no GregScope tile entity class is registered or loaded in any world, and no GregScope
     * world generator exists.
     */
    @GameTest(batch = BATCH)
    public static void noGregScopeListenersTileEntitiesOrGenerators(GameTestHelper helper) {
        helper.assertTrue(Loader.isModLoaded(GregScope.MODID), "GregScope is not loaded");

        int listenerObjects = 0;
        // GS-110 (design-v0.2 sections 8.3 and 8.4): exactly one, the overworld save/unload persistence hook.
        listenerObjects += assertGregScopeListeners(
            helper,
            "MinecraftForge.EVENT_BUS",
            MinecraftForge.EVENT_BUS,
            1,
            GregScopeWorldEvents.class);
        listenerObjects += assertGregScopeListeners(
            helper,
            "MinecraftForge.TERRAIN_GEN_BUS",
            MinecraftForge.TERRAIN_GEN_BUS,
            0);
        listenerObjects += assertGregScopeListeners(
            helper,
            "MinecraftForge.ORE_GEN_BUS",
            MinecraftForge.ORE_GEN_BUS,
            0);
        // GS-108 (design-v0.2 section 1.4): the sampler, plus the GS-114 follow-up's logout hook, and nothing else.
        listenerObjects += assertGregScopeListeners(
            helper,
            "FMLCommonHandler.bus()",
            FMLCommonHandler.instance()
                .bus(),
            2,
            TelemetrySampler.class,
            HubViewLifecycle.class);
        // GT, OC and Forge itself subscribe many listeners; zero would mean the scan is broken.
        helper.assertTrue(listenerObjects > 10, "event bus scan found only " + listenerObjects + " listener objects");

        // GS-112 (design-v0.2 section 1.4 lifts "no tile entities" for the Telemetry Hub): exactly one GregScope
        // class is registered, and it is the Hub's. Everything else shipped stays out of the registry.
        int tileEntityClasses = 0;
        // TileEntity keeps two maps (name -> class and class -> name), so the same class turns up twice; count the
        // distinct GregScope ones.
        Set<String> gregScopeTileClasses = new HashSet<>();
        for (Object element : staticCollectionElements(helper, TileEntity.class)) {
            if (element instanceof Class) {
                tileEntityClasses++;
                String name = ((Class<?>) element).getName();
                if (isShipped(name)) {
                    gregScopeTileClasses.add(name);
                } else {
                    assertNotShipped(helper, "registered tile entity class", name);
                }
            }
        }
        helper.assertTrue(tileEntityClasses > 50, "tile entity registry scan found only " + tileEntityClasses);
        helper.assertIterableEquals(
            Collections.singletonList(TileTelemetryHub.class.getName()),
            new ArrayList<>(gregScopeTileClasses),
            "GregScope tile entity classes in the registry");

        for (Object element : staticCollectionElements(helper, GameRegistry.class)) {
            assertNotShipped(
                helper,
                "GameRegistry entry",
                element.getClass()
                    .getName());
        }

        // Still zero *loaded* tile entities: World.addTileEntity and World.setTileEntity only add a tile whose
        // canUpdate() is true (World.java:4405-4412 and :2834-2841), and the Hub's answers false. A Hub placed in the
        // world therefore never joins a tick list, which HubBlockTests asserts on a Hub it has just placed.
        int loadedTiles = 0;
        for (WorldServer world : MinecraftServer.getServer().worldServers) {
            for (Object tile : world.loadedTileEntityList) {
                loadedTiles++;
                assertNotShipped(
                    helper,
                    "loaded tile entity",
                    tile.getClass()
                        .getName());
            }
        }
        System.out.println(
            "[GregScope gametest] idle#1 scanned " + listenerObjects
                + " event listener objects, "
                + tileEntityClasses
                + " registered tile entity classes, "
                + loadedTiles
                + " loaded tile entities: none from GregScope");
        helper.succeed();
    }

    /**
     * A real Adapter next to a GT machine only ticks environments whose {@code canUpdate()} is true
     * (Adapter.scala: {@code updatingBlocks}). GregScope's environment and the merged compound environment report
     * false, and the Adapter's tick list stays empty after real server ticks.
     */
    @GameTest(batch = BATCH, timeoutTicks = 100)
    public static void adapterNeverTicksGregScopeEnvironment(GameTestHelper helper) {
        GtPlacement.placeMachine(helper, MACHINE, ItemList.Machine_LV_Macerator.get(1));
        TileEntity adapter = OcComponents.placeBlock(helper, ADAPTER, "adapter");
        Network.joinOrCreateNetwork(adapter);
        Component[] component = new Component[1];

        helper.startSequence()
            .thenWaitUntil(
                "Adapter exposes the macerator",
                OC_TIMEOUT_TICKS,
                () -> { component[0] = OcComponents.adapterSnapshotComponent(helper, (Environment) adapter); })
            .thenIdle(IDLE_TICKS)
            .thenExecute("inspect the Adapter's tick list", () -> {
                // Read and assert the Adapter's own tick list first, so a negative control that makes an environment
                // tick fails here and not only at the canUpdate() asserts below.
                Object updatingBlocks = readField(helper, adapter, adapter.getClass(), "updatingBlocks");
                int size = ((Number) call(helper, updatingBlocks, "size")).intValue();
                Snapshots.log("idle#2 Adapter updatingBlocks after " + IDLE_TICKS + " ticks", "size=" + size);
                helper.assertEquals(0, size, "Adapter updatingBlocks size");

                Environment host = component[0].host();
                ManagedEnvironment compound = helper
                    .assertInstanceOf(ManagedEnvironment.class, host, "merged component host is not managed");
                helper.assertFalse(compound.canUpdate(), "merged compound environment canUpdate()");

                List<Object> children = compoundChildren(helper, compound);
                GregTechMachineEnvironment ours = null;
                for (Object child : children) {
                    if (child instanceof GregTechMachineEnvironment) {
                        helper.assertNull(ours, "more than one GregScope environment in " + children);
                        ours = (GregTechMachineEnvironment) child;
                    }
                }
                helper.assertNotNull(ours, "no GregScope environment in the merged component: " + children);
                helper.assertFalse(ours.canUpdate(), "GregTechMachineEnvironment.canUpdate()");

            })
            .thenSucceed();
    }

    /**
     * GS-108, design-v0.2 section 1.4: the sampler declares exactly one {@code @SubscribeEvent} method, it takes a
     * {@code ServerTickEvent}, and it only works on phase END.
     */
    @GameTest(batch = BATCH)
    public static void exactlyOneServerTickHandler(GameTestHelper helper) {
        TelemetrySampler sampler = GregScope.sampler();
        helper.assertNotNull(sampler, "GregScope has no sampler; is the server running?");

        List<Method> handlers = new ArrayList<>();
        for (Method method : sampler.getClass()
            .getMethods()) {
            if (method.isAnnotationPresent(SubscribeEvent.class)) {
                handlers.add(method);
            }
        }
        helper.assertEquals(1, handlers.size(), "@SubscribeEvent methods on the sampler: " + handlers);
        Method handler = handlers.get(0);
        helper.assertEquals(1, handler.getParameterTypes().length, "handler arity");
        helper.assertEquals(
            TickEvent.ServerTickEvent.class,
            handler.getParameterTypes()[0],
            "the handler must take a ServerTickEvent");

        SamplerStats stats = sampler.stats();
        long before = stats.ticksTotal();
        sampler.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
        helper.assertEquals(before, stats.ticksTotal(), "phase START must do nothing");
        sampler.onServerTick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
        helper.assertEquals(before + 1L, stats.ticksTotal(), "phase END must run one sampler tick");
        System.out.println("[GregScope gametest] idle#3 one @SubscribeEvent method: " + handler);
        helper.succeed();
    }

    /**
     * GS-114 follow-up, design-v0.2 section 9.1: the second FML-bus hook declares exactly one {@code @SubscribeEvent}
     * method, it takes a {@code PlayerLoggedOutEvent}, and it does not take the tick event or anything else that
     * fires per tick. What the handler <em>does</em> (release the viewer's slot of the open-view cap) is
     * {@code HubGuiServerTests.aDisconnectReleasesTheView}'s job.
     */
    @GameTest(batch = BATCH)
    public static void exactlyOneLogoutHandler(GameTestHelper helper) {
        List<Method> handlers = new ArrayList<>();
        for (Method method : HubViewLifecycle.instance()
            .getClass()
            .getMethods()) {
            if (method.isAnnotationPresent(SubscribeEvent.class)) {
                handlers.add(method);
            }
        }
        helper.assertEquals(1, handlers.size(), "@SubscribeEvent methods on the logout hook: " + handlers);
        Method handler = handlers.get(0);
        helper.assertEquals(1, handler.getParameterTypes().length, "handler arity: " + handler);
        helper.assertEquals(
            PlayerEvent.PlayerLoggedOutEvent.class,
            handler.getParameterTypes()[0],
            "the handler must take a PlayerLoggedOutEvent");
        System.out.println("[GregScope gametest] idle#6 one @SubscribeEvent logout method: " + handler);
        helper.succeed();
    }

    /**
     * GS-110, design-v0.2 sections 8.3 and 8.4: the Forge-bus hook subscribes to exactly the two world events the
     * design names, and to nothing that fires per tick. {@code WorldEvent.PotentialSpawns} is a {@code WorldEvent}
     * too and fires several times per chunk per tick, so a handler taking the base class would put GregScope on a hot
     * path; this asserts that no handler takes it.
     */
    @GameTest(batch = BATCH)
    public static void exactlyTwoWorldEventHandlers(GameTestHelper helper) {
        List<Method> handlers = new ArrayList<>();
        for (Method method : GregScopeWorldEvents.instance()
            .getClass()
            .getMethods()) {
            if (method.isAnnotationPresent(SubscribeEvent.class)) {
                handlers.add(method);
            }
        }
        helper.assertEquals(2, handlers.size(), "@SubscribeEvent methods on the world event hook: " + handlers);
        List<Class<?>> taken = new ArrayList<>();
        for (Method handler : handlers) {
            helper.assertEquals(1, handler.getParameterTypes().length, "handler arity: " + handler);
            Class<?> type = handler.getParameterTypes()[0];
            helper.assertNotSame(WorldEvent.class, type, "a handler takes WorldEvent itself: " + handler);
            taken.add(type);
        }
        helper.assertTrue(taken.contains(WorldEvent.Save.class), "no WorldEvent.Save handler: " + handlers);
        helper.assertTrue(taken.contains(WorldEvent.Unload.class), "no WorldEvent.Unload handler: " + handlers);
        System.out.println("[GregScope gametest] idle#5 two @SubscribeEvent world handlers: " + handlers);
        helper.succeed();
    }

    /**
     * GS-108: with no LIVE sensor the tick handler does no per-sensor work at all. The registry is emptied and a whole
     * interval is then run inside one server tick, so no cover can heartbeat in between and register itself again.
     */
    @GameTest(batch = BATCH)
    public static void handlerDoesNoWorkWithoutLiveSensors(GameTestHelper helper) {
        TelemetrySampler sampler = GregScope.sampler();
        helper.assertNotNull(sampler, "GregScope has no sampler; is the server running?");
        helper.assertTrue(GregScopeTestHooks.purgeAllNow() >= 0, "GregScope test hooks are disabled");
        helper.assertEquals(
            0,
            GregScope.registry()
                .core()
                .size(),
            "the registry is not empty");

        SamplerStats stats = sampler.stats();
        long ticks = stats.ticksTotal();
        long cycles = stats.cyclesTotal();
        long samples = stats.samplesTotal();
        int interval = sampler.schedule()
            .intervalTicks();

        helper.assertTrue(GregScopeTestHooks.runIntervalNow(), "GregScope test hooks are disabled");

        helper.assertEquals(ticks + interval, stats.ticksTotal(), "the handler must still count its ticks");
        helper.assertEquals(cycles, stats.cyclesTotal(), "a tick with no due sensor must do no per-sensor work");
        helper.assertEquals(samples, stats.samplesTotal(), "nothing may be sampled without a live sensor");
        helper.assertEquals(
            0,
            sampler.schedule()
                .size(),
            "the schedule must be empty");
        TelemetryFrame frame = GregScope.frame();
        helper.assertEquals(
            0,
            frame.sensors()
                .size(),
            "the frame published at the interval boundary must be empty");
        System.out.println(
            "[GregScope gametest] idle#4 " + interval
                + " sampler ticks with an empty registry: 0 cycles, 0 samples, frame #"
                + frame.sequence());
        helper.succeed();
    }

    // --- reflection helpers (test code only) ---

    /**
     * Asserts that exactly {@code expected} of the bus's listeners belong to GregScope, counted two independent ways
     * (the listener object's class and the owning mod container), and returns the number of listener objects seen.
     */
    private static int assertGregScopeListeners(GameTestHelper helper, String label, EventBus bus, int expected,
        Class<?>... expectedTypes) {
        Map<?, ?> listeners = (Map<?, ?>) readField(helper, bus, EventBus.class, "listeners");
        Map<?, ?> owners = (Map<?, ?>) readField(helper, bus, EventBus.class, "listenerOwners");
        List<Object> ours = new ArrayList<>();
        for (Object target : listeners.keySet()) {
            Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
            if (isShipped(type.getName())) {
                ours.add(target);
            }
        }
        helper.assertEquals(expected, ours.size(), label + " GregScope listener objects: " + ours);
        int owned = 0;
        for (Map.Entry<?, ?> entry : owners.entrySet()) {
            if (GregScope.MODID.equals(((ModContainer) entry.getValue()).getModId())) {
                owned++;
                helper.assertTrue(
                    ours.contains(entry.getKey()),
                    label + " has a GregScope-owned listener that is not a GregScope class: " + entry.getKey());
            }
        }
        helper.assertEquals(expected, owned, label + " listeners owned by the gregscope mod container");
        helper.assertEquals(expected, expectedTypes.length, label + " expected listener types");
        for (Class<?> expectedType : expectedTypes) {
            List<Object> matches = new ArrayList<>();
            for (Object candidate : ours) {
                if (expectedType.isInstance(candidate)) {
                    matches.add(candidate);
                }
            }
            helper
                .assertEquals(1, matches.size(), label + " listeners of type " + expectedType.getName() + ": " + ours);
            Object found = matches.get(0);
            if (expectedType == TelemetrySampler.class) {
                helper.assertSame(GregScope.sampler(), found, "the subscribed sampler is not GregScope.sampler()");
            } else if (expectedType == GregScopeWorldEvents.class) {
                helper.assertSame(
                    GregScopeWorldEvents.instance(),
                    found,
                    "the subscribed hook is not GregScopeWorldEvents.instance()");
            } else {
                helper.assertSame(
                    HubViewLifecycle.instance(),
                    found,
                    "the subscribed hook is not HubViewLifecycle.instance()");
            }
        }
        return listeners.size();
    }

    /** Every element (and map key and value) of every static Map/Collection field declared by {@code owner}. */
    private static List<Object> staticCollectionElements(GameTestHelper helper, Class<?> owner) {
        List<Object> elements = new ArrayList<>();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Object value = readField(helper, null, owner, field.getName());
            if (value == null || seen.put(value, Boolean.TRUE) != null) {
                continue;
            }
            if (value instanceof Map) {
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    elements.add(entry.getKey());
                    elements.add(entry.getValue());
                }
            } else if (value instanceof Collection) {
                elements.addAll((Collection<?>) value);
            }
        }
        elements.removeIf(e -> e == null);
        return elements;
    }

    private static void assertNotShipped(GameTestHelper helper, String what, String className) {
        helper.assertFalse(isShipped(className), what + " belongs to GregScope: " + className);
    }

    private static boolean isShipped(String className) {
        return className.startsWith(SHIPPED_PACKAGE) && !className.startsWith(TEST_PACKAGE);
    }

    private static List<Object> compoundChildren(GameTestHelper helper, ManagedEnvironment compound) {
        Object seq = call(helper, compound, "environments");
        Iterator<?> it = (Iterator<?>) new ScalaIterator(helper, call(helper, seq, "iterator"));
        List<Object> children = new ArrayList<>();
        while (it.hasNext()) {
            children.add(call(helper, it.next(), "_2"));
        }
        return children;
    }

    private static Object readField(GameTestHelper helper, Object target, Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            helper.fail("cannot read " + owner.getName() + "." + name + ": " + e);
            return null;
        }
    }

    private static Object call(GameTestHelper helper, Object target, String name) {
        try {
            Method method = target.getClass()
                .getMethod(name);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            helper.fail(
                "cannot call " + target.getClass()
                    .getName() + "." + name + "(): " + e);
            return null;
        }
    }

    /** Adapts a {@code scala.collection.Iterator} without compiling against Scala. */
    private static final class ScalaIterator implements Iterator<Object> {

        private final GameTestHelper helper;
        private final Object delegate;

        ScalaIterator(GameTestHelper helper, Object delegate) {
            this.helper = helper;
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return (Boolean) call(helper, delegate, "hasNext");
        }

        @Override
        public Object next() {
            return call(helper, delegate, "next");
        }
    }
}
