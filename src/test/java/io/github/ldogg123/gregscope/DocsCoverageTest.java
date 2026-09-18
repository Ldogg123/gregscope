package io.github.ldogg123.gregscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.ldogg123.gregscope.config.ConfigKeys;
import io.github.ldogg123.gregscope.sensor.SensorNbtCodec;

/**
 * GS-120's acceptance criterion: every name a player or a script can see must be written down somewhere in
 * {@code docs/}, and the list of names is read from the <b>code</b> rather than copied into this test.
 *
 * <p>
 * Three vocabularies are covered, each read a way that cannot go stale:
 * <ul>
 * <li><b>Config keys</b> from {@link ConfigKeys#ALL}, which is also what writes {@code config/gregscope.cfg}, so a
 * key added to the config is a key this test starts demanding documentation for.</li>
 * <li><b>Sensor NBT keys</b> from {@link SensorNbtCodec}'s own {@code public static final String} fields, reflectively
 * - these are the keys that survive in a world save, so a player recovering data by hand needs them written down.</li>
 * <li><b>OpenComputers callbacks</b> read out of the <b>class files</b> of the two environments, by parsing the
 * method table for OC's {@code @Callback} annotation. Those classes import OpenComputers, which a plain-JVM unit test
 * must never load, so the bytes are parsed instead - the same trick {@code ShippedClassesTest} and
 * {@code TelemetryFrameContractTest} use.</li>
 * </ul>
 *
 * <p>
 * Both halves are guarded against passing vacuously: a minimum count for each vocabulary, and a check that the
 * documents were really read. A test that finds no keys would otherwise be perfectly green.
 */
class DocsCoverageTest {

    /** Where docs live, relative to the module root Gradle runs tests from. */
    private static final String DOCS = "docs";

    /** The OC annotation, as it appears in a class file's {@code RuntimeVisibleAnnotations}. */
    private static final String CALLBACK_DESCRIPTOR = "Lli/cil/oc/api/machine/Callback;";

    private static final String[] OC_ENVIRONMENTS = {
        "io/github/ldogg123/gregscope/integration/opencomputers/GregTechMachineEnvironment.class",
        "io/github/ldogg123/gregscope/integration/opencomputers/HubEnvironment.class" };

    /** Every documentation file, by name, so a failure can say which ones were searched. */
    private static Map<String, String> docs;

    @BeforeAll
    static void readDocs() throws IOException {
        docs = new LinkedHashMap<>();
        File dir = new File(DOCS);
        assertTrue(dir.isDirectory(), "no docs/ directory next to the module root; this test reads the real files");
        collect(dir, docs);
        assertTrue(docs.size() >= 6, "only " + docs.size() + " documentation files were read: " + docs.keySet());
    }

    private static void collect(File dir, Map<String, String> into) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, into);
            } else if (child.getName()
                .endsWith(".md")) {
                    into.put(child.getPath(), read(child));
                }
        }
    }

    /**
     * Every config key's full {@code category.name} path must appear in the docs. This is the vocabulary a server
     * owner edits by hand, so an undocumented key is one nobody can be expected to find.
     */
    @Test
    void everyConfigKeyIsDocumented() {
        List<String> paths = new ArrayList<>();
        for (ConfigKeys.Key key : ConfigKeys.ALL) {
            paths.add(key.path());
        }
        assertTrue(paths.size() >= 12, "ConfigKeys.ALL did not load: " + paths.size() + " keys");
        assertUndocumented("config key", paths, "docs/sensors-and-hub.md");
    }

    /**
     * Every NBT key the sensor cover writes. These outlive the mod: they are what is left in the world save when
     * GregScope is removed, so design-v0.2 section 8.5's "mod removal" promise depends on them being written down.
     */
    @Test
    void everySensorNbtKeyIsDocumented() {
        List<String> keys = new ArrayList<>();
        for (Field field : SensorNbtCodec.class.getDeclaredFields()) {
            if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && Modifier.isPublic(field.getModifiers())) {
                try {
                    keys.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    fail("could not read " + field.getName() + ": " + e);
                }
            }
        }
        assertTrue(keys.size() >= 8, "SensorNbtCodec exposed only " + keys.size() + " string keys: " + keys);
        assertUndocumented("sensor NBT key", keys, "docs/sensors-and-hub.md", "docs/history-format-v1.md");
    }

    /**
     * Every OpenComputers callback, read from the environments' class files. This is the scripting API: an
     * undocumented callback is one no player can discover without reading the mod's source.
     */
    @Test
    void everyOpenComputersCallbackIsDocumented() throws IOException {
        Set<String> callbacks = new TreeSet<>();
        for (String resource : OC_ENVIRONMENTS) {
            File file = classFile(resource);
            assertTrue(file != null && file.isFile(), "class file not found for " + resource + "; build first");
            try (InputStream in = new FileInputStream(file)) {
                callbacks.addAll(annotatedMethods(in, CALLBACK_DESCRIPTOR));
            }
        }
        assertTrue(
            callbacks.size() >= 6,
            "only " + callbacks.size()
                + " @Callback methods were found in the OC environments ("
                + callbacks
                + "); the class-file parse did not work, so this test would pass without checking anything");
        assertUndocumented("OpenComputers callback", new ArrayList<>(callbacks), "docs/opencomputers.md");
    }

    /**
     * The documents this test's own claims depend on must exist. GS-120 introduces two of them, and the whole point
     * of the coverage check is lost if the file a name is documented in is one nobody wrote.
     */
    @Test
    void theDocumentsGs120PromisesExist() {
        for (String required : new String[] { "docs/sensors-and-hub.md", "docs/history-format-v1.md",
            "docs/metrics-model.md", "docs/opencomputers.md", "docs/testing.md" }) {
            assertTrue(
                docs.keySet()
                    .stream()
                    .anyMatch(
                        path -> path.replace('\\', '/')
                            .endsWith(required)),
                required + " is missing; docs read: " + docs.keySet());
        }
    }

    /**
     * Fails naming every value of {@code kind} that does not appear <b>as a delimited token</b> in at least one of
     * {@code where}.
     *
     * <p>
     * Two things make this strict on purpose. First, a plain {@code contains} would be close to vacuous: the sensor
     * NBT keys are short - {@code gs}, {@code ct}, {@code lbl} - and a bare substring search finds {@code ct} inside
     * "collect" and {@code gs} inside "flags", so every key would count as documented by accident. A name is
     * therefore only documented when it appears in a markdown code span or in quotes, which is how these documents
     * write every identifier anyway.
     *
     * <p>
     * Second, the search is limited to the documents a <em>reader</em> would actually go to. Searching all of
     * {@code docs/} sounds more generous but is weaker than it looks: this very test's row in {@code testing.md}
     * names the two config keys it once caught as missing, which would be enough to "document" them for ever after.
     * A design note or a test description mentioning a key in passing is not documentation, so a config key has to
     * be in the player guide and a callback in the OpenComputers reference.
     */
    private static void assertUndocumented(String kind, List<String> values, String... where) {
        List<String> searched = new ArrayList<>();
        for (Map.Entry<String, String> doc : docs.entrySet()) {
            String path = doc.getKey()
                .replace(File.separatorChar, '/');
            for (String wanted : where) {
                if (path.endsWith(wanted)) {
                    searched.add(doc.getValue());
                }
            }
        }
        assertEquals(
            where.length,
            searched.size(),
            "expected to search " + Arrays.toString(where)
                + " but found "
                + searched.size()
                + " of them; a missing file would make this check pass without reading anything");
        List<String> missing = new ArrayList<>();
        for (String value : values) {
            boolean found = false;
            for (String text : searched) {
                if (text.contains('`' + value + '`') || text.contains('"' + value + '"')) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(value);
            }
        }
        assertEquals(
            "[]",
            missing.toString(),
            missing.size() + " of "
                + values.size()
                + " "
                + kind
                + "s are not documented in "
                + Arrays.toString(where)
                + ". A mention in a design note or a test description does not count: these "
                + "are the files a reader is pointed at.");
    }

    private static File classFile(String resource) {
        for (String root : new String[] { "build/classes/java/main", "bin/main", "out/production/classes" }) {
            File candidate = new File(root, resource);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), Charset.forName("UTF-8"));
        }
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
     * Names of the methods a class file declares that carry {@code descriptor} in their
     * {@code RuntimeVisibleAnnotations} (JVMS 4.1, 4.6, 4.7.16). The class is read as bytes and never loaded, so a
     * plain-JVM test can look inside a class that imports OpenComputers.
     */
    private static Set<String> annotatedMethods(InputStream stream, String descriptor) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(readAll(stream)));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        String[] utf8 = constantPool(in);

        in.readUnsignedShort(); // access_flags
        in.readUnsignedShort(); // this_class
        in.readUnsignedShort(); // super_class
        skipShorts(in, in.readUnsignedShort()); // interfaces
        skipMembers(in, utf8); // fields

        Set<String> annotated = new LinkedHashSet<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort(); // access_flags
            String name = utf8[in.readUnsignedShort()];
            in.readUnsignedShort(); // descriptor
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attribute = utf8[in.readUnsignedShort()];
                int length = in.readInt();
                byte[] body = new byte[length];
                in.readFully(body);
                if ("RuntimeVisibleAnnotations".equals(attribute) && mentions(body, utf8, descriptor)) {
                    annotated.add(name);
                }
            }
        }
        return annotated;
    }

    /**
     * True if the annotations blob names {@code descriptor}.
     *
     * <p>
     * Every {@code u2} in the blob is resolved against the constant pool and compared, rather than walking the
     * annotation structure properly. Walking it means recursing through {@code element_value} pairs, and getting
     * that subtly wrong is how a checker like this quietly stops matching. Scanning can only ever over-match - it
     * would also fire if some unrelated element value happened to point at this exact descriptor string, which no
     * annotation in this codebase does - and over-matching here costs nothing: the worst case is demanding
     * documentation for a method that turns out not to be a callback, which fails loudly and visibly rather than
     * silently passing.
     */
    private static boolean mentions(byte[] body, String[] utf8, String descriptor) {
        for (int i = 0; i + 1 < body.length; i++) {
            int index = ((body[i] & 0xFF) << 8) | (body[i + 1] & 0xFF);
            if (index > 0 && index < utf8.length && descriptor.equals(utf8[index])) {
                return true;
            }
        }
        return false;
    }

    private static String[] constantPool(DataInputStream in) throws IOException {
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
        return utf8;
    }

    private static void skipShorts(DataInputStream in, int n) throws IOException {
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();
        }
    }

    private static void skipMembers(DataInputStream in, String[] utf8) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                in.readUnsignedShort();
                int length = in.readInt();
                if (in.skipBytes(length) != length) {
                    throw new IOException("truncated attribute");
                }
            }
        }
    }
}
