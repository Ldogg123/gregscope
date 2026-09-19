package io.github.ldogg123.gregscope.probe;

import javax.annotation.Nullable;

import io.github.ldogg123.gregscope.buffers.BufferSet;
import io.github.ldogg123.gregscope.model.MachineKind;

/**
 * Raw values read from a GT machine, before classification. Boxed fields are optional and null when not read. Mutable
 * and not thread-safe; create one per snapshot.
 */
public final class MachineReadings {

    private MachineKind kind;
    private String name;
    private String metaName;
    private int metaId;
    private String machineClass;
    private int dimension;
    private int x;
    private int y;
    private int z;
    private boolean active;
    private boolean allowedToWork;
    private boolean hasThingsToDo;
    private boolean wasShutdown;
    private int progressTicks;
    private int maxProgressTicks;

    private String shutdownReasonId;
    private boolean shutdownCritical;
    private String shutdownReasonText;

    private Long euPerTick;
    private Long steamPerTick;
    private Long energyStored;
    private Long energyCapacity;
    private Long steamStored;
    private Long steamCapacity;

    private boolean steamPowered;
    private Boolean stuttering;
    private BufferSet inputs;
    private BufferSet outputs;
    private Integer outputBlockedTicks;
    private boolean steamVentBlocked;
    private boolean machineErrors;

    private boolean startupPending;
    private Boolean formed;
    private Integer maintenanceIssues;
    private Boolean maintenanceChecksEnabled;
    private Double efficiency;
    private Double pollutionEmissionFactor;
    private Long recipesCompleted;
    private Long controllerAgeTicks;
    private Long lastWorkingTick;
    private String recipeCheckResultId;
    private Boolean recipeCheckSuccessful;
    private boolean recipeCheckPersistsOnShutdown;
    private String recipeCheckResultText;

    public MachineKind kind() {
        return kind;
    }

    public MachineReadings kind(MachineKind kind) {
        this.kind = kind;
        return this;
    }

    public String name() {
        return name;
    }

    public MachineReadings name(String name) {
        this.name = name;
        return this;
    }

    public String metaName() {
        return metaName;
    }

    public MachineReadings metaName(String metaName) {
        this.metaName = metaName;
        return this;
    }

    public int metaId() {
        return metaId;
    }

    public MachineReadings metaId(int metaId) {
        this.metaId = metaId;
        return this;
    }

    public String machineClass() {
        return machineClass;
    }

    public MachineReadings machineClass(String machineClass) {
        this.machineClass = machineClass;
        return this;
    }

    public int dimension() {
        return dimension;
    }

    public MachineReadings dimension(int dimension) {
        this.dimension = dimension;
        return this;
    }

    public int x() {
        return x;
    }

    public MachineReadings x(int x) {
        this.x = x;
        return this;
    }

    public int y() {
        return y;
    }

    public MachineReadings y(int y) {
        this.y = y;
        return this;
    }

    public int z() {
        return z;
    }

    public MachineReadings z(int z) {
        this.z = z;
        return this;
    }

    public boolean active() {
        return active;
    }

    public MachineReadings active(boolean active) {
        this.active = active;
        return this;
    }

    public boolean allowedToWork() {
        return allowedToWork;
    }

    public MachineReadings allowedToWork(boolean allowedToWork) {
        this.allowedToWork = allowedToWork;
        return this;
    }

    public boolean hasThingsToDo() {
        return hasThingsToDo;
    }

    public MachineReadings hasThingsToDo(boolean hasThingsToDo) {
        this.hasThingsToDo = hasThingsToDo;
        return this;
    }

    public boolean wasShutdown() {
        return wasShutdown;
    }

    public MachineReadings wasShutdown(boolean wasShutdown) {
        this.wasShutdown = wasShutdown;
        return this;
    }

    public int progressTicks() {
        return progressTicks;
    }

    public MachineReadings progressTicks(int progressTicks) {
        this.progressTicks = progressTicks;
        return this;
    }

    public int maxProgressTicks() {
        return maxProgressTicks;
    }

    public MachineReadings maxProgressTicks(int maxProgressTicks) {
        this.maxProgressTicks = maxProgressTicks;
        return this;
    }

    @Nullable
    public String shutdownReasonId() {
        return shutdownReasonId;
    }

    public MachineReadings shutdownReasonId(@Nullable String shutdownReasonId) {
        this.shutdownReasonId = shutdownReasonId;
        return this;
    }

    public boolean shutdownCritical() {
        return shutdownCritical;
    }

    public MachineReadings shutdownCritical(boolean shutdownCritical) {
        this.shutdownCritical = shutdownCritical;
        return this;
    }

    @Nullable
    public String shutdownReasonText() {
        return shutdownReasonText;
    }

    public MachineReadings shutdownReasonText(@Nullable String shutdownReasonText) {
        this.shutdownReasonText = shutdownReasonText;
        return this;
    }

    @Nullable
    public Long euPerTick() {
        return euPerTick;
    }

    public MachineReadings euPerTick(@Nullable Long euPerTick) {
        this.euPerTick = euPerTick;
        return this;
    }

    @Nullable
    public Long steamPerTick() {
        return steamPerTick;
    }

    public MachineReadings steamPerTick(@Nullable Long steamPerTick) {
        this.steamPerTick = steamPerTick;
        return this;
    }

    @Nullable
    public Long energyStored() {
        return energyStored;
    }

    public MachineReadings energyStored(@Nullable Long energyStored) {
        this.energyStored = energyStored;
        return this;
    }

    @Nullable
    public Long energyCapacity() {
        return energyCapacity;
    }

    public MachineReadings energyCapacity(@Nullable Long energyCapacity) {
        this.energyCapacity = energyCapacity;
        return this;
    }

    @Nullable
    public Long steamStored() {
        return steamStored;
    }

    public MachineReadings steamStored(@Nullable Long steamStored) {
        this.steamStored = steamStored;
        return this;
    }

    @Nullable
    public Long steamCapacity() {
        return steamCapacity;
    }

    public MachineReadings steamCapacity(@Nullable Long steamCapacity) {
        this.steamCapacity = steamCapacity;
        return this;
    }

    public boolean steamPowered() {
        return steamPowered;
    }

    public MachineReadings steamPowered(boolean steamPowered) {
        this.steamPowered = steamPowered;
        return this;
    }

    @Nullable
    public Boolean stuttering() {
        return stuttering;
    }

    /** What the machine holds on its input side; null when the walk did not run. */
    public BufferSet inputs() {
        return inputs;
    }

    public MachineReadings inputs(@Nullable BufferSet inputs) {
        this.inputs = inputs;
        return this;
    }

    public BufferSet outputs() {
        return outputs;
    }

    public MachineReadings outputs(@Nullable BufferSet outputs) {
        this.outputs = outputs;
        return this;
    }

    public MachineReadings stuttering(@Nullable Boolean stuttering) {
        this.stuttering = stuttering;
        return this;
    }

    @Nullable
    public Integer outputBlockedTicks() {
        return outputBlockedTicks;
    }

    public MachineReadings outputBlockedTicks(@Nullable Integer outputBlockedTicks) {
        this.outputBlockedTicks = outputBlockedTicks;
        return this;
    }

    public boolean steamVentBlocked() {
        return steamVentBlocked;
    }

    public MachineReadings steamVentBlocked(boolean steamVentBlocked) {
        this.steamVentBlocked = steamVentBlocked;
        return this;
    }

    public boolean machineErrors() {
        return machineErrors;
    }

    public MachineReadings machineErrors(boolean machineErrors) {
        this.machineErrors = machineErrors;
        return this;
    }

    public boolean startupPending() {
        return startupPending;
    }

    public MachineReadings startupPending(boolean startupPending) {
        this.startupPending = startupPending;
        return this;
    }

    @Nullable
    public Boolean formed() {
        return formed;
    }

    public MachineReadings formed(@Nullable Boolean formed) {
        this.formed = formed;
        return this;
    }

    @Nullable
    public Integer maintenanceIssues() {
        return maintenanceIssues;
    }

    public MachineReadings maintenanceIssues(@Nullable Integer maintenanceIssues) {
        this.maintenanceIssues = maintenanceIssues;
        return this;
    }

    @Nullable
    public Boolean maintenanceChecksEnabled() {
        return maintenanceChecksEnabled;
    }

    public MachineReadings maintenanceChecksEnabled(@Nullable Boolean maintenanceChecksEnabled) {
        this.maintenanceChecksEnabled = maintenanceChecksEnabled;
        return this;
    }

    @Nullable
    public Double efficiency() {
        return efficiency;
    }

    public MachineReadings efficiency(@Nullable Double efficiency) {
        this.efficiency = efficiency;
        return this;
    }

    @Nullable
    public Double pollutionEmissionFactor() {
        return pollutionEmissionFactor;
    }

    public MachineReadings pollutionEmissionFactor(@Nullable Double pollutionEmissionFactor) {
        this.pollutionEmissionFactor = pollutionEmissionFactor;
        return this;
    }

    @Nullable
    public Long recipesCompleted() {
        return recipesCompleted;
    }

    public MachineReadings recipesCompleted(@Nullable Long recipesCompleted) {
        this.recipesCompleted = recipesCompleted;
        return this;
    }

    @Nullable
    public Long controllerAgeTicks() {
        return controllerAgeTicks;
    }

    public MachineReadings controllerAgeTicks(@Nullable Long controllerAgeTicks) {
        this.controllerAgeTicks = controllerAgeTicks;
        return this;
    }

    @Nullable
    public Long lastWorkingTick() {
        return lastWorkingTick;
    }

    public MachineReadings lastWorkingTick(@Nullable Long lastWorkingTick) {
        this.lastWorkingTick = lastWorkingTick;
        return this;
    }

    @Nullable
    public String recipeCheckResultId() {
        return recipeCheckResultId;
    }

    public MachineReadings recipeCheckResultId(@Nullable String recipeCheckResultId) {
        this.recipeCheckResultId = recipeCheckResultId;
        return this;
    }

    @Nullable
    public Boolean recipeCheckSuccessful() {
        return recipeCheckSuccessful;
    }

    public MachineReadings recipeCheckSuccessful(@Nullable Boolean recipeCheckSuccessful) {
        this.recipeCheckSuccessful = recipeCheckSuccessful;
        return this;
    }

    public boolean recipeCheckPersistsOnShutdown() {
        return recipeCheckPersistsOnShutdown;
    }

    public MachineReadings recipeCheckPersistsOnShutdown(boolean recipeCheckPersistsOnShutdown) {
        this.recipeCheckPersistsOnShutdown = recipeCheckPersistsOnShutdown;
        return this;
    }

    @Nullable
    public String recipeCheckResultText() {
        return recipeCheckResultText;
    }

    public MachineReadings recipeCheckResultText(@Nullable String recipeCheckResultText) {
        this.recipeCheckResultText = recipeCheckResultText;
        return this;
    }
}
