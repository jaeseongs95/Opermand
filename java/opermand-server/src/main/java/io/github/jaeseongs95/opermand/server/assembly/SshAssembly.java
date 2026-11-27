package io.github.jaeseongs95.opermand.server.assembly;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * The SSH adapter of the assembly: the beans of the session, exec, sftp, platform, cli, profile and hostkey packages of opermand-ssh (the session pool, the executor, the file
 * transfer, the two target platforms, the agent CLI installer, the profile registry and the credentials, the host key policy). opermand-ssh may not depend on opermand-store, so
 * it is only here, in the module that sees both, that the adapter and the use cases that call its ports meet. The settings it asks for ({@code SshSettings},
 * {@code SessionReapSettings}, {@code ProfileCatalog}, {@code FilePathPolicy}) are made by {@link DefaultAssembly} or by a test.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = {"io.github.jaeseongs95.opermand.ssh.session", "io.github.jaeseongs95.opermand.ssh.exec", "io.github.jaeseongs95.opermand.ssh.sftp",
    "io.github.jaeseongs95.opermand.ssh.platform", "io.github.jaeseongs95.opermand.ssh.cli", "io.github.jaeseongs95.opermand.ssh.profile", "io.github.jaeseongs95.opermand.ssh.hostkey"})
public class SshAssembly {
}
