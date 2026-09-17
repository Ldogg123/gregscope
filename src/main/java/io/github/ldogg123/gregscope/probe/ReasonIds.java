package io.github.ldogg123.gregscope.probe;

import javax.annotation.Nullable;

import io.github.ldogg123.gregscope.model.StatusIds;

/** Normalization of GT shutdown-reason and recipe-check IDs. IDs are opaque and passed through verbatim. */
public final class ReasonIds {

    private ReasonIds() {}

    /**
     * GT's simple reasons/results all report the ID {@code simple_result}; their key is the real discriminator.
     *
     * @return key if non-empty, else id if non-empty and not {@code simple_result}, else {@code none}
     */
    public static String normalize(@Nullable String key, @Nullable String id) {
        if (key != null && !key.isEmpty()) {
            return key;
        }
        if (id != null && !id.isEmpty() && !StatusIds.SIMPLE_RESULT.equals(id)) {
            return id;
        }
        return StatusIds.NONE;
    }

    public static boolean isOutputFull(@Nullable String id) {
        return StatusIds.ITEM_OUTPUT_FULL.equals(id) || StatusIds.FLUID_OUTPUT_FULL.equals(id);
    }

    /** Whether a recipe-check result is routine for a machine that is not running. */
    public static boolean isBenignWhenIdle(@Nullable String id, boolean successful) {
        return successful || id == null || StatusIds.NONE.equals(id) || StatusIds.NO_RECIPE.equals(id);
    }
}
