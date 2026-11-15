package io.github.jaeseongs95.portal.store.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.store.migration.MigrationException.Reason;

/**
 * One migration script of the jar: {@code V<number>__<description>.sql} under a location such as {@code db/expand}. The number orders the scripts; the digest is the SHA-256 of the
 * file's bytes as they are in the jar, so it is the same on every database whatever type name {@code ${long_text}} becomes there.
 */
record MigrationScript(long version, String file, byte[] bytes, String digest) {
    private static final Pattern NAME = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final String LONG_TEXT = "${long_text}";

    /** A script from its bytes: the digest is computed here. */
    static MigrationScript of(long version, String file, byte[] bytes) {
        return new MigrationScript(version, file, bytes, new Sha256Digester().digest(bytes).hex());
    }

    /** The text of the script as the database takes it: the long text type of the database for {@code ${long_text}}; nothing else is rewritten. */
    String text(String longTextType) {
        return new String(bytes, StandardCharsets.UTF_8).replace(LONG_TEXT, longTextType);
    }

    /** Every script under {@code location} (a folder of the classpath and its subfolders), in number order. A number used twice is a failure. */
    static List<MigrationScript> load(String location) {
        List<MigrationScript> scripts = new ArrayList<>();
        Set<Long> numbers = new HashSet<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath*:" + location + "/**/V*__*.sql")) {
                String file = resource.getFilename();
                Matcher name = file == null ? null : NAME.matcher(file);
                if (name == null || !name.matches()) {
                    continue;
                }
                long version = Long.parseLong(name.group(1));
                if (!numbers.add(version)) {
                    throw new MigrationException(Reason.MIGRATION_FAILED, "Two migration scripts have the number " + version + " under " + location + ".");
                }
                scripts.add(of(version, file, resource.getContentAsByteArray()));
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("The migration scripts under " + location + " cannot be read.", failure);
        }
        scripts.sort(Comparator.comparingLong(MigrationScript::version));
        return List.copyOf(scripts);
    }
}
