package io.github.ldogg123.gregscope.gametest;

import static com.gtnewhorizons.horizonqa.api.TestPos.at;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;

import com.gtnewhorizon.gtnhlib.teams.Team;
import com.gtnewhorizon.gtnhlib.teams.TeamManager;
import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.covers.CoverPlacer;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Textures;
import gregtech.api.enums.TierEU;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.common.covers.Cover;
import io.github.ldogg123.gregscope.sensor.MachineSensorCover;

/**
 * GS-119, design-v0.2 section 14 and the section 18 risk row: the shape of the third-party API GregScope is built on,
 * asserted against the real classes the server loaded.
 *
 * <p>
 * <b>What this is for, and what it deliberately is not.</b> Most of GregScope's coupling is checked by the compiler
 * on every build: if GT renames a method GregScope overrides, {@code compileJava} fails and nobody needs a test. The
 * cases worth a test are the ones a green compile hides, and there are three of them here.
 *
 * <ul>
 * <li><b>A permission method GT <em>adds</em>.</b> Design-v0.2 section 1.4 promises a sensor is transparent: every
 * {@code lets*} on the cover returns true, so a covered face still passes items, fluids, EU and redstone. That
 * promise is kept by overriding each one. If a GT bump adds a ninth {@code lets*}, GregScope inherits GT's default
 * for it, a covered face silently starts blocking something, and <em>nothing fails to compile</em>.
 * {@link #everyLetsMethodOnCoverIsOverriddenAndPermissive} is the guard: it enumerates the set from the loaded
 * {@code Cover} class rather than from a list in this file.</li>
 * <li><b>A constant whose value changes.</b> {@code TierEU.RECIPE_MV} is a compile-time constant, so the recipes
 * carry the number GregScope was <em>built</em> against, not the one GT now means by it.</li>
 * <li><b>A class that moves.</b> {@code gregtech.common.covers.Cover} is not in an API package and carries no
 * compatibility promise, so the package it lives in is worth pinning explicitly.</li>
 * </ul>
 *
 * <p>
 * Reflection is used here, in test code, which design-v0.2 section 1.4 allows and no shipped class does. Every
 * failure names the declaring class and the signature it found, so a bump says what changed rather than that
 * something did.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class GtApiShapeTests {

    private static final String BATCH = "gregscope.api.shape";

    /** Every test here is synchronous: it places a machine, reflects and returns inside its own start. */
    private static final int SYNCHRONOUS = 20;

    /** Where the sensor under test goes; a free input face of the machine {@link #prepareMachine} sets up. */
    private static final ForgeDirection COVERED = ForgeDirection.EAST;
    private static final ForgeDirection MAIN_FACING = ForgeDirection.NORTH;
    private static final ForgeDirection FRONT_FACING = ForgeDirection.SOUTH;

    /**
     * The eight {@code lets*} methods {@code gregtech.common.covers.Cover} declared when GS-119 was written, against
     * GT5U 5.09.54.133. This is the <em>expected</em> set; the test reads the real one off the class and compares, so
     * a GT bump that adds or removes one fails here with both sets printed.
     */
    private static final List<String> EXPECTED_LETS_METHODS = Arrays.asList(
        "letsEnergyIn()",
        "letsEnergyOut()",
        "letsFluidIn(net.minecraftforge.fluids.Fluid)",
        "letsFluidOut(net.minecraftforge.fluids.Fluid)",
        "letsItemsIn(int)",
        "letsItemsOut(int)",
        "letsRedstoneGoIn()",
        "letsRedstoneGoOut()");

    private GtApiShapeTests() {}

    /**
     * The transparency guarantee of design-v0.2 section 1.4, enforced against the real class rather than a list.
     *
     * <p>
     * Three steps, and the first is the one that catches a bump: the {@code lets*} set is read off the loaded
     * {@code Cover} class and compared with {@link #EXPECTED_LETS_METHODS}, so a method GT adds or removes fails
     * here. Then every one of them must be overridden by {@link MachineSensorCover} - inheriting GT's default is
     * exactly the silent failure this test exists for. Then each is invoked on a sensor really attached to a machine
     * and must answer true.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void everyLetsMethodOnCoverIsOverriddenAndPermissive(GameTestHelper helper) {
        TreeSet<String> found = new TreeSet<>();
        for (Method method : Cover.class.getMethods()) {
            if (method.getName()
                .startsWith("lets") && !method.isSynthetic()) {
                found.add(signature(method));
            }
        }
        helper.assertEquals(
            new TreeSet<>(EXPECTED_LETS_METHODS).toString(),
            found.toString(),
            "the set of lets* methods on " + Cover.class.getName()
                + " changed. Every one of them must be overridden by MachineSensorCover and return true, or a covered "
                + "face silently stops passing something (design-v0.2 section 1.4). Override the new one, then update "
                + "EXPECTED_LETS_METHODS");

        IGregTechTileEntity holder = prepareMachine(helper);
        helper.assertTrue(
            SensorPlacementTests.placeViaCoverPlacer(helper, holder, COVERED),
            "the sensor was refused on " + COVERED);
        MachineSensorCover cover = helper
            .assertInstanceOf(MachineSensorCover.class, holder.getCoverAtSide(COVERED), "sensor cover on " + COVERED);

        List<String> notOverridden = new ArrayList<>();
        for (Method method : Cover.class.getMethods()) {
            if (!method.getName()
                .startsWith("lets") || method.isSynthetic()) {
                continue;
            }
            Method actual = declaredOn(cover.getClass(), method);
            if (actual == null || actual.getDeclaringClass() == Cover.class) {
                notOverridden.add(signature(method));
                continue;
            }
            Object answer = invoke(helper, actual, cover, method.getParameterTypes());
            helper.assertEquals(
                Boolean.TRUE,
                answer,
                signature(method) + " answered "
                    + answer
                    + " on an attached sensor; design-v0.2 section 1.4 requires "
                    + "every lets* to be permissive so a covered face stays transparent");
        }
        helper.assertTrue(
            notOverridden.isEmpty(),
            "MachineSensorCover does not override " + notOverridden
                + "; it would inherit GT's default, which is the "
                + "silent transparency break this test exists to catch");
        Snapshots.log("apishape#lets", found.size() + " lets* methods, all overridden and permissive");
        helper.succeed();
    }

    /**
     * The rest of the {@code Cover} surface GregScope overrides or calls, with the class that declares each. The
     * compiler already requires these to exist; what this pins is <b>where</b> they live, so a bump that moves one to
     * another class in the hierarchy is visible rather than merely still-compiling.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void theCoverMembersGregScopeOverridesStillLiveOnCover(GameTestHelper helper) {
        helper.assertEquals(
            "gregtech.common.covers",
            Cover.class.getPackage()
                .getName(),
            "Cover moved package. It is not an API class and carries no compatibility promise, which is why "
                + "design-v0.2 section 18 lists it as a risk and keeps all coupling to it in two classes");

        assertDeclared(helper, Cover.class, "saveDataToNbt", NBTBase.class);
        assertDeclared(helper, Cover.class, "readDataFromNbt", void.class, NBTBase.class);
        assertDeclared(helper, Cover.class, "getDescription", String.class);
        assertDeclared(helper, Cover.class, "allowsCopyPasteTool", boolean.class);
        assertDeclared(helper, Cover.class, "allowsTickRateAddition", boolean.class);
        assertDeclared(helper, Cover.class, "hasCoverGUI", boolean.class);
        assertDeclared(helper, Cover.class, "isRedstoneSensitive", boolean.class, long.class);
        assertDeclared(helper, Cover.class, "manipulatesSidedRedstoneOutput", boolean.class);
        assertDeclared(helper, Cover.class, "getMinimumTickRate", int.class);
        assertDeclared(helper, Cover.class, "doCoverThings", void.class, byte.class, long.class);
        assertDeclared(helper, Cover.class, "onCoverRemoval", void.class);
        assertDeclared(helper, Cover.class, "onCoverUnload", void.class);
        assertDeclared(helper, Cover.class, "onBaseTEDestroyed", void.class);
        assertDeclared(helper, Cover.class, "onPlayerAttach", void.class, EntityPlayer.class, ItemStack.class);
        assertDeclared(helper, Cover.class, "isValid", boolean.class);

        // The two the probe and the Hub read a cover back through.
        assertPresent(helper, ICoverable.class, "getCoverAtSide", Cover.class, ForgeDirection.class);
        assertPresent(helper, ICoverable.class, "hasCoverAtSide", boolean.class, ForgeDirection.class);
        helper.succeed();
    }

    /** The registration path of design-v0.2 section 3.2: the placer builder and the icon container. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void theCoverRegistrationApiStillHasTheShapeSensorCoversUses(GameTestHelper helper) {
        Object builder = CoverPlacer.builder();
        helper.assertNotNull(builder, "CoverPlacer.builder() returned null");
        assertPresent(helper, builder.getClass(), "build", CoverPlacer.class);
        helper.assertNotNull(
            findByName(builder.getClass(), "onlyPlaceIf"),
            "CoverPlacer.builder() has no onlyPlaceIf; design-v0.2 section 3.2's placement predicate hangs off it");

        // Building the icon container on a dedicated server must stay safe: GT only queues it for icon registration.
        IIconContainer icon = Textures.BlockIcons.custom("gregscope", "gs_shape_probe");
        helper.assertNotNull(icon, "Textures.BlockIcons.custom returned null on a dedicated server");
        helper.succeed();
    }

    /**
     * {@code TierEU.RECIPE_MV} and {@code RECIPE_EV} are compile-time constants, so GS-117's recipes carry the values
     * GregScope was <b>built</b> against. Reading them from the loaded class is the only way a value change shows up.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void theRecipeTierConstantsStillMeanWhatTheRecipesAssume(GameTestHelper helper) {
        helper.assertEquals(120L, TierEU.RECIPE_MV, "TierEU.RECIPE_MV; the Machine Sensor recipe is registered at it");
        helper.assertEquals(1920L, TierEU.RECIPE_EV, "TierEU.RECIPE_EV; the Telemetry Hub recipe is registered at it");
        helper.assertNotNull(ItemList.Hull_EV.get(1L), "ItemList.Hull_EV no longer resolves");
        helper.assertNotNull(ItemList.Sensor_MV.get(1L), "ItemList.Sensor_MV no longer resolves");
        helper.succeed();
    }

    /**
     * The GTNHLib team API {@code GtnhlibTeamResolver} uses. Design-v0.2's decision record pins the by-map scan
     * rather than GTNHLib's by-player lookup, so the two members that matter are the map and {@code Team.isMember}.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void theGtnhlibTeamApiStillHasTheShapeTheResolverUses(GameTestHelper helper) {
        Map<?, ?> teams = TeamManager.getTeamMap();
        helper.assertNotNull(teams, "TeamManager.getTeamMap() returned null; the resolver scans its values()");
        assertPresent(helper, Team.class, "isMember", boolean.class, UUID.class);
        assertPresent(helper, Team.class, "isOfficer", boolean.class, UUID.class);
        assertPresent(helper, Team.class, "isOwner", boolean.class, UUID.class);
        helper.succeed();
    }

    // --- helpers ---

    /** A basic machine with a known facing, so {@link #COVERED} is a face GT itself passes items and fluids through. */
    private static IGregTechTileEntity prepareMachine(GameTestHelper helper) {
        IGregTechTileEntity holder = GtPlacement
            .placeMachine(helper, at(1, 0, 1), ItemList.Machine_LV_ChemicalReactor.get(1L));
        MTEBasicMachine mte = helper
            .assertInstanceOf(MTEBasicMachine.class, holder.getMetaTileEntity(), "basic machine");
        helper.assertTrue(mte.setMainFacing(MAIN_FACING), "GT refused the main facing " + MAIN_FACING);
        holder.setFrontFacing(FRONT_FACING);
        helper.assertTrue(mte.isLiquidInput(COVERED), COVERED + " is not a free input face after the setup");
        return holder;
    }

    /** Asserts {@code owner} itself declares the member, not merely inherits it. */
    private static void assertDeclared(GameTestHelper helper, Class<?> owner, String name, Class<?> returnType,
        Class<?>... params) {
        Method method = null;
        try {
            method = owner.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            helper.fail(
                owner.getName() + " no longer declares "
                    + name
                    + Arrays.toString(params)
                    + ". It declares: "
                    + namesOf(owner, name));
        }
        helper.assertEquals(
            returnType,
            method.getReturnType(),
            owner.getName() + "." + name + " return type; GregScope overrides or calls it");
    }

    /** Asserts the member is callable on {@code owner}, declared there or inherited. */
    private static void assertPresent(GameTestHelper helper, Class<?> owner, String name, Class<?> returnType,
        Class<?>... params) {
        Method method = null;
        try {
            method = owner.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            helper.fail(
                owner.getName() + " has no " + name + Arrays.toString(params) + ". It has: " + namesOf(owner, name));
        }
        helper.assertEquals(returnType, method.getReturnType(), owner.getName() + "." + name + " return type");
    }

    /** Every overload of {@code name} on {@code owner}, for a failure message that says what is there instead. */
    private static String namesOf(Class<?> owner, String name) {
        TreeSet<String> all = new TreeSet<>();
        for (Method method : owner.getMethods()) {
            if (method.getName()
                .equals(name)) {
                all.add(signature(method));
            }
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName()
                .equals(name)) {
                all.add(signature(method));
            }
        }
        return all.isEmpty() ? "no overload of that name at all" : all.toString();
    }

    private static Method findByName(Class<?> owner, String name) {
        for (Method method : owner.getMethods()) {
            if (method.getName()
                .equals(name)) {
                return method;
            }
        }
        return null;
    }

    /** The most-derived declaration of {@code method} at or above {@code type}. */
    private static Method declaredOn(Class<?> type, Method method) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException ignored) {
                // keep walking up
            }
        }
        return null;
    }

    /** Invokes a no-argument or single-argument {@code lets*} with a harmless argument. */
    private static Object invoke(GameTestHelper helper, Method method, Object target, Class<?>[] params) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (params[i] == int.class) {
                args[i] = Integer.valueOf(0);
            } else if (params[i] == Fluid.class) {
                args[i] = null;
            } else {
                helper.fail(
                    "a lets* method takes " + params[i].getName()
                        + ", which this test does not know how to call; "
                        + "teach it an argument rather than skipping the method");
            }
        }
        try {
            if (!method.isAccessible() && !Modifier.isPublic(method.getModifiers())) {
                method.setAccessible(true);
            }
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            helper.fail(signature(method) + " could not be invoked: " + e);
            return null;
        }
    }

    private static String signature(Method method) {
        StringBuilder out = new StringBuilder(method.getName()).append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(params[i].getName());
        }
        return out.append(')')
            .toString();
    }
}
