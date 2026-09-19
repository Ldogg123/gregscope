package io.github.ldogg123.gregscope.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.access.TeamResolver;
import io.github.ldogg123.gregscope.config.Settings;
import io.github.ldogg123.gregscope.history.GapReason;
import io.github.ldogg123.gregscope.hub.HubCodecs;
import io.github.ldogg123.gregscope.model.MachineKind;
import io.github.ldogg123.gregscope.model.MachineSnapshot;
import io.github.ldogg123.gregscope.model.MachineState;
import io.github.ldogg123.gregscope.model.SnapshotKeys;
import io.github.ldogg123.gregscope.model.StateCodes;
import io.github.ldogg123.gregscope.registry.SensorEntry;
import io.github.ldogg123.gregscope.registry.SensorRegistryCore;
import io.github.ldogg123.gregscope.registry.SensorState;
import io.github.ldogg123.gregscope.sensor.SensorIdentity;
import io.github.ldogg123.gregscope.sensor.SensorKind;

/**
 * GS-118 (design-v0.2 sections 7.7 and 16.2): the contract that lets the v0.4 Prometheus exporter render a
 * {@code TelemetryFrame} from an HTTP thread, and the contract between that exporter's metric model
 * ({@code docs/metrics-model.md}) and the shipped enums.
 *
 * <p>
 * <b>Two halves.</b> The first half checks the data model by reflection: every field of every class reachable from
 * {@link TelemetryFrame} is final, no class in that graph holds mutable static state, every field type is a primitive,
 * an immutable value or a collection that is handed out unmodifiable, every collection a populated frame exposes
 * really throws on a write, every array getter hands out a fresh copy, and the shipped publication field is
 * {@code static volatile}. The second half parses the machine-readable tables of {@code docs/metrics-model.md} and
 * checks every reserved metric and label name against the Prometheus regexes, regenerates every closed label set from
 * the enum it claims to come from, and re-adds the cardinality the document states.
 *
 * <p>
 * <b>Reflection is used here and only here.</b> Design-v0.2 section 1.4 forbids reflection in shipped code; GS-119
 * makes the same exception for the GT API shape tests. The one class file read as bytes ({@code TelemetrySampler}) is
 * never loaded, the way {@code ShippedClassesTest} reads every shipped class, so no Minecraft class is touched.
 *
 * <p>
 * <b>Why the document is parsed rather than duplicated.</b> The acceptance criterion asks that closed label sets be
 * generated from the enums rather than hand-written. A test that hard-coded the ten state ids would only prove the
 * test agrees with itself. Instead the expected sets are generated here from {@code StateCodes}, {@code GapReason},
 * {@code SensorKind}, {@code SensorState} and {@code HubCodecs}, and the document's tables are checked against them,
 * so a new enum constant that nobody wrote into the mapping fails this test.
 */
class TelemetryFrameContractTest {

    private static final Path DOC = Paths.get("docs/metrics-model.md");

    /** Prometheus metric name (anchored). */
    private static final Pattern METRIC_NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    /** Prometheus label name (anchored). */
    private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    /** The shape a generated closed-set value must have, so a value can never need escaping. */
    private static final Pattern VALUE_ID = Pattern.compile("[a-z][a-z0-9_]*");

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");
    private static final Pattern CALL = Pattern.compile("\\b([A-Z][A-Za-z0-9]*)\\.([a-zA-Z][A-Za-z0-9_]*)\\(");
    private static final Pattern SNAPSHOT_KEY = Pattern.compile("snapshot key `([A-Za-z]+)`");

    private static final String PREFIX = "gregscope_";
    private static final String MOD_PACKAGE = "io.github.ldogg123.gregscope.";

    /** Suffixes Prometheus derives from summaries and histograms; no two families may differ only by one. */
    private static final List<String> DERIVED_SUFFIXES = Arrays
        .asList("_total", "_sum", "_count", "_bucket", "_created");

    /**
     * Snapshot keys that are also label names, with the reason. Every other key in {@link SnapshotKeys} is an open
     * vocabulary and must never become a label (design-v0.2 section 16.2 "never used as labels").
     */
    private static final Set<String> SNAPSHOT_KEYS_ALLOWED_AS_LABELS = new LinkedHashSet<>(
        Arrays.asList(
            // The label carries the same MachineState vocabulary the snapshot key carries.
            "state",
            // Different vocabulary, same word: the snapshot's kind is singleblock|multiblock, the label's kind is
            // the SensorKind machine|item_flow|fluid_flow. Pinned by design-v0.2 section 16.2 and design-v0.3 A7.
            "kind"));

    /** Classes whose getters a family's Source column may name; anything else in a Source cell is prose. */
    private static final Map<String, Class<?>> SOURCE_CLASSES = sourceClasses();

    private static Map<String, Class<?>> sourceClasses() {
        Map<String, Class<?>> map = new java.util.LinkedHashMap<>();
        map.put("TelemetryFrame", TelemetryFrame.class);
        map.put("SensorView", SensorView.class);
        map.put("SamplerStatsView", SamplerStatsView.class);
        map.put("LimitsView", LimitsView.class);
        map.put("CountersView", CountersView.class);
        map.put("MachineCountersView", MachineCountersView.class);
        map.put("MachineSnapshot", MachineSnapshot.class);
        return Collections.unmodifiableMap(map);
    }

    // ---------------------------------------------------------------- the document

    private static String doc;
    private static List<Family> global;
    private static List<Family> common;
    private static List<Family> machine;
    private static List<Family> all;
    private static List<ClosedSet> closedSets;
    private static Set<String> neverLabels;

    /** One row of a family table. */
    private static final class Family {

        final String group;
        final String name;
        final String type;
        final List<String> labels;
        final int series;
        final String source;

        Family(String group, String name, String type, List<String> labels, int series, String source) {
            this.group = group;
            this.name = name;
            this.type = type;
            this.labels = labels;
            this.series = series;
            this.source = source;
        }

        @Override
        public String toString() {
            return group + " " + name;
        }
    }

    /** One row of the closed-set table. */
    private static final class ClosedSet {

        final String label;
        final String family;
        final String generator;
        final List<String> values;

        ClosedSet(String label, String family, String generator, List<String> values) {
            this.label = label;
            this.family = family;
            this.generator = generator;
            this.values = values;
        }

        @Override
        public String toString() {
            return family + "{" + label + "}";
        }
    }

    @BeforeAll
    static void readTheDocument() throws IOException {
        assertTrue(
            Files.isRegularFile(DOC),
            DOC.toAbsolutePath() + " is missing; GS-118 delivers it (design-v0.2 section 16.2)");
        doc = new String(Files.readAllBytes(DOC), StandardCharsets.UTF_8).replace("\r\n", "\n");

        global = families("families:global", "global");
        common = families("families:common", "common");
        machine = families("families:machine", "machine");
        all = new ArrayList<>();
        all.addAll(global);
        all.addAll(common);
        all.addAll(machine);

        closedSets = closedSets();
        neverLabels = new LinkedHashSet<>(backticked(block("never-labels")));

        // The parse itself must not pass vacuously.
        assertTrue(global.size() >= 20, "the global family table did not parse: " + global.size() + " rows");
        assertTrue(common.size() >= 5, "the common family table did not parse: " + common.size() + " rows");
        assertTrue(machine.size() >= 10, "the machine family table did not parse: " + machine.size() + " rows");
        assertTrue(closedSets.size() >= 6, "the closed-set table did not parse: " + closedSets.size() + " rows");
        assertTrue(neverLabels.size() >= 20, "the never-labels block did not parse: " + neverLabels.size() + " names");
    }

    private static String block(String marker) {
        String open = "<!-- contract:" + marker + " -->";
        String close = "<!-- /contract:" + marker + " -->";
        int from = doc.indexOf(open);
        int to = doc.indexOf(close);
        assertTrue(from >= 0, "missing marker " + open + " in " + DOC);
        assertTrue(to > from, "missing or misplaced marker " + close + " in " + DOC);
        return doc.substring(from + open.length(), to);
    }

    /** Markdown table rows inside a marked block, header and separator dropped, cells trimmed. */
    private static List<String[]> rows(String text) {
        List<String[]> out = new ArrayList<>();
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("|")) {
                continue;
            }
            String inner = line.substring(1);
            if (inner.endsWith("|")) {
                inner = inner.substring(0, inner.length() - 1);
            }
            String[] cells = inner.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim();
            }
            if (cells.length < 2 || cells[0].startsWith("---")
                || cells[0].equals("Family")
                || cells[0].equals("Label")) {
                continue;
            }
            out.add(cells);
        }
        return out;
    }

    private static List<String> backticked(String cell) {
        List<String> out = new ArrayList<>();
        Matcher m = BACKTICKED.matcher(cell);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static List<Family> families(String marker, String group) {
        List<Family> out = new ArrayList<>();
        for (String[] cells : rows(block(marker))) {
            assertEquals(5, cells.length, "a family row needs 5 cells: " + Arrays.toString(cells));
            List<String> names = backticked(cells[0]);
            assertEquals(1, names.size(), "a family row names exactly one family: " + cells[0]);
            List<String> labels = "-".equals(cells[2]) ? Collections.<String>emptyList() : backticked(cells[2]);
            int series;
            try {
                series = Integer.parseInt(cells[3]);
            } catch (NumberFormatException e) {
                throw new AssertionError("the Series cell of " + names.get(0) + " is not an integer: " + cells[3]);
            }
            out.add(new Family(group, names.get(0), cells[1], labels, series, cells[4]));
        }
        return out;
    }

    private static List<ClosedSet> closedSets() {
        List<ClosedSet> out = new ArrayList<>();
        for (String[] cells : rows(block("closed-sets"))) {
            assertEquals(4, cells.length, "a closed-set row needs 4 cells: " + Arrays.toString(cells));
            List<String> label = backticked(cells[0]);
            List<String> family = backticked(cells[1]);
            List<String> generator = backticked(cells[2]);
            assertEquals(1, label.size(), "one label per closed-set row: " + cells[0]);
            assertEquals(1, family.size(), "one family per closed-set row: " + cells[1]);
            assertEquals(1, generator.size(), "one generator per closed-set row: " + cells[2]);
            out.add(new ClosedSet(label.get(0), family.get(0), generator.get(0), backticked(cells[3])));
        }
        return out;
    }

    // ---------------------------------------------------------------- generated closed sets

    /** The ten machine states, in the pinned code order of design-v0.2 section 7.1. */
    private static List<String> generatedStateCodes() {
        List<String> out = new ArrayList<>();
        for (int code = 0; code < StateCodes.COUNT; code++) {
            MachineState state = StateCodes.state(code);
            assertNotNull(state, "StateCodes has no state for pinned code " + code);
            out.add(state.id());
        }
        return out;
    }

    /**
     * The lifecycle ids a frame can carry: the design-v0.2 section 10.1 vocabulary minus OVER_CAP, which never gets a
     * registry entry (section 4.2) and so can never reach a frame.
     */
    private static List<String> generatedAvailability() {
        List<String> out = new ArrayList<>();
        for (SensorState state : SensorState.values()) {
            if (state == SensorState.OVER_CAP) {
                continue;
            }
            out.add(HubCodecs.availabilityId(HubCodecs.availabilityCode(state)));
        }
        return out;
    }

    /** The six stored gap reasons, in bit order. */
    private static List<String> generatedStoredGapReasons() {
        List<String> out = new ArrayList<>();
        for (GapReason reason : GapReason.values()) {
            if (reason.isStored()) {
                out.add(reason.id());
            }
        }
        return out;
    }

    private static List<String> generatedSensorKinds() {
        return Arrays.asList(
            SensorKind.label(SensorKind.MACHINE),
            SensorKind.label(SensorKind.ITEM_FLOW),
            SensorKind.label(SensorKind.FLUID_FLOW));
    }

    private static List<String> generate(String generator) {
        if ("state-codes".equals(generator)) {
            return generatedStateCodes();
        }
        if ("availability".equals(generator)) {
            return generatedAvailability();
        }
        if ("gap-reason-stored".equals(generator)) {
            return generatedStoredGapReasons();
        }
        if ("sensor-kind".equals(generator)) {
            return generatedSensorKinds();
        }
        return null;
    }

    // ---------------------------------------------------------------- name rules

    @Test
    void everyFamilyNameMatchesThePrometheusMetricNameRegex() {
        for (Family family : all) {
            assertTrue(
                METRIC_NAME.matcher(family.name)
                    .matches(),
                family + ": metric names must match [a-zA-Z_:][a-zA-Z0-9_:]*");
        }
    }

    @Test
    void everyFamilyNameIsOwnedByGregscopeAndCarriesNoColon() {
        for (Family family : all) {
            assertTrue(family.name.startsWith(PREFIX), family + ": every family is under the gregscope_ prefix");
            assertFalse(
                family.name.indexOf(':') >= 0,
                family + ": ':' is reserved for names produced by recording rules");
        }
    }

    @Test
    void familyNamesAreUniqueAndFreeOfDerivedSuffixCollisions() {
        Set<String> names = new LinkedHashSet<>();
        for (Family family : all) {
            assertTrue(names.add(family.name), "the family " + family.name + " is declared twice");
        }
        for (String name : names) {
            for (String suffix : DERIVED_SUFFIXES) {
                if (!name.endsWith(suffix)) {
                    continue;
                }
                String base = name.substring(0, name.length() - suffix.length());
                assertFalse(
                    names.contains(base),
                    name + " collides with "
                        + base
                        + ": Prometheus derives '"
                        + suffix
                        + "' from summaries and "
                        + "histograms, so the two families could not be told apart");
            }
        }
    }

    @Test
    void counterFamiliesEndInTotalAndGaugesDoNot() {
        for (Family family : all) {
            assertTrue(
                "counter".equals(family.type) || "gauge".equals(family.type),
                family + ": only 'counter' and 'gauge' are used; GregScope exports no histogram or summary");
            if ("counter".equals(family.type)) {
                assertTrue(family.name.endsWith("_total"), family + ": a counter ends in _total");
            } else {
                assertFalse(family.name.endsWith("_total"), family + ": a gauge must not end in _total");
            }
        }
    }

    @Test
    void everyLabelNameMatchesThePrometheusLabelRegexAndIsNotReserved() {
        int checked = 0;
        for (Family family : all) {
            for (String label : family.labels) {
                checked++;
                assertTrue(
                    LABEL_NAME.matcher(label)
                        .matches(),
                    family + " label '" + label + "': label names must match [a-zA-Z_][a-zA-Z0-9_]*");
                assertFalse(
                    label.startsWith("__"),
                    family + " label '" + label + "': names beginning with __ are reserved for Prometheus internals");
                assertFalse(
                    "le".equals(label),
                    family + ": 'le' is reserved by the histogram convention and GregScope exports no histogram");
            }
        }
        assertTrue(checked >= 15, "the label check saw only " + checked + " labels; the tables did not parse");
        // 'quantile' is reserved by the summary convention: exactly one family may use it, and it is a gauge.
        List<Family> withQuantile = new ArrayList<>();
        for (Family family : all) {
            if (family.labels.contains("quantile")) {
                withQuantile.add(family);
            }
        }
        assertEquals(1, withQuantile.size(), "exactly one family may carry the reserved label 'quantile'");
        assertEquals(
            "gauge",
            withQuantile.get(0).type,
            withQuantile.get(0) + ": quantile='max' does not parse as a float, so this family cannot be a summary");
    }

    @Test
    void noForbiddenIdentifierIsUsedAsALabelName() {
        for (Family family : all) {
            for (String label : family.labels) {
                assertFalse(
                    neverLabels.contains(label),
                    family + " uses '" + label + "', which the never-labels list of docs/metrics-model.md forbids");
            }
        }
        // The list itself must be well formed, or a forbidden name could slip through as a typo.
        for (String forbidden : neverLabels) {
            assertFalse(forbidden.isEmpty(), "an empty entry in the never-labels list");
        }
    }

    @Test
    void noSnapshotKeyLeaksIntoALabelName() {
        Set<String> labels = new TreeSet<>();
        for (Family family : all) {
            labels.addAll(family.labels);
        }
        assertTrue(SnapshotKeys.ORDER.size() > 30, "SnapshotKeys.ORDER did not load: " + SnapshotKeys.ORDER.size());
        for (String key : SnapshotKeys.ORDER) {
            if (SNAPSHOT_KEYS_ALLOWED_AS_LABELS.contains(key)) {
                continue;
            }
            assertFalse(
                labels.contains(key),
                "the snapshot key '" + key
                    + "' is used as a label name; schema v1 keys are an open vocabulary and "
                    + "belong in the metric value or the Hub, not in a series identity");
        }
        // The two exceptions must really be snapshot keys, or the allowlist is stale.
        for (String allowed : SNAPSHOT_KEYS_ALLOWED_AS_LABELS) {
            assertTrue(
                SnapshotKeys.ORDER.contains(allowed),
                "'" + allowed + "' is allowlisted but is no longer a snapshot key");
        }
    }

    @Test
    void everyPerSensorFamilyCarriesTheSensorIdLabel() {
        List<Family> perSensor = new ArrayList<>(common);
        perSensor.addAll(machine);
        for (Family family : perSensor) {
            assertTrue(
                family.labels.contains("sensor_id"),
                family + ": design-v0.2 section 16.2, the only series identity is sensor_id");
            assertEquals(
                "sensor_id",
                family.labels.get(0),
                family + ": sensor_id comes first, so every per-sensor series reads the same way");
        }
        for (Family family : global) {
            assertFalse(family.labels.contains("sensor_id"), family + " is global and must not carry sensor_id");
        }
    }

    // ---------------------------------------------------------------- closed label sets

    @Test
    void closedLabelSetsAreGeneratedFromTheEnums() {
        int generated = 0;
        for (ClosedSet set : closedSets) {
            List<String> expected = generate(set.generator);
            if (expected == null) {
                assertEquals("pinned", set.generator, set + ": unknown generator '" + set.generator + "'");
                assertFalse(set.values.isEmpty(), set + ": a pinned set must still list its values");
                continue;
            }
            generated++;
            assertEquals(
                expected,
                set.values,
                set + ": the documented values are not what " + set.generator + " produces from the shipped enums");
            for (String value : set.values) {
                assertTrue(
                    VALUE_ID.matcher(value)
                        .matches(),
                    set + " value '" + value + "': a generated label value must be a lower-case identifier");
            }
        }
        assertTrue(generated >= 5, "only " + generated + " closed sets were regenerated from an enum");

        // The generators themselves must be complete, so a shrunken generator cannot make a set agree by accident.
        assertEquals(StateCodes.COUNT, generatedStateCodes().size(), "one value per pinned state code");
        assertEquals(
            SensorState.values().length - 1,
            generatedAvailability().size(),
            "every SensorState except OVER_CAP");
        assertEquals(GapReason.STORED_COUNT, generatedStoredGapReasons().size(), "one value per stored gap reason");
        assertEquals(3, generatedSensorKinds().size(), "one value per SensorKind");
        assertFalse(
            generatedAvailability().contains("over_cap"),
            "OVER_CAP never reaches a frame, so it must not be an exported label value");
        for (GapReason reason : GapReason.values()) {
            assertEquals(
                reason.isStored(),
                generatedStoredGapReasons().contains(reason.id()),
                reason + ": only stored reasons are counter label values (design-v0.2 section 7.2)");
        }
    }

    @Test
    void everyClosedSetNamesALabelItsFamilyReallyCarries() {
        for (ClosedSet set : closedSets) {
            Family family = null;
            for (Family candidate : all) {
                if (candidate.name.equals(set.family)) {
                    family = candidate;
                }
            }
            assertNotNull(family, set + ": no family table declares " + set.family);
            assertTrue(family.labels.contains(set.label), set + ": " + set.family + " does not carry that label");
            assertFalse(set.values.isEmpty(), set + ": a closed set with no values is not closed, it is empty");
        }
        // The label name 'state' really is overloaded, which is why closed sets are keyed by (family, label).
        Set<String> stateDomains = new LinkedHashSet<>();
        for (ClosedSet set : closedSets) {
            if ("state".equals(set.label)) {
                stateDomains.add(set.values.toString());
            }
        }
        assertEquals(
            2,
            stateDomains.size(),
            "'state' is documented as carrying two disjoint vocabularies; if that changed, section 4 of "
                + "docs/metrics-model.md has to change with it");
    }

    // ---------------------------------------------------------------- sources and cardinality

    @Test
    void everyFamilySourceNamesAGetterThatExists() {
        int checkedCalls = 0;
        int checkedKeys = 0;
        for (Family family : all) {
            Matcher call = CALL.matcher(family.source);
            while (call.find()) {
                Class<?> owner = SOURCE_CLASSES.get(call.group(1));
                if (owner == null) {
                    continue;
                }
                checkedCalls++;
                assertTrue(
                    hasMethod(owner, call.group(2)),
                    family + ": the Source column names "
                        + call.group(1)
                        + "."
                        + call.group(2)
                        + "(), which does "
                        + "not exist");
            }
            Matcher key = SNAPSHOT_KEY.matcher(family.source);
            while (key.find()) {
                checkedKeys++;
                assertTrue(
                    SnapshotKeys.ORDER.contains(key.group(1)),
                    family + ": the Source column names the snapshot key '"
                        + key.group(1)
                        + "', which schema v1 has "
                        + "no such key for");
            }
        }
        assertTrue(
            checkedCalls >= 25,
            "only " + checkedCalls + " getters were checked; the Source cells did not parse");
        assertTrue(checkedKeys >= 8, "only " + checkedKeys + " snapshot keys were checked");
    }

    private static boolean hasMethod(Class<?> owner, String name) {
        for (Method method : owner.getMethods()) {
            if (method.getName()
                .equals(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyCounterTheFrameCarriesHasExactlyOneFamily() {
        for (String counter : counterGetters(SamplerStatsView.class)) {
            assertEquals(
                1,
                countSources(global, "SamplerStatsView." + counter + "("),
                "SamplerStatsView." + counter + "() must have exactly one global family in docs/metrics-model.md");
        }
        List<Family> perSensor = new ArrayList<>(common);
        perSensor.addAll(machine);
        for (String counter : counterGetters(MachineCountersView.class)) {
            assertEquals(
                1,
                countSources(perSensor, "." + counter + "("),
                "MachineCountersView." + counter
                    + "() must have exactly one per-sensor family in "
                    + "docs/metrics-model.md");
        }
        // And nothing was checked vacuously.
        assertEquals(14, counterGetters(SamplerStatsView.class).size(), "SamplerStatsView counter getters");
        assertEquals(7, counterGetters(MachineCountersView.class).size(), "MachineCountersView counter getters");
    }

    /**
     * Distinct names of the public instance getters that end in {@code Total}: the process-lifetime counters of
     * design-v0.2 section 7.6. Overloads (for example {@code gapSecondsTotal()} and {@code gapSecondsTotal(GapReason)})
     * still name one counter, so the result is keyed by name.
     */
    private static Set<String> counterGetters(Class<?> type) {
        Set<String> names = new TreeSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())
                || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            if (method.getName()
                .endsWith("Total")) {
                names.add(method.getName());
            }
        }
        return names;
    }

    private static int countSources(List<Family> families, String needle) {
        int n = 0;
        for (Family family : families) {
            if (family.source.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void theDocumentedCardinalityIsTheSumOfTheFamilyTables() {
        int commonSeries = sum(common);
        int machineSeries = sum(machine);
        int globalSeries = sum(global);

        assertEquals(16, commonSeries, "the common group is the 16 series design-v0.3 section 6.4 counts");
        // 32 before v0.3; the seven single-series buffer gauges of section 7.1 took it to 39. Pinned here rather
        // than read from the document so that adding a family is a deliberate act with a number attached to it.
        assertEquals(39, machineSeries, "the machine group");
        assertEquals(55, commonSeries + machineSeries, "the per-machine-sensor ceiling");
        assertEquals(30, globalSeries, "the global series count");

        int perSensor = commonSeries + machineSeries;
        assertDocContains("**55**", "the per-sensor ceiling");
        assertDocContains(grouped(perSensor * 256), "256 sensors");
        assertDocContains(grouped(perSensor * 512), "512 sensors");
        assertDocContains(grouped(perSensor * 1024), "1024 sensors");
        assertDocContains(grouped(perSensor * 256 + globalSeries), "256 sensors plus the globals");
        assertDocContains(grouped(perSensor * 512 + globalSeries), "512 sensors plus the globals");
        assertDocContains(grouped(perSensor * 1024 + globalSeries), "1024 sensors plus the globals");

        // The closed sets really are what makes the groups that big.
        assertEquals(5, seriesOf("gregscope_sensor_availability"), "one series per frame-reachable lifecycle id");
        assertEquals(10, seriesOf("gregscope_sensor_state"), "one-hot over the ten pinned states");
        assertEquals(10, seriesOf("gregscope_sensor_state_samples_total"), "one counter per pinned state");
        assertEquals(6, seriesOf("gregscope_sensor_gap_seconds_total"), "one counter per stored gap reason");
    }

    private static void assertDocContains(String text, String what) {
        assertTrue(doc.contains(text), "docs/metrics-model.md does not state " + what + " as '" + text + "'");
    }

    private static String grouped(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static int sum(List<Family> families) {
        int n = 0;
        for (Family family : families) {
            n += family.series;
        }
        return n;
    }

    private static int seriesOf(String name) {
        for (Family family : all) {
            if (family.name.equals(name)) {
                return family.series;
            }
        }
        throw new AssertionError("no family " + name + " in docs/metrics-model.md");
    }

    // ---------------------------------------------------------------- the data model

    /**
     * Every GregScope class reachable from {@link TelemetryFrame} through a field type or a no-argument instance
     * getter, plus the concrete classes a real populated frame holds (which is how {@link MachineCountersView} is
     * found behind the {@link CountersView} interface).
     */
    private static Set<Class<?>> frameGraph() {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(TelemetryFrame.class);
        for (Object value : reachableValues(populatedFrame())) {
            queue.add(value.getClass());
        }
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (type == null || !isGregScope(type) || !seen.add(type)) {
                continue;
            }
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                enqueue(queue, field.getGenericType());
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0) {
                    continue;
                }
                enqueue(queue, method.getGenericReturnType());
            }
        }
        return seen;
    }

    private static void enqueue(Deque<Class<?>> queue, Type type) {
        if (type instanceof Class) {
            Class<?> raw = (Class<?>) type;
            queue.add(raw.isArray() ? raw.getComponentType() : raw);
            return;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            enqueue(queue, parameterized.getRawType());
            for (Type argument : parameterized.getActualTypeArguments()) {
                enqueue(queue, argument);
            }
        }
    }

    private static boolean isGregScope(Class<?> type) {
        return type != null && type.getName()
            .startsWith(MOD_PACKAGE);
    }

    @Test
    void everyFieldInTheFrameGraphIsFinal() {
        Set<Class<?>> graph = frameGraph();
        assertTrue(graph.contains(TelemetryFrame.class), "the graph walk did not start");
        assertTrue(graph.contains(SensorView.class), "SensorView is not in the frame graph");
        assertTrue(graph.contains(MachineCountersView.class), "MachineCountersView is not in the frame graph");
        assertTrue(graph.contains(MachineSnapshot.class), "MachineSnapshot is not in the frame graph");
        assertTrue(graph.size() >= 9, "the frame graph found only " + graph.size() + " classes: " + graph);

        for (Class<?> type : graph) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertTrue(
                    Modifier.isFinal(field.getModifiers()),
                    type.getName() + "."
                        + field.getName()
                        + " is not final; a frame is published by a data race on a volatile and only final fields are "
                        + "guaranteed to be visible to the reader");
            }
        }
    }

    @Test
    void theFrameGraphHoldsNoMutableStaticState() {
        for (Class<?> type : frameGraph()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                assertTrue(
                    Modifier.isFinal(field.getModifiers()),
                    type.getName() + "." + field.getName() + " is mutable static state in the frame graph");
            }
        }
    }

    @Test
    void everyFieldTypeInTheFrameGraphIsImmutableOrCopiedOut() {
        for (Class<?> type : frameGraph()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                boolean ok = fieldType.isPrimitive() || fieldType.isEnum()
                    || fieldType == String.class
                    || fieldType == UUID.class
                    || isGregScope(fieldType)
                    || fieldType == List.class
                    || fieldType == Map.class
                    || (fieldType.isArray() && fieldType.getComponentType()
                        .isPrimitive());
                assertTrue(
                    ok,
                    type.getName() + "."
                        + field.getName()
                        + " has type "
                        + fieldType.getName()
                        + ", which is neither an immutable value nor a collection this test knows how to check");
            }
        }
    }

    @Test
    void everyCollectionAPopulatedFrameExposesIsUnmodifiable() {
        int collections = 0;
        for (Object value : reachableValues(populatedFrame())) {
            if (value instanceof Map) {
                collections++;
                final Map<Object, Object> map = castMap(value);
                assertThrows(
                    UnsupportedOperationException.class,
                    () -> map.put("gregscope_contract_probe", "x"),
                    "a map a frame exposes is writable: " + value.getClass()
                        .getName());
            } else if (value instanceof Collection) {
                collections++;
                final Collection<Object> collection = castCollection(value);
                assertThrows(
                    UnsupportedOperationException.class,
                    () -> collection.add("gregscope_contract_probe"),
                    "a collection a frame exposes is writable: " + value.getClass()
                        .getName());
            }
        }
        assertTrue(
            collections >= 3,
            "only " + collections
                + " collections were reached; the populated frame is not populated enough for this "
                + "test to mean anything (expected at least the sensor list, the snapshot map and its warnings list)");
    }

    @Test
    void everyArrayGetterHandsOutAFreshCopy() {
        int arrays = 0;
        for (Object owner : reachableValues(populatedFrame())) {
            if (!isGregScope(owner.getClass())) {
                continue;
            }
            for (Method method : gettersOf(owner.getClass())) {
                if (!method.getReturnType()
                    .isArray()) {
                    continue;
                }
                arrays++;
                long[] first = (long[]) invoke(method, owner);
                long[] second = (long[]) invoke(method, owner);
                assertNotSame(
                    first,
                    second,
                    owner.getClass()
                        .getName() + "."
                        + method.getName()
                        + "() hands out the live array");
                Arrays.fill(first, 0x7F7F7F7FL);
                long[] third = (long[]) invoke(method, owner);
                assertFalse(
                    Arrays.equals(first, third),
                    owner.getClass()
                        .getName() + "."
                        + method.getName()
                        + "(): a caller wrote through the copy");
            }
        }
        assertTrue(arrays >= 2, "only " + arrays + " array getters were reached; the walk did not find the counters");
    }

    @Test
    void theFrameIsPublishedThroughAStaticVolatileField() throws IOException {
        String resource = "io/github/ldogg123/gregscope/sampling/TelemetrySampler.class";
        InputStream stream = TelemetryFrameContractTest.class.getClassLoader()
            .getResourceAsStream(resource);
        assertNotNull(stream, "the compiled " + resource + " is not on the test classpath");
        Map<String, Integer> fields;
        try {
            fields = fieldFlags(stream);
        } finally {
            stream.close();
        }
        Integer flags = fields.get("frame:Lio/github/ldogg123/gregscope/sampling/TelemetryFrame;");
        assertNotNull(
            flags,
            "TelemetrySampler has no TelemetryFrame field called 'frame'; design-v0.2 section 7.7 publishes the frame "
                + "through a static volatile field. Fields seen: "
                + fields.keySet());
        assertTrue((flags & 0x0008) != 0, "TelemetrySampler.frame is not static");
        assertTrue(
            (flags & 0x0040) != 0,
            "TelemetrySampler.frame is not volatile; without it a reader on the HTTP or an OpenComputers thread may "
                + "never see a newly published frame");
        assertTrue((flags & 0x0002) != 0, "TelemetrySampler.frame should stay private; frame() is the reader");
    }

    // ---------------------------------------------------------------- helpers

    /** A frame with one fully populated LIVE sensor: rings, counters, a multiblock snapshot with warnings. */
    private static TelemetryFrame populatedFrame() {
        final long now = 1_700_000_040L;
        SensorRegistryCore core = new SensorRegistryCore(() -> Settings.DEFAULTS, () -> now * 1000L, new NoTeams());
        SensorIdentity identity = new SensorIdentity(new UUID(1L, 2L), "Main EBF", null, null, now);
        core.heartbeat(identity, SensorKind.MACHINE, 0, 1, 64, 2, 1, 100L);
        SensorEntry entry = core.entry(identity.id());
        assertNotNull(entry, "the contract test could not register a sensor");
        assertNotNull(entry.counters(), "a LIVE entry must hold counters for this test to mean anything");

        entry.counters()
            .onSample(StateCodes.RUNNING, true, 480L, 20);
        entry.counters()
            .onGap(GapReason.CHUNK_UNLOADED, 5L);
        entry.counters()
            .onProbeError();
        entry.counters()
            .onRecipesCounterReset();
        entry.setMachineMetadata(7, "Electric Blast Furnace", "Electric Blast Furnace", "running");
        entry.setLastSampleEpochSec(now);
        entry.setLastSnapshot(
            MachineSnapshot.builder()
                .kind(MachineKind.MULTIBLOCK)
                .state(MachineState.RUNNING)
                .statusId("running")
                .statusText("Running")
                .name("Electric Blast Furnace")
                .metaName("Electric Blast Furnace")
                .metaId(7)
                .machineClass("gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace")
                .dimension(0)
                .x(1)
                .y(64)
                .z(2)
                .active(true)
                .allowedToWork(true)
                .hasThingsToDo(true)
                .wasShutdown(false)
                .progressTicks(100)
                .maxProgressTicks(200)
                .progress(0.5)
                .warnings(Arrays.asList("maintenance"))
                .euPerTick(480L)
                .energyStored(100L)
                .energyCapacity(1000L)
                .formed(true)
                .maintenanceIssues(1)
                .recipesCompleted(42L)
                .build());

        return new TelemetryFrame(
            7L,
            123L,
            now * 1000L,
            20,
            Collections.singletonList(new SensorView(entry)),
            new SamplerStatsView(new SamplerStats(), 0L, 0L),
            new LimitsView(Settings.DEFAULTS));
    }

    /** A resolver with no teams at all, so the caps never merge two owners. */
    private static final class NoTeams implements TeamResolver<Object> {

        @Override
        public Object teamOf(UUID player) {
            return null;
        }

        @Override
        public boolean isMember(Object team, UUID player) {
            return false;
        }

        @Override
        public boolean isOfficerOrOwner(Object team, UUID player) {
            return false;
        }
    }

    /**
     * Everything a reader can reach from {@code root} through GregScope no-argument getters, collections and map
     * values. Identity-based, so a cycle cannot loop for ever.
     */
    private static List<Object> reachableValues(Object root) {
        List<Object> out = new ArrayList<>();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Object value = queue.poll();
            if (value == null || seen.put(value, Boolean.TRUE) != null) {
                continue;
            }
            out.add(value);
            if (value instanceof Map) {
                for (Object element : ((Map<?, ?>) value).values()) {
                    offer(queue, element);
                }
                continue;
            }
            if (value instanceof Collection) {
                for (Object element : (Collection<?>) value) {
                    offer(queue, element);
                }
                continue;
            }
            if (!isGregScope(value.getClass())) {
                continue;
            }
            for (Method method : gettersOf(value.getClass())) {
                if (method.getReturnType()
                    .isArray()) {
                    continue;
                }
                offer(queue, invoke(method, value));
            }
        }
        return out;
    }

    /**
     * {@link ArrayDeque} refuses null, and a nullable getter (an unowned sensor, a sensor with no snapshot) is fine.
     */
    private static void offer(Deque<Object> queue, Object value) {
        if (value != null) {
            queue.add(value);
        }
    }

    /** Public no-argument instance methods a GregScope class declares (its own or an inherited GregScope one). */
    private static List<Method> gettersOf(Class<?> type) {
        List<Method> out = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getReturnType() == void.class || method.isSynthetic()) {
                continue;
            }
            if (!isGregScope(method.getDeclaringClass()) || "toString".equals(method.getName())) {
                continue;
            }
            out.add(method);
        }
        return out;
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                "calling " + target.getClass()
                    .getName() + "." + method.getName() + "() failed",
                e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Object value) {
        return (Map<Object, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> castCollection(Object value) {
        return (Collection<Object>) value;
    }

    /**
     * Field access flags of a class file, keyed {@code name:descriptor} (JVMS 4.1, 4.5). The class file is read as
     * bytes and never loaded, so this test needs no Minecraft on the classpath.
     */
    private static Map<String, Integer> fieldFlags(InputStream stream) throws IOException {
        byte[] bytes = readAll(stream);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf8[i] = in.readUTF();
                    break;
                case 7:
                case 8:
                case 16:
                case 19:
                case 20:
                    in.readUnsignedShort();
                    break;
                case 15:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                case 3:
                case 4:
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    in.readInt();
                    break;
                case 5:
                case 6:
                    in.readLong();
                    i++;
                    break;
                default:
                    throw new IOException("unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort(); // access_flags
        in.readUnsignedShort(); // this_class
        in.readUnsignedShort(); // super_class
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            in.readUnsignedShort();
        }
        Map<String, Integer> fields = new java.util.LinkedHashMap<>();
        int fieldCount = in.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) {
            int flags = in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.readUnsignedShort();
                int length = in.readInt();
                if (in.skipBytes(length) != length) {
                    throw new IOException("truncated attribute");
                }
            }
            fields.put(name + ":" + descriptor, flags);
        }
        return fields;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * The binding between the document and this test must be visible from the document, or a later editor will not
     * know that the tables are checked.
     */
    @Test
    void theDocumentIsTheOneThisTestParses() {
        assertTrue(doc.contains("# GregScope metrics model (GS-118)"), "docs/metrics-model.md lost its title");
        assertTrue(
            doc.contains("TelemetryFrameContractTest"),
            "docs/metrics-model.md must name the test that parses it, or the binding is invisible to a reader");
        if (all.isEmpty()) {
            fail("no families parsed");
        }
    }
}
