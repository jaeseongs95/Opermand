package io.github.jaeseongs95.portal.server.usecase;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import tools.jackson.databind.json.JsonMapper;

import io.github.jaeseongs95.portal.app.artifact.ArtifactImporter;
import io.github.jaeseongs95.portal.app.artifact.QuarantineNotice;
import io.github.jaeseongs95.portal.app.artifact.StagingTimes;
import io.github.jaeseongs95.portal.app.config.DomainBeans;
import io.github.jaeseongs95.portal.app.file.DownloadService;
import io.github.jaeseongs95.portal.app.file.OperationUploadAdmission;
import io.github.jaeseongs95.portal.app.file.StagedFileService;
import io.github.jaeseongs95.portal.app.lifecycle.StartupSequence;
import io.github.jaeseongs95.portal.app.file.TransferOutcomes;
import io.github.jaeseongs95.portal.app.file.TransferSupport;
import io.github.jaeseongs95.portal.app.file.UploadService;
import io.github.jaeseongs95.portal.app.remote.TransferLimits;
import io.github.jaeseongs95.portal.app.event.ChangeWaiters;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.app.remote.ExecutionLimits;
import io.github.jaeseongs95.portal.app.target.TargetPlatformRegistry;
import io.github.jaeseongs95.portal.domain.approval.ApprovalPolicy;
import io.github.jaeseongs95.portal.domain.capacity.Capacity;
import io.github.jaeseongs95.portal.domain.classify.CommandClassifier;
import io.github.jaeseongs95.portal.domain.id.Digester;
import io.github.jaeseongs95.portal.domain.schedule.AdmissionLimits;
import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.portal.domain.target.TargetOs;
import io.github.jaeseongs95.portal.store.StorageRoots;
import io.github.jaeseongs95.portal.store.artifact.ArtifactLimits;
import io.github.jaeseongs95.portal.store.output.OutputConfiguration;

/**
 * The wiring of the use case slice. Real: every use case bean of portal-app (found by scanning its packages), every storage adapter that the use cases need (found by
 * scanning the store packages, without the audit, capacity and agent CLI adapters, which no use case of this slice touches), the helper beans of
 * {@link DomainBeans}. The store scan includes {@code ArtifactStartupCheck}, which needs {@link QuarantineNotice}; that class is a {@code @Component} of the app artifact
 * package, which the production assembly scans ({@code CoreAssembly}), so it is imported here as the real class like {@link ArtifactImporter}. Plain test classes: the SSH executor ({@link ScriptedExecutor}), the audit port ({@link RecordingAudit}), the two target platforms
 * ({@link TestPlatform}), the targets ({@link FixedTargets}) and a listener of terminal operations. The limits are small numbers a test can reach: four operations at
 * once, two on one target.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({DomainBeans.class, OutputConfiguration.class, TargetPlatformRegistry.class, StagedFileService.class, UploadService.class, DownloadService.class,
    TransferSupport.class, TransferOutcomes.class, OperationUploadAdmission.class, ArtifactImporter.class, QuarantineNotice.class, StartupSequence.class})
@ComponentScan(basePackages = {"io.github.jaeseongs95.portal.app.attachment", "io.github.jaeseongs95.portal.app.event", "io.github.jaeseongs95.portal.app.admission",
    "io.github.jaeseongs95.portal.app.operation", "io.github.jaeseongs95.portal.app.execution", "io.github.jaeseongs95.portal.app.recovery",
    "io.github.jaeseongs95.portal.app.storage"}, excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
    pattern = "io\\.github\\.jaeseongs95\\.portal\\.app\\.execution\\.AgentCliOperationRunner"))
@ComponentScan(basePackages = "io.github.jaeseongs95.portal.store", excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
    pattern = "io\\.github\\.jaeseongs95\\.portal\\.store\\.(audit|capacity|cli)\\..*|io\\.github\\.jaeseongs95\\.portal\\.store\\.StoreConfiguration"))
@EnableJdbcRepositories(basePackages = "io.github.jaeseongs95.portal.store")
class UseCaseWiring {

    @Bean
    ConcurrencyLimits concurrencyLimits() {
        return ConcurrencyLimits.defaults().withGlobalConcurrency(new Capacity(4)).withTargetConcurrency(new Capacity(2));
    }

    @Bean
    AdmissionLimits admissionLimits() {
        return AdmissionLimits.defaults();
    }

    @Bean
    ApprovalPolicy approvalPolicy() {
        return new ApprovalPolicy(new Capacity(100));
    }

    @Bean
    ExecutionLimits executionLimits() {
        return ExecutionLimits.defaults();
    }

    @Bean
    TransferLimits transferLimits() {
        return TransferLimits.defaults();
    }

    @Bean
    StagingTimes stagingTimes() {
        return StagingTimes.defaults();
    }

    /** No disk margin: a test must not fail because the disk of the machine is nearly full; the rule of the margin has its own tests in portal-store. */
    @Bean
    ArtifactLimits artifactLimits() {
        return new ArtifactLimits(ArtifactLimits.DEFAULT_BUDGET_BYTES, ArtifactLimits.MAX_FILE_BYTES, ArtifactLimits.CHUNK_BYTES, ArtifactLimits.UPLOAD_TTL, 0);
    }

    /** The SFTP boundary, the other thing that is not real: it follows the contract of the adapter and answers what a test says. */
    @Bean
    ScriptedTransfer transfer(ScriptedExecutor executor) {
        return new ScriptedTransfer(executor);
    }

    /** One pool serves as the {@code TaskScheduler} (timers) and the {@code TaskExecutor} (the dispatcher and the executions). */
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(8);
        scheduler.setThreadNamePrefix("usecase-");
        return scheduler;
    }

    @Bean
    JsonMapper jsonMapper() {
        return JsonMapper.builder().build();
    }

    /** The output folder is made by the store (private to its owner) below a temporary folder of this context. */
    @Bean
    StorageRoots storageRoots() throws IOException {
        Path base = Files.createTempDirectory("portal-usecase");
        return new StorageRoots(base.resolve("artifacts"), base.resolve("outputs"));
    }

    @Bean
    RecordingAudit audit() {
        return new RecordingAudit();
    }

    @Bean
    FixedTargets targets(Digester digester) {
        return new FixedTargets(digester);
    }

    @Bean
    TestPlatform linuxPlatform(CommandClassifier classifier) {
        return new TestPlatform(TargetOs.LINUX, classifier);
    }

    @Bean
    TestPlatform windowsPlatform(CommandClassifier classifier) {
        return new TestPlatform(TargetOs.WINDOWS, classifier);
    }

    @Bean
    ScriptedExecutor executor() {
        return new ScriptedExecutor();
    }

    @Bean
    RecordingTerminalListener terminalListener() {
        return new RecordingTerminalListener();
    }

    @Bean
    UseCaseKit kit(Digester digester, FixedTargets targets, ChangeWaiters waiters, OperationJournal journal) {
        return new UseCaseKit(digester, targets, waiters, journal);
    }

    /** Enforces PostgreSQL aborted-transaction semantics (SQLState 25P02) on the embedded H2 DataSource across all @UseCaseTest suites. */
    @Bean
    static BeanPostProcessor pgAbortedTransactionDataSource() {
        Set<String> creators = Set.of("createStatement", "prepareStatement", "prepareCall");
        Set<String> executors = Set.of("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeUpdate", "executeLargeBatch");
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof DataSource dataSource)) {
                    return bean;
                }
                return Proxy.newProxyInstance(UseCaseWiring.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = invoke(dataSource, method, args);
                    if ("getConnection".equals(method.getName()) && result instanceof Connection connection) {
                        return proxyConnection(connection, creators, executors);
                    }
                    return result;
                });
            }
        };
    }

    private static Connection proxyConnection(Connection delegate, Set<String> creators, Set<String> executors) {
        boolean[] aborted = {false};
        return (Connection) Proxy.newProxyInstance(UseCaseWiring.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            String name = method.getName();
            if ("rollback".equals(name) || "close".equals(name) || ("setAutoCommit".equals(name) && args != null && args.length == 1 && Boolean.TRUE.equals(args[0]))) {
                aborted[0] = false;
                return invoke(delegate, method, args);
            }
            if ("commit".equals(name) && aborted[0]) {
                aborted[0] = false;
                delegate.rollback();
                throw aborted25P02();
            }
            if (creators.contains(name)) {
                if (aborted[0]) {
                    throw aborted25P02();
                }
                Object created;
                try {
                    created = invoke(delegate, method, args);
                } catch (SQLException e) {
                    markAborted(delegate, aborted);
                    throw e;
                }
                if (created instanceof CallableStatement c) {
                    return proxyStatement(c, CallableStatement.class, delegate, aborted, executors);
                }
                if (created instanceof PreparedStatement p) {
                    return proxyStatement(p, PreparedStatement.class, delegate, aborted, executors);
                }
                if (created instanceof Statement s) {
                    return proxyStatement(s, Statement.class, delegate, aborted, executors);
                }
                return created;
            }
            if (("setSavepoint".equals(name) || "releaseSavepoint".equals(name)) && aborted[0]) {
                throw aborted25P02();
            }
            return invoke(delegate, method, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T extends Statement> T proxyStatement(T statement, Class<T> iface, Connection connection, boolean[] aborted, Set<String> executors) {
        return (T) Proxy.newProxyInstance(UseCaseWiring.class.getClassLoader(), new Class<?>[] {iface}, (proxy, method, args) -> {
            if (executors.contains(method.getName())) {
                if (aborted[0]) {
                    throw aborted25P02();
                }
                try {
                    return invoke(statement, method, args);
                } catch (SQLException e) {
                    markAborted(connection, aborted);
                    throw e;
                }
            }
            return invoke(statement, method, args);
        });
    }

    private static void markAborted(Connection connection, boolean[] aborted) {
        try {
            if (!connection.getAutoCommit()) {
                aborted[0] = true;
            }
        } catch (SQLException ignored) {
            // Connection already closed.
        }
    }

    private static SQLException aborted25P02() {
        return new SQLException("ERROR: current transaction is aborted, commands ignored until end of transaction block", "25P02");
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
