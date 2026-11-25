package io.github.jaeseongs95.portal.app.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import io.github.jaeseongs95.portal.domain.redact.VendorOutputRedactor;

/**
 * The reservation of a command output has to cover the two renderings that are stored, not the raw streams (defect D-0154). The test renders streams made of one byte value
 * repeated, for every byte value and for both streams at once, and of the shortest secret that the redactor makes longer, with the redactor and the JSON mapper that the
 * application uses, and compares the real size of the renderings with {@link OutputRendering#maxBytes}.
 */
class OutputRenderingTest {

    private static final int STREAM_BYTES = 2_000;

    private final OutputRendering rendering = new OutputRendering(new VendorOutputRedactor(), JsonMapper.builder().build());

    @Test
    @DisplayName("S-D0154-03 D-0154 for no byte value, on either stream or on both, do the two renderings take more than the reservation of the cap")
    void noByteValueExceedsTheReservation() {
        long reserved = OutputRendering.maxBytes(STREAM_BYTES);
        for (int value = 0; value < 256; value++) {
            byte[] stream = new byte[STREAM_BYTES];
            Arrays.fill(stream, (byte) value);
            assertThat(rendering.render(stream, stream).size()).as("byte value %d on both streams", value).isLessThanOrEqualTo(reserved);
            assertThat(rendering.render(stream, new byte[0]).size()).as("byte value %d on standard output", value).isLessThanOrEqualTo(reserved);
            assertThat(rendering.render(new byte[0], stream).size()).as("byte value %d on standard error", value).isLessThanOrEqualTo(reserved);
        }
    }

    @Test
    @DisplayName("S-D0154-04 D-0154 the raw size of the old reservation was too small: control characters on both streams take more than twice the cap plus 256 KiB at the default cap")
    void theRawReservationWasTooSmall() {
        int cap = 1024 * 1024;
        byte[] controls = new byte[cap];
        Arrays.fill(controls, (byte) 0x01);

        long rendered = rendering.render(controls, controls).size();

        assertThat(rendered).isGreaterThan(2L * cap + 256 * 1024);
        assertThat(rendered).isLessThanOrEqualTo(OutputRendering.maxBytes(cap));
    }

    @Test
    @DisplayName("S-D0154-05 D-0154 a secret that the redactor replaces by a longer marker, repeated to the cap, still fits")
    void aSecretThatGrowsStillFits() {
        byte[] stream = "Bearer a\n".repeat(STREAM_BYTES / 9).getBytes(StandardCharsets.UTF_8);

        long rendered = rendering.render(stream, stream).size();

        assertThat(rendered).as("the markers are longer than the secrets").isGreaterThan(2L * stream.length);
        assertThat(rendered).isLessThanOrEqualTo(OutputRendering.maxBytes(stream.length));
    }

    @Test
    @DisplayName("S-D0154-06 D-0154 the reservation is a bound of the largest rendering, grows with the cap, and does not wrap round for the largest cap the execution limits allow")
    void theBoundIsMonotonicAndDoesNotOverflow() {
        assertThat(OutputRendering.maxBytes(0)).isEqualTo(OutputRendering.FIXED_BYTES);
        assertThat(OutputRendering.maxBytes(1_000)).isLessThan(OutputRendering.maxBytes(1_001));
        assertThat(OutputRendering.maxBytes(Integer.MAX_VALUE)).isEqualTo(2L * OutputRendering.WORST_BYTES_PER_BYTE * Integer.MAX_VALUE + OutputRendering.FIXED_BYTES);
        assertThatThrownBy(() -> OutputRendering.maxBytes(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
