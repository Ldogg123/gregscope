package io.github.ldogg123.gregscope;

import io.github.ldogg123.gregscope.config.Settings;

/**
 * Hooks for GregScope's Horizon-QA tests, which cannot advance server time or restart the server (design-v0.2 §13.1).
 * Every hook is a no-op returning {@code false} unless the JVM runs with {@code -Dgregscope.testHooks=true}. The dev
 * {@code runServer}/{@code runClient} tasks set it (addon.gradle); a normal server does not. Later tickets add the
 * clock, sampling, heartbeat and I/O hooks here.
 */
public final class GregScopeTestHooks {

    public static final String PROPERTY = "gregscope.testHooks";

    private static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

    private GregScopeTestHooks() {}

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Replaces the active settings until {@link #clearSettingsOverride()} or server stop.
     *
     * @return true if applied, false if hooks are disabled
     */
    public static boolean overrideSettings(Settings settings) {
        if (!ENABLED) {
            return false;
        }
        if (settings == null) {
            throw new IllegalArgumentException("settings");
        }
        GregScope.setSettingsOverride(settings);
        return true;
    }

    /** @return true if applied, false if hooks are disabled */
    public static boolean clearSettingsOverride() {
        if (!ENABLED) {
            return false;
        }
        GregScope.setSettingsOverride(null);
        return true;
    }
}
