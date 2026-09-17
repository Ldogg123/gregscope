package io.github.ldogg123.gregscope.config;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import org.apache.logging.log4j.Logger;

/**
 * Reads {@code config/gregscope.cfg} with Forge's {@link Configuration} (design-v0.2 §12.3). GTNHLib {@code @Config} is
 * not used: it fills fields by reflection and its GUI is client-only.
 */
public final class GregScopeConfig {

    private GregScopeConfig() {}

    /**
     * Loads the file (creating it with every §12.3 key and its comment when missing) and returns immutable settings.
     * Out-of-range values are clamped with one WARN (see {@link Settings#fromRaw}); the file keeps what the user wrote,
     * except that Forge itself resets a value that does not parse as the key's type.
     */
    public static Settings load(File file, Logger log) {
        Configuration config = new Configuration(file);
        Map<String, String> raw = new HashMap<>();
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            // Capture the text as written before the typed getter below replaces an unparseable value.
            if (config.hasCategory(key.category())) {
                ConfigCategory category = config.getCategory(key.category());
                if (category.containsKey(key.name())) {
                    raw.put(
                        key.path(),
                        category.get(key.name())
                            .getString());
                }
            }
            Property property;
            if (key.isBoolean()) {
                property = config.get(key.category(), key.name(), key.defaultBoolean(), key.comment());
            } else {
                property = config
                    .get(key.category(), key.name(), key.defaultInt(), key.comment(), key.min(), key.max());
            }
            property.comment = key.comment();
            property.setRequiresMcRestart(true);
        }
        Settings settings = Settings.fromRaw(raw, log::warn);
        if (config.hasChanged()) {
            config.save();
        }
        return settings;
    }
}
