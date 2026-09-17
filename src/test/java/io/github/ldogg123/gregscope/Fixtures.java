package io.github.ldogg123.gregscope;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Loads golden fixtures from {@code src/test/resources/fixtures/}. */
public final class Fixtures {

    private Fixtures() {}

    public static byte[] bytes(String path) {
        try (InputStream in = Fixtures.class.getClassLoader()
            .getResourceAsStream("fixtures/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A {@code .hex} fixture: hex byte pairs separated by whitespace; {@code #} starts a comment. */
    public static byte[] hex(String path) {
        String text = new String(bytes(path), StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String line : text.split("\n")) {
            int hash = line.indexOf('#');
            String data = hash >= 0 ? line.substring(0, hash) : line;
            for (String token : data.trim()
                .split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.length() != 2) {
                    throw new IllegalStateException("bad hex token '" + token + "' in " + path);
                }
                out.write(Integer.parseInt(token, 16));
            }
        }
        return out.toByteArray();
    }
}
