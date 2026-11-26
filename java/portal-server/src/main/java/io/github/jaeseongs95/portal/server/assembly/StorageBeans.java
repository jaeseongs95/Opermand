package io.github.jaeseongs95.portal.server.assembly;

import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import io.github.jaeseongs95.portal.app.artifact.StagingTimes;
import io.github.jaeseongs95.portal.app.remote.TransferLimits;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.artifact.ArtifactLimits;
import io.github.jaeseongs95.portal.store.attachment.AttachmentKeyLocation;
import io.github.jaeseongs95.portal.store.attachment.AttachmentKeyring;
import io.github.jaeseongs95.portal.store.audit.AuditFileLocation;

/**
 * The value beans that portal-store and portal-app ask for and cannot make themselves: {@link StorageRoots}, {@link AttachmentKeyLocation}, {@link ArtifactLimits},
 * {@link AuditFileLocation}, {@link TransferLimits}, and {@link StagingTimes}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PortalStorageProperties.class)
public class StorageBeans {

    private final StorageSettings settings;

    public StorageBeans(StorageSettings settings) {
        this.settings = settings;
    }

    @Bean
    public StorageRoots storageRoots() {
        return new StorageRoots(settings.artifactsFolder(), settings.outputsFolder(), settings.attachmentsFolder(), settings.keyringFolder());
    }

    @Bean
    public AttachmentKeyLocation attachmentKeyLocation(StorageRoots roots, PortalStorageProperties properties) {
        Path chosen = properties.keyFile() != null ? properties.keyFile()
            : settings.keyFile() != null ? settings.keyFile()
            : roots.keyring().resolve(AttachmentKeyring.DEFAULT_FILE_NAME);
        return new AttachmentKeyLocation(chosen);
    }

    @Bean
    public ArtifactLimits artifactLimits() {
        return settings.artifactLimits();
    }

    @Bean
    public AuditFileLocation auditFileLocation() {
        return new AuditFileLocation(settings.auditFile());
    }

    @Bean
    public TransferLimits transferLimits() {
        return new TransferLimits(TransferLimits.DEFAULT_DEADLINE, DataSize.ofBytes(settings.artifactLimits().maxFileBytes()));
    }

    @Bean
    public StagingTimes stagingTimes() {
        return settings.stagingTimes();
    }
}
