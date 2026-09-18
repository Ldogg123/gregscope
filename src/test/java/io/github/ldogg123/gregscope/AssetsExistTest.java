package io.github.ldogg123.gregscope;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.hub.HubCodecs;
import io.github.ldogg123.gregscope.model.MachineState;

/**
 * GS-105 (design-v0.2 §12.2, §14): every icon constant in {@link GregScopeAssets} resolves to an existing 16x16 RGBA
 * PNG under {@code textures/blocks/} + name or {@code textures/items/} + name, and every lang key constant exists in
 * {@code en_US.lang}. Resources are read from the test classpath, i.e. the processed {@code main} resources that go
 * into the jar.
 */
class AssetsExistTest {

    private static final String ASSETS = "assets/gregscope/";
    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };

    @Test
    void designNamesArePinned() {
        assertEquals("gregscope", GregScopeAssets.DOMAIN);
        assertEquals(GregScope.MODID, GregScopeAssets.DOMAIN);
        // Errata E1: GT appends the key straight after textures/blocks/, so the name includes iconsets/.
        assertEquals("iconsets/GREGSCOPE_SENSOR_OVERLAY", GregScopeAssets.BLOCK_ICON_SENSOR_OVERLAY);
        assertEquals("machine_sensor", GregScopeAssets.ITEM_ICON_MACHINE_SENSOR);
        assertEquals("gregscope:machine_sensor", GregScopeAssets.iconName(GregScopeAssets.ITEM_ICON_MACHINE_SENSOR));
        assertEquals("machine_sensor", GregScopeAssets.REGISTRY_MACHINE_SENSOR);
        assertEquals("gregscope.machine_sensor", GregScopeAssets.UNLOCALIZED_MACHINE_SENSOR);
        assertEquals("item.gregscope.machine_sensor.name", GregScopeAssets.LANG_ITEM_MACHINE_SENSOR);
        // GS-112, design-v0.2 §17: the Telemetry Hub's registry names are frozen before the first public jar.
        assertEquals("telemetry_hub", GregScopeAssets.REGISTRY_TELEMETRY_HUB);
        assertEquals("gregscope:telemetry_hub", GregScopeAssets.TILE_ENTITY_TELEMETRY_HUB);
        assertEquals("gregscope.telemetry_hub", GregScopeAssets.UNLOCALIZED_TELEMETRY_HUB);
        assertEquals("tile.gregscope.telemetry_hub.name", GregScopeAssets.LANG_TILE_TELEMETRY_HUB);
        assertEquals("telemetry_hub_front", GregScopeAssets.BLOCK_ICON_HUB_FRONT);
        assertEquals("telemetry_hub_side", GregScopeAssets.BLOCK_ICON_HUB_SIDE);
        assertEquals("telemetry_hub_top", GregScopeAssets.BLOCK_ICON_HUB_TOP);
        assertArrayEquals(
            new String[] { "gregscope.tooltip.sensor.1", "gregscope.tooltip.sensor.2", "gregscope.tooltip.sensor.3" },
            GregScopeAssets.sensorTooltipKeys());
        assertEquals("gregscope.state.power_starved", GregScopeAssets.stateKey("power_starved"));
        assertEquals("gregscope.gap.chunk_unloaded", GregScopeAssets.gapKey("chunk_unloaded"));
    }

    @Test
    void everyIconConstantIsA16x16RgbaPng() throws IOException {
        Map<String, String> icons = constants("BLOCK_ICON_");
        Map<String, String> items = constants("ITEM_ICON_");
        assertFalse(icons.isEmpty(), "no BLOCK_ICON_ constants found");
        assertFalse(items.isEmpty(), "no ITEM_ICON_ constants found");
        List<String> checked = new ArrayList<>();
        for (Map.Entry<String, String> e : icons.entrySet()) {
            checked.add(assertPng(e.getKey(), "textures/blocks/" + e.getValue() + ".png"));
        }
        for (Map.Entry<String, String> e : items.entrySet()) {
            checked.add(assertPng(e.getKey(), "textures/items/" + e.getValue() + ".png"));
        }
        assertTrue(checked.contains(ASSETS + "textures/blocks/iconsets/GREGSCOPE_SENSOR_OVERLAY.png"), "" + checked);
        assertTrue(checked.contains(ASSETS + "textures/items/machine_sensor.png"), "" + checked);
        assertTrue(checked.contains(ASSETS + "textures/blocks/telemetry_hub_front.png"), "" + checked);
        assertTrue(checked.contains(ASSETS + "textures/blocks/telemetry_hub_side.png"), "" + checked);
        assertTrue(checked.contains(ASSETS + "textures/blocks/telemetry_hub_top.png"), "" + checked);
    }

    @Test
    void everyLangKeyConstantExists() throws IOException {
        Map<String, String> lang = lang();
        List<String> keys = new ArrayList<>(
            constants("LANG_").entrySet()
                .stream()
                .filter(
                    e -> !e.getKey()
                        .endsWith("_PREFIX"))
                .map(Map.Entry::getValue)
                .collect(java.util.stream.Collectors.toList()));
        assertTrue(keys.size() >= 5, "LANG_ constants: " + keys);
        for (String key : GregScopeAssets.sensorTooltipKeys()) {
            assertTrue(keys.contains(key), key + " is not a LANG_ constant");
        }
        for (MachineState state : MachineState.values()) {
            keys.add(GregScopeAssets.stateKey(state.id()));
        }
        for (GapReason reason : GapReason.values()) {
            keys.add(GregScopeAssets.gapKey(reason.id()));
        }
        // GS-114: the Hub GUI names a lifecycle state by its section 10.1 availability id, so every pinned code needs
        // a key. AVAILABILITY_NONE is a padding row, which the GUI draws as an empty line and never translates.
        for (int code = HubCodecs.AVAILABILITY_LIVE; code <= HubCodecs.AVAILABILITY_REMOVED; code++) {
            keys.add(GregScopeAssets.hubAvailabilityKey(HubCodecs.availabilityId(code)));
        }
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            String value = lang.get(key);
            if (value == null || value.trim()
                .isEmpty()) {
                missing.add(key);
            }
        }
        assertEquals(new ArrayList<String>(), missing, "lang keys missing or empty in en_US.lang");
        assertEquals("Machine Sensor", lang.get(GregScopeAssets.LANG_ITEM_MACHINE_SENSOR));
        assertEquals("Telemetry Hub", lang.get(GregScopeAssets.LANG_TILE_TELEMETRY_HUB));
        assertEquals(
            "Attach to a GT machine or multiblock controller (sneak + right-click)",
            lang.get(GregScopeAssets.LANG_TOOLTIP_SENSOR_1));
        assertEquals("Rename in an anvil to set a label", lang.get(GregScopeAssets.LANG_TOOLTIP_SENSOR_2));
        assertEquals(
            "Read-only: passes power, items, fluids and redstone",
            lang.get(GregScopeAssets.LANG_TOOLTIP_SENSOR_3));
    }

    /** The lang file parses the way 1.7.10's LanguageMap reads it, with no duplicate keys and ASCII only. */
    @Test
    void langFileIsWellFormed() throws IOException {
        byte[] bytes = resource(ASSETS + "lang/en_US.lang");
        for (byte b : bytes) {
            assertTrue(b == '\n' || (b >= 0x20 && b < 0x7f), "non-ASCII or control byte in en_US.lang: " + b);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> keys = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            assertTrue(eq > 0, "line without key=value: " + line);
            String key = line.substring(0, eq);
            assertEquals(key.trim(), key, "key with surrounding spaces: " + line);
            assertFalse(keys.contains(key), "duplicate key " + key);
            keys.add(key);
        }
    }

    /** Checks the negative case of the scanner: a name that does not exist is reported as missing. */
    @Test
    void missingResourceIsDetected() {
        assertEquals(
            null,
            getClass().getClassLoader()
                .getResourceAsStream(ASSETS + "textures/blocks/iconsets/GREGSCOPE_NO_SUCH_ICON.png"));
    }

    private static String assertPng(String constant, String relative) throws IOException {
        String path = ASSETS + relative;
        byte[] bytes = resource(path);
        assertTrue(bytes.length > 8 + 25, constant + ": " + path + " is too short");
        byte[] signature = new byte[8];
        System.arraycopy(bytes, 0, signature, 0, 8);
        assertArrayEquals(PNG_SIGNATURE, signature, constant + ": " + path + " is not a PNG");
        ByteBuffer ihdr = ByteBuffer.wrap(bytes, 8, 25);
        assertEquals(13, ihdr.getInt(), constant + ": IHDR length");
        assertEquals(0x49484452, ihdr.getInt(), constant + ": first chunk is not IHDR");
        assertEquals(16, ihdr.getInt(), constant + ": width");
        assertEquals(16, ihdr.getInt(), constant + ": height");
        assertEquals(8, ihdr.get(), constant + ": bit depth");
        assertEquals(6, ihdr.get(), constant + ": colour type (6 = RGBA)");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image, constant + ": " + path + " does not decode");
        assertEquals(16, image.getWidth(), constant + ": decoded width");
        assertEquals(16, image.getHeight(), constant + ": decoded height");
        assertTrue(
            image.getColorModel()
                .hasAlpha(),
            constant + ": no alpha channel");
        boolean opaque = false;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                opaque |= (image.getRGB(x, y) >>> 24) != 0;
            }
        }
        assertTrue(opaque, constant + ": fully transparent");
        return path;
    }

    /** Every {@code public static final String} constant of GregScopeAssets whose name starts with the prefix. */
    private static Map<String, String> constants(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field field : GregScopeAssets.class.getDeclaredFields()) {
            int m = field.getModifiers();
            if (Modifier.isPublic(m) && Modifier.isStatic(m)
                && Modifier.isFinal(m)
                && field.getType() == String.class
                && field.getName()
                    .startsWith(prefix)) {
                try {
                    out.put(field.getName(), (String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return out;
    }

    private static Map<String, String> lang() throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        String text = new String(resource(ASSETS + "lang/en_US.lang"), StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) {
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return map;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = AssetsExistTest.class.getClassLoader()
            .getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
