package io.github.ldogg123.gregscope.probe;

import java.util.ArrayList;
import java.util.List;

import io.github.ldogg123.gregscope.buffers.BufferLines;
import io.github.ldogg123.gregscope.buffers.BufferSet;
import io.github.ldogg123.gregscope.model.MachineKind;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.model.StatusIds;
import io.github.ldogg123.gregscope.probe.Classification.TextSource;

/** Turns raw readings into a schema v1 snapshot: classification, status text, warnings, gating and derived values. */
public final class SnapshotBuilder {

    private SnapshotBuilder() {}

    /**
     * @throws IllegalStateException if a required reading (kind, name, metaName, machineClass) is missing
     */
    public static MachineSnapshot build(MachineReadings r) {
        Classification c = StateClassifier.classify(r);
        boolean multi = r.kind() == MachineKind.MULTIBLOCK;
        boolean basic = r.kind() == MachineKind.SINGLEBLOCK;

        MachineSnapshot.Builder b = MachineSnapshot.builder()
            .kind(r.kind())
            .state(c.state())
            .statusId(c.statusId())
            .statusText(statusText(r, c))
            .name(r.name())
            .metaName(r.metaName())
            .metaId(r.metaId())
            .machineClass(r.machineClass())
            .dimension(r.dimension())
            .x(r.x())
            .y(r.y())
            .z(r.z())
            .active(r.active())
            .allowedToWork(r.allowedToWork())
            .hasThingsToDo(r.hasThingsToDo())
            .wasShutdown(r.wasShutdown())
            .progressTicks(r.progressTicks())
            .maxProgressTicks(r.maxProgressTicks())
            .progress(progress(r.progressTicks(), r.maxProgressTicks()))
            .warnings(warnings(r, c, multi));

        emitBuffers(b, r);

        if (r.wasShutdown()) {
            b.shutdownReasonId(r.shutdownReasonId() == null ? StatusIds.NONE : r.shutdownReasonId());
            b.shutdownCritical(r.shutdownCritical());
        }
        if (r.active() && r.euPerTick() != null) {
            b.euPerTick(r.euPerTick());
        }
        if (r.energyStored() != null) {
            b.energyStored(r.energyStored());
        }
        if (r.energyCapacity() != null) {
            b.energyCapacity(r.energyCapacity());
        }

        if (basic) {
            if (r.active() && r.steamPerTick() != null) {
                b.steamPerTick(r.steamPerTick());
            }
            if (r.steamStored() != null) {
                b.steamStored(r.steamStored());
            }
            if (r.steamCapacity() != null) {
                b.steamCapacity(r.steamCapacity());
            }
            if (r.outputBlockedTicks() != null) {
                b.outputBlockedTicks(r.outputBlockedTicks());
            }
            if (r.stuttering() != null) {
                b.stuttering(r.stuttering());
            }
        }

        if (multi) {
            if (r.formed() != null) {
                b.formed(r.formed());
            }
            if (r.maintenanceIssues() != null) {
                b.maintenanceIssues(r.maintenanceIssues());
            }
            if (r.maintenanceChecksEnabled() != null) {
                b.maintenanceChecksEnabled(r.maintenanceChecksEnabled());
            }
            if (r.efficiency() != null) {
                b.efficiency(r.efficiency());
            }
            if (r.pollutionEmissionFactor() != null) {
                b.pollutionEmissionFactor(r.pollutionEmissionFactor());
            }
            if (r.recipesCompleted() != null) {
                b.recipesCompleted(r.recipesCompleted());
            }
            if (r.controllerAgeTicks() != null) {
                b.controllerAgeTicks(r.controllerAgeTicks());
            }
            if (!r.active() && r.controllerAgeTicks() != null && r.lastWorkingTick() != null) {
                b.ticksSinceLastWork(Math.max(0L, r.controllerAgeTicks() - r.lastWorkingTick()));
            }
            if (r.recipeCheckResultId() != null) {
                b.recipeCheckResultId(r.recipeCheckResultId());
            }
            if (r.recipeCheckSuccessful() != null) {
                b.recipeCheckSuccessful(r.recipeCheckSuccessful());
            }
            b.recipeCheckResultText(Text.firstNonEmpty(r.recipeCheckResultText()));
        }
        return b.build();
    }

    /**
     * Design-v0.3-buffers section 3. A direction with nothing to report writes nothing, so a machine that holds no
     * buffers at all looks exactly as it did before v0.3 rather than gaining a row of zeroes.
     */
    private static void emitBuffers(MachineSnapshot.Builder b, MachineReadings r) {
        BufferSet in = r.inputs();
        if (in != null && !in.isEmpty()) {
            b.inputs(BufferLines.of(in))
                .inputTotal(in.totalAmount())
                .inputCapacity(in.totalCapacity())
                .inputSaturation(in.saturation());
            if (in.meBacked() > 0) {
                b.meInputs(in.meBacked());
            }
        }
        BufferSet out = r.outputs();
        if (out != null && !out.isEmpty()) {
            b.outputs(BufferLines.of(out))
                .outputTotal(out.totalAmount())
                .outputCapacity(out.totalCapacity())
                .outputSaturation(out.saturation());
        }
    }

    static double progress(int progressTicks, int maxProgressTicks) {
        if (maxProgressTicks <= 0) {
            return 0.0;
        }
        double ratio = progressTicks / (double) maxProgressTicks;
        return Math.max(0.0, Math.min(1.0, ratio));
    }

    private static String statusText(MachineReadings r, Classification c) {
        String sourceText = null;
        String ownText = null;
        if (c.textSource() == TextSource.SHUTDOWN_REASON) {
            sourceText = r.shutdownReasonText();
        } else if (c.textSource() == TextSource.RECIPE_CHECK) {
            sourceText = r.recipeCheckResultText();
        } else {
            ownText = StatusTexts.forStatusId(c.statusId());
        }
        return Text.firstNonEmpty(sourceText, ownText, StatusTexts.forState(c.state()), c.statusId());
    }

    private static List<String> warnings(MachineReadings r, Classification c, boolean multi) {
        List<String> warnings = new ArrayList<>(2);
        if (multi && r.maintenanceIssues() != null && r.maintenanceIssues() > 0) {
            warnings.add(StatusIds.WARNING_MAINTENANCE);
        }
        if (c.state() == MachineState.RUNNING && !r.allowedToWork()) {
            warnings.add(StatusIds.WARNING_WORK_DISABLED);
        }
        return warnings;
    }
}
