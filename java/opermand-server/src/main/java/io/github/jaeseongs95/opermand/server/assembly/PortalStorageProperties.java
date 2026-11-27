package io.github.jaeseongs95.opermand.server.assembly;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional storage overrides bound outside the legacy {@code BRIDGE_} namespace so {@code A-VALUE}, {@code A-ENV}, and {@code LegacyKeyGuard} remain strictly enforced.
 *
 * @param keyFile optional override path for the attachment encryption keyring file ({@code portal.storage.key-file} / {@code PORTAL_STORAGE_KEY_FILE}); when omitted,
 *     defaults to {@code <keyring>/attachment.keys}
 */
@ConfigurationProperties(prefix = "portal.storage")
public record PortalStorageProperties(Path keyFile) {
}
