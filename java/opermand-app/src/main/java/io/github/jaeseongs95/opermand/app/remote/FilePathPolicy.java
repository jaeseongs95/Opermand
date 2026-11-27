package io.github.jaeseongs95.opermand.app.remote;

import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.path.RemotePath;

/**
 * The allowed-root rule and the test-server bypass for the files of a target. The implementation belongs to stage 4 (test-server mode); stage 3 tests use a fake.
 * The adapter asks before it touches a file and honours the answer.
 */
public interface FilePathPolicy {

    PathPolicyDecision decide(TargetId target, RemotePath path);
}
