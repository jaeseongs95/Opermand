package io.github.jaeseongs95.portal.domain.attachment;

import java.util.HexFormat;
import java.util.Objects;
import java.util.random.RandomGenerator;

/** Makes attachment ids: 24 random bytes as 48 lowercase hex characters. */
public final class AttachmentIdGenerator {

    private static final int RANDOM_BYTES = 24;

    private final RandomGenerator random;

    public AttachmentIdGenerator(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public AttachmentId next() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return new AttachmentId(HexFormat.of().formatHex(bytes));
    }
}
