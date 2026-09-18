package io.github.ldogg123.gregscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The [pure] rule of design-v0.2 §2: a class whose Javadoc says {@code [pure]} imports and names nothing from
 * Minecraft, Forge/FML, GT, OpenComputers, GTNHLib or ModularUI2, and uses no reflection.
 *
 * <p>
 * GS-103 adds the {@code history}, {@code sensor} and {@code sampling} packages with pure classes only. Every source
 * file in those packages (and their subpackages) must be marked [pure] unless it is listed in {@link #IMPURE}; later
 * tickets that add the MC adapters named in §2 (for example {@code sensor/MachineSensorCover},
 * {@code history/HistoryIo}, {@code sampling/TelemetrySampler}) must list them there on purpose.
 *
 * <p>
 * GS-105 lists the sensor package's MC adapters ({@code ItemMachineSensor}, {@code MachineSensorCover},
 * {@code SensorCovers}) in {@link #IMPURE} and adds the pure {@code GregScopeAssets}, {@code SensorKind} and
 * {@code SensorCover}. GS-106 adds the pure {@code SensorDescription} and two more adapters, {@code NbtKeyValue} (the
 * {@code NBTTagCompound} side of the {@code KeyValue} seam) and {@code SensorEvents} (GT types in its signatures).
 *
 * <p>
 * GS-104 adds the {@code access} package: {@code AccessPolicy}, {@code TeamResolver} and {@code Viewer} are pure;
 * {@code GtnhlibTeamResolver} is the one listed adapter (design-v0.2 §2: the sole importer of GTNHLib teams).
 *
 * <p>
 * Three layers, because a text denylist alone misses things (review findings PURE-1/PURE-2): (1) a source denylist of
 * game packages in code, including GT5U's other root packages, LWJGL, Netty and Guava; (2) a source allowlist for
 * imports ({@code java.*} and [pure] GregScope classes) plus a word-boundary check that no [pure] file names an
 * {@link #IMPURE} adapter by its simple name, which a same-package use needs no import for; (3) a bytecode allowlist:
 * every class a compiled [pure] class (inner classes included) refers to in its constant pool, descriptors or
 * signatures is {@code java.*} (not {@code java.lang.reflect}) or a GregScope class whose source is [pure].
 */
class PureSourcesTest {

    private static final Path ROOT = Paths.get("src/main/java/io/github/ldogg123/gregscope");
    private static final String MOD_PACKAGE = "io.github.ldogg123.gregscope.";
    private static final String MOD_INTERNAL = "io/github/ldogg123/gregscope/";

    /** GS-103 and GS-104 scope (§14) plus pure support classes; each must exist and be marked [pure]. */
    private static final List<String> PURE_CLASSES = Arrays.asList(
        // GS-202 spike: the fluid counting core is pure so its rules can be tested without Minecraft.
        "flow/FluidMover.java",
        "flow/FluidParcel.java",
        "model/StateCodes.java",
        // v0.1 enum that StateCodes maps; found by the bytecode check (a same-package reference has no import).
        "model/MachineState.java",
        "history/GapReason.java",
        "sensor/Labels.java",
        "sensor/SensorIdentity.java",
        "sensor/SensorNbtCodec.java",
        "sensor/KeyValue.java",
        // GS-106: the cover description text, kept out of the cover so it can be unit tested.
        "sensor/SensorDescription.java",
        "history/SecondRing.java",
        "history/MinuteSlot.java",
        "history/MinuteAccumulator.java",
        "history/MinuteRing.java",
        "history/MinuteSource.java",
        "history/Crc16Ccitt.java",
        "history/GapRanges.java",
        "history/Summaries.java",
        "history/SizeCeilings.java",
        "history/LongMath.java",
        "sampling/SensorCounters.java",
        "sampling/LogHistogram.java",
        "sampling/Clock.java",
        "sampling/FakeClock.java",
        // GS-104 (§5, §14)
        "access/AccessPolicy.java",
        "access/TeamResolver.java",
        "access/Viewer.java",
        // GS-105 (§3.2, §12.2) and the design-v0.3 GS-201 A2 hook: asset names, sensor kinds, the SensorCover seam.
        "GregScopeAssets.java",
        "sensor/SensorKind.java",
        "sensor/SensorCover.java",
        // GS-107 (section 4) plus the design-v0.3 GS-201 A1 reverse-index key.
        "registry/SensorState.java",
        "registry/RemovalCause.java",
        "registry/PosKey.java",
        "registry/SensorEntry.java",
        "registry/RegistryEvents.java",
        "registry/SensorRegistryCore.java",
        // The v0.1 snapshot model a registry entry holds; pure in fact since v0.1, marked and enforced with GS-107.
        "model/MachineSnapshot.java",
        "model/MachineKind.java",
        "model/SnapshotKeys.java",
        "model/StatusIds.java",
        // GS-108 (sections 6.1, 6.3, 7.6, 7.7) plus the design-v0.3 GS-201 A6 CountersView interface.
        "sampling/SamplerSchedule.java",
        "sampling/SampleFolder.java",
        "sampling/SamplerStats.java",
        "sampling/SamplerStatsView.java",
        "sampling/LimitsView.java",
        "sampling/CountersView.java",
        "sampling/MachineCountersView.java",
        "sampling/SensorView.java",
        "sampling/TelemetryFrame.java",
        // GS-109 (sections 8.1 to 8.4) plus the design-v0.3 GS-201 A5 slot-layout hook.
        "history/Crc32.java",
        "history/HistoryFileCodec.java",
        "history/SlotLayouts.java",
        "history/FileStore.java",
        "history/IoListener.java",
        "registry/RunsTable.java",
        // GS-111 (section 11, section 3.5): the argument parser and the shared per-player rename cooldown.
        "command/CommandArgs.java",
        "sensor/RenameCooldown.java",
        // GS-112 (section 9.1): the Telemetry Hub's NBT record and the server-wide open-view cap.
        "hub/HubNbtCodec.java",
        "hub/HubViews.java",
        // GS-113 (sections 9.2, 9.3): the Hub's data side - the DTOs, their codec seam and the view model.
        "hub/ByteSink.java",
        "hub/ByteSource.java",
        "hub/HubCodecs.java",
        "hub/HubRow.java",
        "hub/HubHeader.java",
        "hub/HubWindow.java",
        "hub/HubDetail.java",
        "hub/HubViewModel.java",
        // GS-115 (sections 10.1, 10.4): the OpenComputers table builders, which take no game type at all.
        "integration/opencomputers/LuaTables.java",
        // GS-116 (sections 10.3, 5): one Telemetry Hub's scope over a frame, with the paging and id resolution the
        // gregscope_hub callbacks need. It takes the team answers as a TeamResolver, so it needs no game type either.
        "integration/opencomputers/HubScope.java",
        // The settings a LimitsView copies; pure in fact since GS-101, and reached by the bytecode check.
        "config/Settings.java",
        "config/ConfigKeys.java");

    private static final List<String> PURE_PACKAGES = Arrays
        .asList("history", "sensor", "sampling", "access", "registry", "model", "command", "hub", "integration");

    /** Files in {@link #PURE_PACKAGES} allowed to touch game classes, each on purpose. */
    private static final Set<String> IMPURE = new HashSet<>(
        Arrays.asList(
            "access/GtnhlibTeamResolver.java",
            // GS-105: the §2 MC adapters of the sensor package (item, cover, registration and placement rule).
            "sensor/ItemMachineSensor.java",
            "sensor/MachineSensorCover.java",
            "sensor/SensorCovers.java",
            // GS-106: the NBT seam adapter and the event interface the cover reports through (both take GT/MC types).
            "sensor/NbtKeyValue.java",
            "sensor/SensorEvents.java",
            // GS-107: the MC adapter of the registry (world lookups, cover NBT re-key, chat, server tick).
            "registry/SensorRegistry.java",
            // GS-108: the world half of section 6.2 and the one ServerTickEvent handler.
            "sampling/TargetResolver.java",
            "sampling/TelemetrySampler.java",
            // GS-109: the I/O adapters design-v0.2 §2 lists beside the pure history model. They import no game class
            // (only java.nio and java.util.concurrent), but they are the file and thread boundary, so they are listed
            // here on purpose: no [pure] class may name them, which is what keeps the boundary one-way.
            "history/NioFileStore.java",
            "history/HistoryIo.java",
            // GS-109: registry.dat is NBT, so only an MC adapter can read or write it.
            "registry/RegistryNbtCodec.java",
            // GS-110: the two persistence services. Neither imports a game class, but each names an adapter above
            // (HistoryIo, RegistryNbtCodec/FileStore), so listing them is what keeps the boundary one-way: no [pure]
            // class may name them either.
            "history/HistoryPersistence.java",
            "registry/RegistryPersistence.java",
            // GS-111: the one CommandBase of section 11. It is the Minecraft adapter around CommandArgs (chat
            // components, an ICommandSender turned into a Viewer, the live registry), so the command package is a
            // pure package with exactly one listed adapter.
            "command/GregScopeCommand.java",
            // GS-112: the Minecraft adapters of the Telemetry Hub (design-v0.2 section 9.1). The block and the tile
            // entity are Minecraft types; the registration class is the one that names GameRegistry. The pure half of
            // the package (the record codec and the view cap) may name none of them.
            "hub/BlockTelemetryHub.java",
            "hub/TileTelemetryHub.java",
            "hub/TelemetryHubs.java",
            // GS-114: the ModularUI2 half of the Hub (design-v0.2 sections 9.2 and 9.3). HubPacketIo is the
            // PacketBuffer side of the ByteSink/ByteSource seam, exactly as NbtKeyValue is the NBTTagCompound side of
            // KeyValue; HubPanel is the widget tree and the named sync handlers; HubSession is the per-viewer object
            // that reaches the live registry. The pure half of the package - the DTOs, their codecs and the view
            // model - may name none of them, which is what keeps the seam one-way and keeps HubViewModel unit tested.
            "hub/HubPacketIo.java",
            "hub/HubPanel.java",
            "hub/HubSession.java",
            // GS-114 follow-up: the FML logout hook. It names the FML bus and the logout event, so it is an adapter
            // too; all it does with them is call HubViews.closed.
            "hub/HubViewLifecycle.java",
            // GS-115 adds the integration package to the pure scan for the sake of LuaTables. The v0.1 classes beside
            // it are the OpenComputers adapters by definition: the driver and the environment name li.cil.oc types
            // and the world, and the registration class names the OC driver registry. LuaTables may name none of
            // them, which is what keeps the table builders unit tested on a plain JVM.
            "integration/opencomputers/GregTechMachineDriver.java",
            "integration/opencomputers/GregTechMachineEnvironment.java",
            "integration/opencomputers/OpenComputersIntegration.java",
            // GS-116: the same pair for the gregscope_hub component. The driver and the environment name li.cil.oc
            // types, the world and the Hub's tile entity; HubScope, which they build and read, may name none of them.
            "integration/opencomputers/HubDriver.java",
            "integration/opencomputers/HubEnvironment.java"));

    static final List<String> FORBIDDEN = Arrays.asList(
        "net.minecraft.",
        "net.minecraftforge.",
        "cpw.mods.",
        "gregtech.",
        "gtPlusPlus.",
        "tectech.",
        // The other root packages inside the pinned GT5U 5.09.54.133 jar.
        "bartworks.",
        "bwcrossmod.",
        "detrav.",
        "galacticgreg.",
        "ggfab.",
        "goodgenerator.",
        "gtneioreplugin.",
        "gtnhintergalactic.",
        "gtnhlanth.",
        "kekztech.",
        "kubatech.",
        "toxiceverglades.",
        "li.cil.",
        "com.gtnewhorizon.",
        "com.gtnewhorizons.",
        "com.cleanroommc.",
        "java.lang.reflect.",
        "org.spongepowered.",
        "org.lwjgl.",
        "io.netty.",
        "com.google.");

    @Test
    void pureModelClassesExistAndAreMarkedPure() throws IOException {
        for (String name : PURE_CLASSES) {
            Path file = ROOT.resolve(name);
            assertTrue(Files.isRegularFile(file), "missing " + file.toAbsolutePath());
            assertTrue(read(file).contains("[pure]"), name + " is not marked [pure]");
        }
    }

    @Test
    void purePackagesHoldOnlyPureOrListedClasses() throws IOException {
        List<String> unmarked = new ArrayList<>();
        int seen = 0;
        for (String pkg : PURE_PACKAGES) {
            // Files.walk: subpackages of a pure package are checked too.
            try (Stream<Path> files = Files.walk(ROOT.resolve(pkg))) {
                for (Path file : files.filter(
                    p -> p.toString()
                        .endsWith(".java"))
                    .collect(Collectors.toList())) {
                    seen++;
                    String name = relative(file);
                    if (!IMPURE.contains(name) && !read(file).contains("[pure]")) {
                        unmarked.add(name);
                    }
                }
            }
        }
        assertTrue(seen >= PURE_CLASSES.size() - 1, "scan found only " + seen + " files");
        assertEquals(new ArrayList<String>(), unmarked, "unmarked classes in pure packages");
    }

    @Test
    void pureClassesReferenceNoGameClasses() throws IOException {
        List<String> violations = new ArrayList<>();
        Set<String> pure = pureSources();
        for (String name : pure) {
            violations.addAll(violations(name, read(ROOT.resolve(name))));
        }
        assertTrue(pure.size() >= PURE_CLASSES.size(), "only " + pure.size() + " [pure] files checked");
        assertEquals(new ArrayList<String>(), violations, "[pure] classes referencing forbidden packages");
    }

    /** Imports of [pure] files are java.* or [pure] GregScope classes; no [pure] file names a listed adapter. */
    @Test
    void pureClassesImportOnlyJavaAndPureClassesAndNameNoAdapter() throws IOException {
        Set<String> pure = pureSources();
        List<String> violations = new ArrayList<>();
        for (String name : pure) {
            violations.addAll(importViolations(name, read(ROOT.resolve(name)), pure, IMPURE));
        }
        assertEquals(new ArrayList<String>(), violations, "[pure] classes importing or naming impure classes");
    }

    /**
     * The compiled [pure] classes, inner classes included, reference only {@code java/*} and GregScope classes whose
     * source is [pure]. This sees what a source grep cannot: same-package references, qualified names split across
     * lines, and types that only appear in descriptors.
     */
    @Test
    void compiledPureClassesReferenceOnlyJavaAndPureClasses() throws IOException, URISyntaxException {
        Set<String> pure = pureSources();
        Path classes = mainClassesRoot();
        List<String> violations = new ArrayList<>();
        int classFiles = 0;
        for (String source : pure) {
            List<Path> compiled = compiledClasses(classes, source);
            assertTrue(!compiled.isEmpty(), "no compiled class for " + source + " under " + classes);
            for (Path file : compiled) {
                classFiles++;
                for (String ref : classReferences(Files.readAllBytes(file))) {
                    if (!allowedBytecodeReference(ref, pure)) {
                        violations.add(
                            classes.relativize(file)
                                .toString()
                                .replace('\\', '/') + " references "
                                + ref);
                    }
                }
            }
        }
        assertTrue(classFiles >= pure.size(), "only " + classFiles + " compiled [pure] classes checked");
        assertEquals(new ArrayList<String>(), violations, "compiled [pure] classes referencing impure classes");
    }

    /** Negative control for the bytecode check: the listed GTNHLib adapter must be reported, a pure class must not. */
    @Test
    void bytecodeScannerCatchesTheListedAdapter() throws IOException, URISyntaxException {
        Set<String> pure = pureSources();
        Path classes = mainClassesRoot();
        List<String> adapterRefs = new ArrayList<>();
        for (Path file : compiledClasses(classes, "access/GtnhlibTeamResolver.java")) {
            for (String ref : classReferences(Files.readAllBytes(file))) {
                if (!allowedBytecodeReference(ref, pure)) {
                    adapterRefs.add(ref);
                }
            }
        }
        assertTrue(
            adapterRefs.contains("com/gtnewhorizon/gtnhlib/teams/TeamManager"),
            "adapter references not seen: " + adapterRefs);
        assertTrue(adapterRefs.contains(MOD_INTERNAL + "access/GtnhlibTeamResolver"), adapterRefs.toString());
        Set<String> policyRefs = classReferences(
            Files.readAllBytes(classes.resolve(MOD_INTERNAL + "access/AccessPolicy.class")));
        assertTrue(policyRefs.contains(MOD_INTERNAL + "config/Settings"), "scanner misses a reference: " + policyRefs);
        assertTrue(policyRefs.contains("java/util/UUID"), "scanner misses a descriptor type: " + policyRefs);
    }

    /** Negative control: the denylist scanner must catch imports, static imports and qualified names in code. */
    @Test
    void scannerCatchesViolations() {
        String bad = "package x;\n" + "import net.minecraft.world.World;\n"
            + "import static gregtech.api.enums.GTValues.V;\n"
            + "/** mentions net.minecraft.world.World in a comment only */\n"
            + "class Y {\n"
            + "    // li.cil.oc.api.Network in a line comment\n"
            + "    Object o = com.gtnewhorizon.gtnhlib.teams.TeamManager.class;\n"
            + "    java.lang.reflect.Field f;\n"
            + "    Object b = bartworks.util.BWUtil.class;\n"
            + "    Object k = org.lwjgl.input.Keyboard.class;\n"
            + "}\n";
        List<String> found = violations("Y.java", bad);
        assertEquals(6, found.size(), found.toString());
        assertTrue(violations("Z.java", "import java.util.UUID;\nclass Z { String s = \"ok\"; }\n").isEmpty());
    }

    /** Negative control for the import allowlist and the adapter simple-name check. */
    @Test
    void importScannerCatchesViolations() {
        Set<String> pure = new HashSet<>(Arrays.asList("history/A.java", "history/B.java"));
        Set<String> impure = Collections.singleton("history/HistoryIo.java");
        String bad = "package io.github.ldogg123.gregscope.history;\n" + "import java.util.List;\n"
            + "import io.github.ldogg123.gregscope.history.B;\n"
            + "import io.github.ldogg123.gregscope.history.HistoryIo;\n"
            + "import com.google.common.base.Preconditions;\n"
            + "import kubatech.api.utils.ModUtils;\n"
            + "import static io.github.ldogg123.gregscope.GregScope.phase;\n"
            + "/** {@link HistoryIo} in a comment is fine */\n"
            + "class A {\n"
            + "    void f() { HistoryIo.saveRoot(); }\n"
            + "    int historyIoCount; // HistoryIo in a trailing comment is fine\n"
            + "    int HistoryIoCount;\n"
            + "}\n";
        List<String> found = importViolations("history/A.java", bad, pure, impure);
        // HistoryIo import, Guava, kubatech, impure static import, HistoryIo use in code.
        assertEquals(5, found.size(), found.toString());
        assertEquals(2, violations("A.java", bad).size(), "denylist sees com.google and kubatech");
        assertTrue(
            importViolations(
                "history/B.java",
                "import java.util.UUID;\nimport static io.github.ldogg123.gregscope.history.A.CONST;\nclass B {}\n",
                pure,
                impure).isEmpty());
    }

    static List<String> violations(String name, String source) {
        List<String> out = new ArrayList<>();
        Set<Integer> reported = new HashSet<>();
        for (CodeLine line : codeLines(source)) {
            for (String prefix : FORBIDDEN) {
                if (line.code.contains(prefix) && reported.add(line.number)) {
                    out.add(name + ":" + line.number + ": " + line.text);
                }
            }
        }
        return out;
    }

    static List<String> importViolations(String name, String source, Set<String> pure, Set<String> impure) {
        List<String> out = new ArrayList<>();
        List<Pattern> adapters = new ArrayList<>();
        for (String adapter : impure) {
            String file = Paths.get(adapter)
                .getFileName()
                .toString();
            adapters.add(Pattern.compile("\\b" + Pattern.quote(file.substring(0, file.length() - 5)) + "\\b"));
        }
        for (CodeLine line : codeLines(source)) {
            if (line.code.startsWith("import ")) {
                String target = line.code.substring("import ".length())
                    .replace("static ", "")
                    .replace(";", "")
                    .trim();
                if (!importAllowed(target, pure)) {
                    out.add(name + ":" + line.number + ": " + line.text);
                }
                continue;
            }
            for (Pattern adapter : adapters) {
                if (adapter.matcher(line.code)
                    .find()) {
                    out.add(name + ":" + line.number + ": names adapter " + adapter + ": " + line.text);
                }
            }
        }
        return out;
    }

    private static final class CodeLine {

        final int number;
        final String text;
        final String code;

        CodeLine(int number, String text, String code) {
            this.number = number;
            this.text = text;
            this.code = code;
        }
    }

    /** Non-comment code of each line (block comments, Javadoc and trailing line comments removed). */
    private static List<CodeLine> codeLines(String source) {
        List<CodeLine> out = new ArrayList<>();
        boolean inBlockComment = false;
        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (inBlockComment) {
                if (line.contains("*/")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (line.startsWith("/*")) {
                inBlockComment = !line.contains("*/");
                continue;
            }
            if (line.startsWith("//") || line.startsWith("*")) {
                continue;
            }
            int comment = line.indexOf("//");
            out.add(new CodeLine(i + 1, line, comment >= 0 ? line.substring(0, comment) : line));
        }
        return out;
    }

    private static boolean importAllowed(String target, Set<String> pure) {
        if (target.startsWith("java.") && !target.startsWith("java.lang.reflect.")) {
            return true;
        }
        if (!target.startsWith(MOD_PACKAGE)) {
            return false;
        }
        // a.b.C, a.b.C.Inner and a.b.C.member (static import) are allowed when a/b/C.java is [pure].
        StringBuilder path = new StringBuilder();
        for (String part : target.substring(MOD_PACKAGE.length())
            .split("\\.")) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(part);
            if (pure.contains(path + ".java")) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowedBytecodeReference(String internalName, Set<String> pure) {
        if (internalName.startsWith("java/")) {
            return !internalName.startsWith("java/lang/reflect/");
        }
        if (!internalName.startsWith(MOD_INTERNAL)) {
            return false;
        }
        String outer = internalName.substring(MOD_INTERNAL.length());
        int dollar = outer.indexOf('$');
        return pure.contains((dollar >= 0 ? outer.substring(0, dollar) : outer) + ".java");
    }

    /** An object type inside a descriptor or signature, e.g. {@code Ljava/util/List;} or {@code Ljava/util/List<}. */
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([A-Za-z_$][\\w$]*(?:/[\\w$]+)+)[;<]");

    /** Class names in a class file (JVMS 4.4): CONSTANT_Class entries and object types in UTF-8 entries. */
    static Set<String> classReferences(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        List<Integer> classNameIndexes = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: // Utf8
                    utf8[i] = in.readUTF();
                    break;
                case 7: // Class
                    classNameIndexes.add(in.readUnsignedShort());
                    break;
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    in.readUnsignedShort();
                    break;
                case 15: // MethodHandle
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    in.readInt();
                    break;
                case 5: // Long
                case 6: // Double
                    in.readLong();
                    i++;
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag);
            }
        }
        Set<String> refs = new TreeSet<>();
        for (int index : classNameIndexes) {
            String name = utf8[index];
            if (name.startsWith("[")) {
                addDescriptorClasses(name, refs);
            } else {
                refs.add(name);
            }
        }
        for (String s : utf8) {
            if (s != null && s.indexOf('/') >= 0) {
                addDescriptorClasses(s, refs);
            }
        }
        return refs;
    }

    private static void addDescriptorClasses(String descriptor, Set<String> refs) {
        Matcher m = DESCRIPTOR_CLASS.matcher(descriptor);
        while (m.find()) {
            refs.add(m.group(1));
        }
    }

    private static List<Path> compiledClasses(Path classes, String source) throws IOException {
        String base = source.substring(0, source.length() - ".java".length());
        Path outer = classes.resolve(MOD_INTERNAL + base + ".class");
        String simple = outer.getFileName()
            .toString();
        String prefix = simple.substring(0, simple.length() - ".class".length()) + "$";
        try (Stream<Path> files = Files.list(outer.getParent())) {
            return files.filter(p -> {
                String f = p.getFileName()
                    .toString();
                return f.equals(simple) || (f.startsWith(prefix) && f.endsWith(".class"));
            })
                .sorted()
                .collect(Collectors.toList());
        }
    }

    private static Set<String> pureSources() throws IOException {
        Set<String> pure = new TreeSet<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(
                p -> p.toString()
                    .endsWith(".java"))
                .collect(Collectors.toList())) {
                if (read(file).contains("[pure]")) {
                    pure.add(relative(file));
                }
            }
        }
        return pure;
    }

    /** The compiled {@code main} output root on the test classpath (a directory in Gradle's test task). */
    private static Path mainClassesRoot() throws URISyntaxException {
        URL marker = PureSourcesTest.class.getClassLoader()
            .getResource(MOD_INTERNAL + "GregScope.class");
        assertTrue(marker != null, "shipped GregScope.class is not on the test classpath");
        assertEquals("file", marker.getProtocol(), "main classes must be a directory on the test classpath");
        Path root = Paths.get(marker.toURI())
            .getParent();
        for (int i = 0; i < MOD_INTERNAL.split("/").length; i++) {
            root = root.getParent();
        }
        return root;
    }

    private static String relative(Path file) {
        return ROOT.relativize(file)
            .toString()
            .replace('\\', '/');
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
