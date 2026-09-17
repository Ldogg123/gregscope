package io.github.ldogg123.gregscope.gametest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.versioning.ArtifactVersion;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.relauncher.Side;
import io.github.ldogg123.gregscope.GregScope;
import io.github.ldogg123.gregscope.GregScopeTestHooks;
import io.github.ldogg123.gregscope.LifecyclePhase;
import io.github.ldogg123.gregscope.Tags;
import io.github.ldogg123.gregscope.config.ConfigKeys;
import io.github.ldogg123.gregscope.config.Settings;

/** GS-101: the v0.2 mod skeleton boots on a dedicated server with its config, dependencies and test hooks. */
@GameTestHolder(value = "gregscope", requiredMods = { "gregtech", "OpenComputers", "gregscope" })
public class LifecycleTests {

    private static final String BATCH = "gregscope.lifecycle";
    /** Separate batch: it changes the process-wide settings, so nothing may run in parallel with it. */
    private static final String HOOKS_BATCH = "gregscope.lifecycle.hooks";

    private LifecycleTests() {}

    @GameTest(batch = BATCH)
    public static void serverStarts(GameTestHelper helper) {
        helper.assertTrue(
            FMLCommonHandler.instance()
                .getSide() == Side.SERVER,
            "not a server side");
        helper.assertTrue(
            MinecraftServer.getServer()
                .isDedicatedServer(),
            "not a dedicated server");
        helper.assertTrue(Loader.isModLoaded(GregScope.MODID), "GregScope is not loaded");
        ModContainer container = Loader.instance()
            .getIndexedModList()
            .get(GregScope.MODID);
        helper.assertNotNull(container, "no GregScope mod container");
        helper.assertEquals(Tags.VERSION, container.getVersion(), "GregScope version");

        // Every handler up to serverStarted ran, and none after it.
        helper.assertEquals(LifecyclePhase.SERVER_STARTED, GregScope.phase(), "lifecycle phase");

        // Dependencies string: all four mods are hard requirements and loaded; gtnhlib's version is in range.
        Map<String, ArtifactVersion> requirements = new HashMap<>();
        for (ArtifactVersion requirement : container.getRequirements()) {
            requirements.put(requirement.getLabel(), requirement);
        }
        List<String> required = Arrays.asList("gregtech", "OpenComputers", "modularui2", "gtnhlib");
        for (String modId : required) {
            helper.assertTrue(requirements.containsKey(modId), "not required: " + modId + " in " + requirements);
            helper.assertTrue(Loader.isModLoaded(modId), "required mod not loaded: " + modId);
            boolean after = false;
            for (ArtifactVersion dependency : container.getDependencies()) {
                after |= modId.equals(dependency.getLabel());
            }
            helper.assertTrue(after, "not loaded after: " + modId);
        }
        String gtnhlibVersion = Loader.instance()
            .getIndexedModList()
            .get("gtnhlib")
            .getVersion();
        helper.assertEquals("0.11.46", gtnhlibVersion, "dev runtime GTNHLib version (beta-3 manifest)");
        helper.assertTrue(
            requirements.get("gtnhlib")
                .containsVersion(new DefaultArtifactVersion("gtnhlib", gtnhlibVersion)),
            "gtnhlib requirement " + requirements.get("gtnhlib") + " rejects " + gtnhlibVersion);
        helper.assertFalse(
            requirements.get("gtnhlib")
                .containsVersion(new DefaultArtifactVersion("gtnhlib", "0.11.45")),
            "gtnhlib requirement accepts 0.11.45");
        helper.assertEquals(
            "2.3.88-1.7.10",
            Loader.instance()
                .getIndexedModList()
                .get("modularui2")
                .getVersion(),
            "dev runtime ModularUI2 version (beta-3 manifest)");

        // The config file exists with every §12.3 key, and the loaded settings are what the file says.
        File file = new File(
            Loader.instance()
                .getConfigDir(),
            "gregscope.cfg");
        helper.assertTrue(file.isFile(), "config file missing: " + file.getAbsolutePath());
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            helper.fail("cannot read " + file + ": " + e);
            return;
        }
        Configuration config = new Configuration(file);
        Map<String, String> raw = new HashMap<>();
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            helper.assertTrue(config.hasCategory(key.category()), "missing category " + key.category());
            ConfigCategory category = config.getCategory(key.category());
            helper.assertTrue(category.containsKey(key.name()), "missing key " + key.path() + " in " + file);
            raw.put(
                key.path(),
                category.get(key.name())
                    .getString());
            // Forge does not keep comments when it reads a file, so check the written text.
            helper.assertTrue(text.contains("# " + key.comment()), "comment of " + key.path() + " not written");
        }
        helper.assertFalse(config.hasCategory("exporter"), "exporter category is reserved for v0.4");
        Settings fromFile = Settings.fromRaw(raw, message -> {});
        helper.assertEquals(fromFile, GregScope.configuredSettings(), "settings loaded in preInit");
        helper.assertTrue(GregScope.settings() != null, "active settings are null");

        // The creative tab is registered.
        helper.assertNotNull(GregScope.creativeTab(), "creative tab");
        helper.assertTrue(
            Arrays.asList(CreativeTabs.creativeTabArray)
                .contains(GregScope.creativeTab()),
            "creative tab not in CreativeTabs.creativeTabArray");

        Snapshots.log(
            "lifecycle#serverStarts",
            "phase=" + GregScope.phase()
                + " version="
                + container.getVersion()
                + " requirements="
                + requirements.values()
                + " settings="
                + GregScope.configuredSettings());
        helper.succeed();
    }

    /** {@code -Dgregscope.testHooks=true} is set by addon.gradle on runServer, also for CI's plain invocation. */
    @GameTest(batch = HOOKS_BATCH)
    public static void testHooksReachGametestJvm(GameTestHelper helper) {
        helper.assertEquals("true", System.getProperty("gregscope.testHooks"), "gregscope.testHooks property");
        helper.assertTrue(GregScopeTestHooks.enabled(), "GregScopeTestHooks.enabled()");

        Settings configured = GregScope.configuredSettings();
        Settings override = configured.toBuilder()
            .maxSensors(16)
            .intervalTicks(100)
            .build();
        helper.assertTrue(!override.equals(configured), "override equals the configured settings");
        try {
            helper.assertTrue(GregScopeTestHooks.overrideSettings(override), "overrideSettings not applied");
            helper.assertTrue(GregScope.settings() == override, "active settings are not the override");
            helper.assertTrue(GregScope.configuredSettings() == configured, "override replaced configured settings");
        } finally {
            GregScopeTestHooks.clearSettingsOverride();
        }
        helper.assertTrue(GregScope.settings() == configured, "override not cleared");
        helper.succeed();
    }
}
