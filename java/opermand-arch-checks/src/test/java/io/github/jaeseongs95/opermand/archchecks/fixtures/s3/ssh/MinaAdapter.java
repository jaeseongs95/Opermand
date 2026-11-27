package io.github.jaeseongs95.opermand.archchecks.fixtures.s3.ssh;

import org.apache.sshd.client.SshClient;

/** The allowed home of the SSH library: a class of opermand-ssh uses MINA. No rule may flag it (RuleFixturesTest). */
final class MinaAdapter {
    Object client() {
        return SshClient.setUpDefaultClient();
    }
}
