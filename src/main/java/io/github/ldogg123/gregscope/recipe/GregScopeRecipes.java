package io.github.ldogg123.gregscope.recipe;

import static gregtech.api.util.GTRecipeBuilder.HALF_INGOTS;
import static gregtech.api.util.GTRecipeBuilder.INGOTS;
import static gregtech.api.util.GTRecipeBuilder.MINUTES;
import static gregtech.api.util.GTRecipeBuilder.SECONDS;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.enums.TierEU;
import gregtech.api.objects.SubstituteFluidStack;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.hub.BlockTelemetryHub;
import io.github.ldogg123.gregscope.sensor.ItemMachineSensor;

/**
 * GS-117 (design-v0.2 section 12.1): the two assembler recipes, registered in GregScope's {@code postInit}.
 *
 * <p>
 * <b>Why postInit and not loadComplete.</b> GT clears its postload recipe lists at the end of its own postInit, and
 * GregScope declares {@code required-after:gregtech}, so this runs after GT has filled {@code RecipeMaps} and before
 * anything that reads them.
 *
 * <p>
 * <b>Why every ingredient is resolved here first.</b> {@code GTRecipeBuilder} has two item-input paths. The
 * {@code itemInputs(Object...)} path takes OreDict <em>names</em> and, when a name has no entries, logs
 * {@code Warning: OreDict entry "<name>" is empty; recipe will be skipped.} and drops the recipe. GregScope uses the
 * {@code itemInputs(ItemStack...)} path instead, exactly as section 12.1 writes it, so that message can never come
 * from here - but the same failure still exists in a quieter form: {@code GTOreDictUnificator.get} answers
 * <b>null</b> for an OreDict name with no entries, and {@code ItemList.get} answers null for an item GT never set.
 * A null then silently shortens the recipe's input list (or, if it is trailing, disappears), which is the outcome the
 * acceptance criteria forbid. The resolution is therefore done once, checked, and recorded in {@link #report()} so
 * the in-game {@code RecipeTests} can assert on what the <em>real</em> registration saw.
 *
 * <p>
 * The integrated circuit is appended by {@code GTRecipeBuilder.circuit}, which runs after {@code itemInputs} and puts
 * it last, so the registered recipes have {@link #SENSOR_ITEM_INPUTS} and {@link #HUB_ITEM_INPUTS} item inputs. Nine
 * is the assembler's {@code maxIO} ({@code RecipeMaps.assemblerRecipes}).
 */
public final class GregScopeRecipes {

    /** The assembler's programmed-circuit setting for both recipes (section 12.1). */
    public static final int CIRCUIT = 7;

    /** 5 named ingredients plus the integrated circuit. */
    public static final int SENSOR_ITEM_INPUTS = 6;
    /** 8 named ingredients plus the integrated circuit; the assembler's maximum. */
    public static final int HUB_ITEM_INPUTS = 9;

    /** 20 s (section 12.1). */
    public static final int SENSOR_DURATION_TICKS = 20 * SECONDS;
    /** 1 min (section 12.1). */
    public static final int HUB_DURATION_TICKS = 1 * MINUTES;

    /** {@code TierEU.RECIPE_MV}, i.e. {@code GTValues.VP[2]} = 128 * 30 / 32. */
    public static final int SENSOR_EUT = 120;
    /** {@code TierEU.RECIPE_EV}, i.e. {@code GTValues.VP[4]} = 2048 * 30 / 32. */
    public static final int HUB_EUT = 1920;

    /** Half an ingot of soldering alloy, the cheapest of the {@link SubstituteFluidStack#soldering(long)} set. */
    public static final int SENSOR_SOLDER_MILLIBUCKETS = 1 * HALF_INGOTS;
    /** Two ingots of molten soldering alloy. */
    public static final int HUB_SOLDER_MILLIBUCKETS = 2 * INGOTS;

    private static volatile Report report = Report.NOT_RUN;

    private GregScopeRecipes() {}

    /**
     * What the one real registration did. Never null; {@link Report#ran()} is false until {@link #install()} has run.
     */
    public static Report report() {
        return report;
    }

    /**
     * postInit: build both recipes and add them to {@code RecipeMaps.assemblerRecipes}. Called once, from
     * {@code GregScope.postInit}.
     */
    public static void install() {
        List<String> missing = new ArrayList<>();
        ItemStack[] sensorInputs = sensorIngredients(missing);
        ItemStack[] hubInputs = hubIngredients(missing);
        if (!missing.isEmpty()) {
            // Not a crash: a pack that really has removed one of these items should still load, and the in-game
            // RecipeTests turn this into a failure on the packs GregScope supports.
            GregScope.LOG.error(
                "GregScope cannot build its assembler recipes: {} did not resolve to an item. The recipes that need"
                    + " them are not registered; the Machine Sensor and the Telemetry Hub stay creative-only.",
                missing);
        }

        int sensorAdded = 0;
        if (!containsNull(sensorInputs)) {
            Collection<GTRecipe> added = GTValues.RA.stdBuilder()
                .itemInputs(sensorInputs)
                .circuit(CIRCUIT)
                .fluidInputs(SubstituteFluidStack.soldering(SENSOR_SOLDER_MILLIBUCKETS))
                .itemOutputs(new ItemStack(ItemMachineSensor.INSTANCE, 1))
                .duration(SENSOR_DURATION_TICKS)
                .eut(TierEU.RECIPE_MV)
                .addTo(RecipeMaps.assemblerRecipes);
            sensorAdded = added.size();
        }

        int hubAdded = 0;
        if (!containsNull(hubInputs)) {
            Collection<GTRecipe> added = GTValues.RA.stdBuilder()
                .itemInputs(hubInputs)
                .circuit(CIRCUIT)
                .fluidInputs(Materials.SolderingAlloy.getMolten(HUB_SOLDER_MILLIBUCKETS))
                .itemOutputs(new ItemStack(BlockTelemetryHub.INSTANCE, 1))
                .duration(HUB_DURATION_TICKS)
                .eut(TierEU.RECIPE_EV)
                .addTo(RecipeMaps.assemblerRecipes);
            hubAdded = added.size();
        }

        report = new Report(sensorAdded, hubAdded, missing);
        GregScope.LOG.info(
            "GregScope registered {} Machine Sensor and {} Telemetry Hub assembler recipes ({} unresolved"
                + " ingredients)",
            Integer.valueOf(sensorAdded),
            Integer.valueOf(hubAdded),
            Integer.valueOf(missing.size()));
    }

    /**
     * The Machine Sensor's five named ingredients, in the section 12.1 order. The integrated circuit is not here:
     * {@code GTRecipeBuilder.circuit} appends it.
     *
     * @param missing every ingredient that resolved to null is named here
     */
    public static ItemStack[] sensorIngredients(List<String> missing) {
        return new ItemStack[] {
            require(ItemList.Cover_ActivityDetector.get(1L), "ItemList.Cover_ActivityDetector", missing),
            require(ItemList.Sensor_MV.get(1L), "ItemList.Sensor_MV", missing),
            require(GTOreDictUnificator.get(OrePrefixes.circuit, Materials.MV, 1L), "circuitGood", missing),
            require(GTOreDictUnificator.get(OrePrefixes.plate, Materials.Aluminium, 2L), "plateAluminium", missing),
            require(GTOreDictUnificator.get(OrePrefixes.cableGt01, Materials.Copper, 2L), "cableGt01Copper", missing) };
    }

    /**
     * The Telemetry Hub's eight named ingredients, in the section 12.1 order.
     *
     * @param missing every ingredient that resolved to null is named here
     */
    public static ItemStack[] hubIngredients(List<String> missing) {
        return new ItemStack[] { require(ItemList.Hull_EV.get(1L), "ItemList.Hull_EV", missing),
            require(ItemList.Cover_Screen.get(1L), "ItemList.Cover_Screen", missing),
            require(ItemList.Sensor_EV.get(1L), "ItemList.Sensor_EV", missing),
            require(ItemList.Emitter_EV.get(1L), "ItemList.Emitter_EV", missing),
            require(ItemList.Tool_DataStick.get(1L), "ItemList.Tool_DataStick", missing),
            require(GTOreDictUnificator.get(OrePrefixes.circuit, Materials.EV, 2L), "circuitData", missing),
            require(GTOreDictUnificator.get(OrePrefixes.plate, Materials.Titanium, 4L), "plateTitanium", missing),
            require(
                GTOreDictUnificator.get(OrePrefixes.cableGt01, Materials.Aluminium, 4L),
                "cableGt01Aluminium",
                missing) };
    }

    private static ItemStack require(ItemStack stack, String name, List<String> missing) {
        if (stack == null && missing != null) {
            missing.add(name);
        }
        return stack;
    }

    private static boolean containsNull(ItemStack[] stacks) {
        for (ItemStack stack : stacks) {
            if (stack == null) {
                return true;
            }
        }
        return false;
    }

    /** What {@link GregScopeRecipes#install()} did, for the in-game {@code RecipeTests}. Immutable. */
    public static final class Report {

        static final Report NOT_RUN = new Report(-1, -1, Collections.<String>emptyList());

        private final int sensorRecipes;
        private final int hubRecipes;
        private final List<String> missingIngredients;

        Report(int sensorRecipes, int hubRecipes, List<String> missingIngredients) {
            this.sensorRecipes = sensorRecipes;
            this.hubRecipes = hubRecipes;
            this.missingIngredients = Collections.unmodifiableList(
                new ArrayList<String>(
                    missingIngredients == null ? Collections.<String>emptyList() : missingIngredients));
        }

        /** False before {@code postInit} has run. */
        public boolean ran() {
            return sensorRecipes >= 0;
        }

        /** How many recipes {@code addTo} really added for the Machine Sensor; 1 on a healthy pack. */
        public int sensorRecipes() {
            return sensorRecipes;
        }

        /** How many recipes {@code addTo} really added for the Telemetry Hub; 1 on a healthy pack. */
        public int hubRecipes() {
            return hubRecipes;
        }

        /**
         * The names of the section 12.1 ingredients that resolved to null at registration time - GregScope's own
         * equivalent of GT's "OreDict entry is empty". Empty on a healthy pack; never null.
         */
        public List<String> missingIngredients() {
            return missingIngredients;
        }

        @Override
        public String toString() {
            return "Report[sensor=" + sensorRecipes
                + ", hub="
                + hubRecipes
                + ", missing="
                + Arrays.toString(missingIngredients.toArray())
                + "]";
        }
    }
}
