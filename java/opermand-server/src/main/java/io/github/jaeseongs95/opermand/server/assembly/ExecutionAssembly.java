package io.github.jaeseongs95.opermand.server.assembly;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import io.github.jaeseongs95.opermand.app.file.OperationUploadAdmission;

/**
 * The execution side of the assembly: the use cases of the event, admission, operation, execution and recovery packages of opermand-app (the change waiters, the request
 * entrance, the intake, the dispatcher and its runners, the recovery of the journal at the start) and the target platforms' registry of the target package, plus the intake of
 * the uploads that the file use cases make ({@link OperationUploadAdmission}). The beans it asks for and does not make are the limits, the targets, the executor and the
 * scheduler: a test supplies them and {@link DefaultAssembly} gives the defaults.
 */
@Configuration(proxyBeanMethods = false)
@Import(OperationUploadAdmission.class)
@ComponentScan(basePackages = {"io.github.jaeseongs95.opermand.app.event", "io.github.jaeseongs95.opermand.app.admission", "io.github.jaeseongs95.opermand.app.operation",
    "io.github.jaeseongs95.opermand.app.execution", "io.github.jaeseongs95.opermand.app.recovery", "io.github.jaeseongs95.opermand.app.target"})
public class ExecutionAssembly {
}
