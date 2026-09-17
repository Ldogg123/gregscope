package io.github.ldogg123.gregscope;

/**
 * Client-side proxy. Client-only code (screens, renderers) goes here or in {@code @SideOnly(CLIENT)} methods, never in
 * common classes.
 *
 * <p>
 * Loading this class sets the system property {@value #LOADED_PROPERTY}. A dedicated server must never load it; the
 * Horizon-QA test {@code SafetyTests.clientProxyNotLoaded} asserts the property is absent there.
 */
public class ClientProxy extends CommonProxy {

    public static final String LOADED_PROPERTY = "gregscope.clientProxyLoaded";

    static {
        System.setProperty(LOADED_PROPERTY, "true");
    }
}
