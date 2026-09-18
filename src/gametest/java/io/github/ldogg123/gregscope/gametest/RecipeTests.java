package io.github.ldogg123.gregscope.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.objects.OreDictItemStack;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import io.github.ldogg123.gregscope.hub.BlockTelemetryHub;
import io.github.ldogg123.gregscope.recipe.GregScopeRecipes;
import io.github.ldogg123.gregscope.sensor.ItemMachineSensor;

/**
 * GS-117 (design-v0.2 section 12.1): the two assembler recipes, checked against the real
 * {@code RecipeMaps.assemblerRecipes} of a running GTNH dedicated server.
 *
 * <p>
 * Every assertion reads the map, not the builder: {@code getAllRecipes()} is what the assembler, NEI and every recipe
 * remover see, so a recipe that was silently dropped (an ingredient that resolved to null, a collision, a category
 * mismatch) is simply not there, and a recipe that lost an ingredient has a shorter {@code mInputs}.
 *
 * <p>
 * <b>"OreDict entry is empty".</b> The literal GT line is
 * {@code Warning: OreDict entry "<name>" is empty; recipe will be skipped.}, and it comes from exactly one place,
 * {@code GTRecipeBuilder.itemInputs(Object...)} - the overload that takes OreDict <em>names</em>. GregScope does not
 * use it: section 12.1 resolves its OreDict ingredients with {@code GTOreDictUnificator.get} and hands
 * {@code itemInputs(ItemStack...)} real stacks, so that message cannot be produced by GregScope's registration at
 * all. The same failure exists in a quieter form - a null {@code ItemStack} - and
 * {@link #registrationResolvedEveryIngredient(GameTestHelper)} asserts against the record the <b>real</b> postInit
 * registration left behind, then re-resolves the same ingredients under a {@link LogCapture} on the GT loggers to
 * show the names are non-empty now as well.
 *
 * <p>
 * <b>Batch name.</b> {@code gregscope.surface.recipes} sorts after every existing GregScope batch (the GS-109/GS-110
 * rule), so no older test's cell moves. Section 13.2 wrote the batch as {@code gregscope.recipes}, which would sort
 * between {@code gregscope.reload.*} and {@code gregscope.safety} and shift every later cell; the deviation is
 * recorded in the implementation notes. Every test here is synchronous and places nothing, so its own cell is unused.
 */
@GameTestHolder(value = "gregscope", requiredMods = { "gregscope" })
public class RecipeTests {

    private static final String BATCH = "gregscope.surface.recipes";

    /** The assembler's {@code maxIO} item-input limit ({@code RecipeMaps.assemblerRecipes}). */
    private static final int ASSEMBLER_MAX_ITEM_INPUTS = 9;

    /** GT's logger names; {@link LogCapture} also attaches to the root, which both of these propagate to. */
    private static final String GT_LOGGER = "GregTech GTNH";
    private static final String GT_ORE_DICT_LOGGER = "GregTech Ore Dictionary";

    /**
     * Every test here runs its whole body inside its own start and observes 0 ticks, exactly like
     * {@code gregscope.surface.hubgui}, so the batch is charged 20 ticks of worst case instead of Horizon-QA's
     * default 100.
     */
    private static final int SYNCHRONOUS = 20;

    /** The distinctive half of {@code Warning: OreDict entry "x" is empty; recipe will be skipped.} */
    private static final String EMPTY_ORE_DICT = "OreDict entry";
    /** An OreDict name nothing can ever register, for the positive control below. */
    private static final String NO_SUCH_ORE_DICT = "gregscopeNoSuchOreDictEntry";

    private RecipeTests() {}

    // --- the two recipes (design-v0.2 section 12.1) ---

    /** The Machine Sensor: MV, 120 EU/t, 400 ticks, 5 ingredients plus the programmed circuit. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void sensorRecipe(GameTestHelper helper) {
        List<GTRecipe> found = recipesOutputting(ItemMachineSensor.INSTANCE);
        helper.assertEquals(1, found.size(), "Machine Sensor assembler recipes in RecipeMaps.assemblerRecipes");
        GTRecipe recipe = found.get(0);

        helper.assertEquals(GregScopeRecipes.SENSOR_EUT, recipe.mEUt, "Machine Sensor EU/t (TierEU.RECIPE_MV)");
        helper.assertEquals(120, recipe.mEUt, "TierEU.RECIPE_MV is 120");
        helper.assertEquals(GregScopeRecipes.SENSOR_DURATION_TICKS, recipe.mDuration, "Machine Sensor duration");
        helper.assertEquals(400, recipe.mDuration, "20 * SECONDS is 400 ticks");

        assertInputs(
            helper,
            recipe,
            "Machine Sensor",
            GregScopeRecipes.SENSOR_ITEM_INPUTS,
            GregScopeRecipes.sensorIngredients(null),
            new int[] { 1, 1, 1, 2, 2 });

        helper.assertEquals(1, recipe.mOutputs.length, "Machine Sensor outputs");
        helper.assertSame(ItemMachineSensor.INSTANCE, recipe.mOutputs[0].getItem(), "Machine Sensor output item");
        helper.assertEquals(1, recipe.mOutputs[0].stackSize, "Machine Sensor output count");

        helper.assertEquals(1, recipe.mFluidInputs.length, "Machine Sensor fluid inputs");
        FluidStack solder = recipe.mFluidInputs[0];
        helper.assertSame(
            Materials.SolderingAlloy.getMolten(1L)
                .getFluid(),
            solder.getFluid(),
            "Machine Sensor solder fluid");
        helper.assertEquals(
            GregScopeRecipes.SENSOR_SOLDER_MILLIBUCKETS,
            solder.amount,
            "Machine Sensor solder amount (1 * HALF_INGOTS)");
        helper.assertEquals(0, recipe.mFluidOutputs.length, "Machine Sensor fluid outputs");
        helper.succeed();
    }

    /** The Telemetry Hub: EV, 1920 EU/t, 1200 ticks, 8 ingredients plus the circuit, i.e. the assembler's maximum. */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void hubRecipeIsEv(GameTestHelper helper) {
        Item hubItem = Item.getItemFromBlock(BlockTelemetryHub.INSTANCE);
        helper.assertNotNull(hubItem, "the Telemetry Hub has no ItemBlock");
        List<GTRecipe> found = recipesOutputting(hubItem);
        helper.assertEquals(1, found.size(), "Telemetry Hub assembler recipes in RecipeMaps.assemblerRecipes");
        GTRecipe recipe = found.get(0);

        helper.assertEquals(GregScopeRecipes.HUB_EUT, recipe.mEUt, "Telemetry Hub EU/t (TierEU.RECIPE_EV)");
        helper.assertEquals(1920, recipe.mEUt, "TierEU.RECIPE_EV is 1920");
        helper.assertEquals(GregScopeRecipes.HUB_DURATION_TICKS, recipe.mDuration, "Telemetry Hub duration");
        helper.assertEquals(1200, recipe.mDuration, "1 * MINUTES is 1200 ticks");

        assertInputs(
            helper,
            recipe,
            "Telemetry Hub",
            GregScopeRecipes.HUB_ITEM_INPUTS,
            GregScopeRecipes.hubIngredients(null),
            new int[] { 1, 1, 1, 1, 1, 2, 4, 4 });
        helper.assertEquals(
            ASSEMBLER_MAX_ITEM_INPUTS,
            recipe.mInputs.length,
            "the Telemetry Hub recipe fills the assembler's nine input slots exactly");

        helper.assertEquals(1, recipe.mOutputs.length, "Telemetry Hub outputs");
        helper.assertSame(hubItem, recipe.mOutputs[0].getItem(), "Telemetry Hub output item");
        helper.assertEquals(1, recipe.mOutputs[0].stackSize, "Telemetry Hub output count");

        helper.assertEquals(1, recipe.mFluidInputs.length, "Telemetry Hub fluid inputs");
        FluidStack solder = recipe.mFluidInputs[0];
        helper.assertSame(
            Materials.SolderingAlloy.getMolten(1L)
                .getFluid(),
            solder.getFluid(),
            "Telemetry Hub solder fluid");
        helper.assertEquals(
            GregScopeRecipes.HUB_SOLDER_MILLIBUCKETS,
            solder.amount,
            "Telemetry Hub solder amount (2 * INGOTS)");
        helper.assertEquals(0, recipe.mFluidOutputs.length, "Telemetry Hub fluid outputs");
        helper.succeed();
    }

    // --- the acceptance criterion about empty OreDict entries ---

    /**
     * The real postInit registration resolved every section 12.1 ingredient and added exactly one recipe each, and a
     * fresh resolution under a log capture produces no GT "OreDict entry ... is empty" line either.
     */
    @GameTest(batch = BATCH, timeoutTicks = SYNCHRONOUS)
    public static void registrationResolvedEveryIngredient(GameTestHelper helper) {
        GregScopeRecipes.Report report = GregScopeRecipes.report();
        helper.assertTrue(report.ran(), "GregScopeRecipes.install() never ran in postInit");
        helper.assertEquals(
            0,
            report.missingIngredients()
                .size(),
            "ingredients that resolved to null at registration time: " + report.missingIngredients());
        helper.assertEquals(1, report.sensorRecipes(), "recipes addTo() added for the Machine Sensor");
        helper.assertEquals(1, report.hubRecipes(), "recipes addTo() added for the Telemetry Hub");

        try (LogCapture empties = LogCapture.attach(EMPTY_ORE_DICT, GT_LOGGER, GT_ORE_DICT_LOGGER)) {
            List<String> missing = new ArrayList<>();
            ItemStack[] sensor = GregScopeRecipes.sensorIngredients(missing);
            ItemStack[] hub = GregScopeRecipes.hubIngredients(missing);
            helper.assertEquals(0, missing.size(), "ingredients that resolve to null now: " + missing);
            for (int i = 0; i < sensor.length; i++) {
                helper.assertNotNull(sensor[i], "Machine Sensor ingredient " + i);
            }
            for (int i = 0; i < hub.length; i++) {
                helper.assertNotNull(hub[i], "Telemetry Hub ingredient " + i);
            }
            helper.assertEquals(0, empties.count(), "GT logged an empty OreDict entry: " + empties.lines());
        }

        // Positive control, so the capture above cannot pass vacuously: GT's own line, produced by the one overload
        // that can produce it. Nothing reaches a recipe map - itemInputs logs on its own and addTo is never called.
        try (LogCapture control = LogCapture.attach(EMPTY_ORE_DICT, GT_LOGGER, GT_ORE_DICT_LOGGER)) {
            try {
                GTValues.RA.stdBuilder()
                    .itemInputs(new OreDictItemStack(NO_SUCH_ORE_DICT, 1));
            } catch (IllegalArgumentException panic) {
                // GT's panic mode (gt.recipebuilder.panic.null, or the pack's crashOnNullRecipeInput) turns the same
                // condition into a throw, after it has logged the line this control is looking for.
            }
            helper.assertEquals(
                1,
                control.count(),
                "the capture never sees GT's empty-OreDict line, so the assertion above proves nothing: "
                    + control.lines());
        }
        helper.succeed();
    }

    // --- helpers ---

    private static List<GTRecipe> recipesOutputting(Item item) {
        List<GTRecipe> found = new ArrayList<>();
        for (GTRecipe recipe : RecipeMaps.assemblerRecipes.getAllRecipes()) {
            for (ItemStack output : recipe.mOutputs) {
                if (output != null && output.getItem() == item) {
                    found.add(recipe);
                    break;
                }
            }
        }
        return found;
    }

    /**
     * Asserts the item inputs of a registered recipe: the exact count, no holes, the named ingredients in the section
     * 12.1 order with their stack sizes, and the programmed circuit {@code GTRecipeBuilder.circuit} appends last.
     */
    private static void assertInputs(GameTestHelper helper, GTRecipe recipe, String what, int expectedCount,
        ItemStack[] ingredients, int[] sizes) {
        helper.assertEquals(expectedCount, recipe.mInputs.length, what + " item inputs");
        helper.assertTrue(
            recipe.mInputs.length <= ASSEMBLER_MAX_ITEM_INPUTS,
            what + " has more inputs than the assembler's maxIO");
        for (int i = 0; i < recipe.mInputs.length; i++) {
            helper.assertNotNull(recipe.mInputs[i], what + " input slot " + i + " is empty");
        }
        for (int i = 0; i < ingredients.length; i++) {
            ItemStack expected = ingredients[i];
            helper.assertNotNull(expected, what + " ingredient " + i + " does not resolve");
            ItemStack actual = recipe.mInputs[i];
            helper.assertTrue(
                GTUtility.areStacksEqual(expected, actual),
                what + " input " + i + ": expected " + expected + " but found " + actual);
            helper.assertEquals(sizes[i], actual.stackSize, what + " input " + i + " stack size");
        }
        ItemStack circuit = recipe.mInputs[recipe.mInputs.length - 1];
        helper.assertTrue(
            GTUtility.areStacksEqual(ItemList.Circuit_Integrated.getWithDamage(0L, GregScopeRecipes.CIRCUIT), circuit),
            what + " does not end in programmed circuit " + GregScopeRecipes.CIRCUIT + " but in " + circuit);
    }
}
