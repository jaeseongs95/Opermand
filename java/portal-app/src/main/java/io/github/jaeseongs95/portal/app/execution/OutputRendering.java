package io.github.jaeseongs95.portal.app.execution;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import tools.jackson.databind.json.JsonMapper;

import io.github.jaeseongs95.portal.domain.redact.OutputRedactor;

/**
 * The two renderings of a finished command output (the JSON document and the text) and the most bytes they can take together. The renderings are what the output store keeps
 * and what its size limits count, so the reservation made before the command starts has to be as large as the largest rendering the output limits can produce, not as large
 * as the raw streams (defect D-0154: a reservation of the raw size left a command whose output was mostly control characters or invalid UTF-8 with a rendering larger than
 * its reservation, and that output was lost as {@code OUTPUT_CAPTURE_FAILED}).
 *
 * <p><b>The bound.</b> Each stream is decoded as UTF-8 (a byte that is not part of a valid sequence becomes U+FFFD, three bytes), redacted as a whole, and written twice: into the
 * JSON document (compact; a quote or a backslash takes two bytes, a control character two bytes for the short escapes and six for the {@code u00XX} form, every other character
 * its UTF-8 bytes) and into the text. Per byte of a stream the worst case is a control character without a short escape: one byte of text and six of JSON, seven in all. An
 * invalid byte takes three bytes in each rendering, six in all. A secret that the redactor replaces by {@code [REDACTED:name:length]} grows at most to 25 characters for 9 (the
 * shortest match of the bearer pattern with the character that ends it); two renderings of 25 bytes for 9 bytes are under six per byte. So no byte of a stream costs more than
 * {@link #WORST_BYTES_PER_BYTE} bytes in the two renderings, and the fixed part is the JSON frame (25 bytes) and the line feed between the texts. The bound assumes that the
 * JSON mapper of the application writes non-ASCII characters as they are (its default); {@link OpenOutput#finish} still checks the real size, and an output that would not fit
 * is unavailable and reported, never cut silently.
 */
final class OutputRendering {

    /** The most bytes the two renderings take for one byte of a stream (see the class comment). */
    static final int WORST_BYTES_PER_BYTE = 7;
    /** The JSON frame (25 bytes) and the line feed of the text (1 byte), rounded up. */
    static final int FIXED_BYTES = 64;

    private final OutputRedactor redactor;
    private final JsonMapper json;

    OutputRendering(OutputRedactor redactor, JsonMapper json) {
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * The two renderings of an output.
     *
     * @param json the JSON document
     * @param text the text
     */
    record Rendered(byte[] json, byte[] text) {

        /** What the two renderings take together. */
        long size() {
            return (long) json.length + text.length;
        }
    }

    /**
     * The most bytes the two renderings of an output can take when each stream keeps at most {@code perStreamBytes}.
     *
     * @throws ArithmeticException when the bound does not fit a long (a limit larger than the execution limits allow)
     */
    static long maxBytes(long perStreamBytes) {
        if (perStreamBytes < 0) {
            throw new IllegalArgumentException("The bytes kept per stream are not negative");
        }
        return Math.addExact(Math.multiplyExact(2L * WORST_BYTES_PER_BYTE, perStreamBytes), FIXED_BYTES);
    }

    /** Decodes, redacts each stream once as a whole, and writes the JSON document and the text. */
    Rendered render(byte[] stdout, byte[] stderr) {
        String out = redactor.redact(new String(stdout, StandardCharsets.UTF_8)).value();
        String err = redactor.redact(new String(stderr, StandardCharsets.UTF_8)).value();
        byte[] jsonBytes = json.writeValueAsBytes(new OutputDocument(out, err));
        byte[] textBytes = (err.isEmpty() ? out : out + "\n" + err).getBytes(StandardCharsets.UTF_8);
        return new Rendered(jsonBytes, textBytes);
    }
}
