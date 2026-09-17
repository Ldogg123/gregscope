package io.github.ldogg123.gregscope.sensor;

/**
 * The one-line cover status GT shows next to a cover (design-v0.2 §3.4 {@code getDescription}). [pure]
 *
 * <p>
 * Shape: {@code GregScope sensor <shortId>[: <label>][ (<availability>)]}. The availability is a transient word the
 * registry sets on the cover (for example {@code live} or {@code unloaded}); it is absent while nothing has set one.
 * An inert cover has no identity to name, so it shows its reason instead: {@code GregScope sensor (inactive)} for a
 * foreign blob and {@code GregScope sensor (unsupported data version)} for data written by a newer GregScope.
 */
public final class SensorDescription {

    /** Shown when a cover is inert and no reason was given. */
    public static final String INACTIVE = "inactive";

    private static final String PREFIX = "GregScope sensor";

    /**
     * The name alone, with no status: what a cover shows where it cannot know its own state. GT's only consumer of a
     * cover description is the client-side WAILA tooltip, and the sensor identity is never synced to a client, so a
     * client cover must not claim the {@link #INACTIVE} wording that a foreign or corrupt blob is named with.
     */
    public static final String NEUTRAL = PREFIX;

    private SensorDescription() {}

    /**
     * @param identity     the cover's identity, or {@code null} if it is inert
     * @param availability the registry's word for this sensor, or {@code null}/empty if unknown
     * @param inertReason  why the cover is inert; used only when {@code identity} is {@code null}
     */
    public static String text(SensorIdentity identity, String availability, String inertReason) {
        if (identity == null) {
            String reason = isBlank(inertReason) ? INACTIVE : inertReason;
            return PREFIX + " (" + reason + ")";
        }
        StringBuilder text = new StringBuilder(PREFIX).append(' ')
            .append(identity.shortId());
        if (!identity.label()
            .isEmpty()) {
            text.append(": ")
                .append(identity.label());
        }
        if (!isBlank(availability)) {
            text.append(" (")
                .append(availability)
                .append(')');
        }
        return text.toString();
    }

    private static boolean isBlank(String text) {
        return text == null || text.isEmpty();
    }
}
