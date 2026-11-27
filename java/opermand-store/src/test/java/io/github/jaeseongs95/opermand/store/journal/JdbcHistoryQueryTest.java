package io.github.jaeseongs95.opermand.store.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.opermand.app.attachment.AttachmentUnreadableException;
import io.github.jaeseongs95.opermand.app.journal.HistoryCursor;
import io.github.jaeseongs95.opermand.app.journal.HistoryFilter;
import io.github.jaeseongs95.opermand.app.journal.HistoryPage;
import io.github.jaeseongs95.opermand.app.journal.TargetFilter;
import io.github.jaeseongs95.opermand.domain.attachment.AttachmentId;
import io.github.jaeseongs95.opermand.domain.error.HistoryErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.operation.CommandInput;
import io.github.jaeseongs95.opermand.domain.operation.ConflictKey;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.StartAuthority;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.store.attachment.AttachmentFiles;

@JournalTest
class JdbcHistoryQueryTest {

    private final JdbcOperationJournal journal;
    private final JdbcHistoryQuery history;
    private final AttachmentFiles attachmentFiles;
    private final JdbcTemplate jdbc;
    private final JournalFixture fx = new JournalFixture();

    JdbcHistoryQueryTest(JdbcOperationJournal journal, JdbcHistoryQuery history, AttachmentFiles attachmentFiles, JdbcTemplate jdbc) {
        this.journal = journal;
        this.history = history;
        this.attachmentFiles = attachmentFiles;
        this.jdbc = jdbc;
    }

    private List<OperationId> allPages(HistoryFilter filter, int pageSize) {
        List<OperationId> ids = new ArrayList<>();
        HistoryCursor cursor = new HistoryCursor.FirstPage();
        for (int guard = 0; guard < 100; guard++) {
            HistoryPage page = history.page(filter, cursor, pageSize);
            page.operations().forEach(operation -> ids.add(operation.id()));
            if (page.next() instanceof HistoryCursor.End) {
                return ids;
            }
            cursor = page.next();
        }
        throw new AssertionError("The pages did not end");
    }

    @Test
    @DisplayName("RC-A-028: the pages of the history cover every operation once, newest first, although all of them were created at the same instant")
    void keysetPagingCoversEveryOperationOnce() {
        for (int number = 0; number < 25; number++) {
            fx.accept(journal, fx.command("h" + number, number % 2 == 0 ? "alpha" : "beta", "echo " + number, new ConflictKey.None()));
        }

        List<OperationId> ids = allPages(new HistoryFilter(Set.of(), new TargetFilter.AnyTarget()), 10);

        assertThat(ids).hasSize(25).doesNotHaveDuplicates();
        assertThat(ids.stream().map(OperationId::value).toList()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(history.page(new HistoryFilter(Set.of(), new TargetFilter.AnyTarget()), new HistoryCursor.FirstPage(), 25).next()).isInstanceOf(HistoryCursor.End.class);
    }

    @Test
    @DisplayName("RC-A-028: the filter by target and by state is applied on every page")
    void theFilterAppliesOnEveryPage() {
        for (int number = 0; number < 12; number++) {
            Operation accepted = fx.accept(journal, fx.command("f" + number, number % 3 == 0 ? "alpha" : "beta", "echo " + number, new ConflictKey.None())).operation();
            if (number % 2 != 0) {
                journal.save(accepted.enqueue(new StartAuthority.Owner(), JournalFixture.NOW), accepted.version());
            }
        }

        List<OperationId> alphaPending = allPages(new HistoryFilter(Set.of(OperationState.PENDING), new TargetFilter.OnlyTarget(new TargetId("alpha"))), 2);

        assertThat(alphaPending).hasSize(2);
        assertThat(allPages(new HistoryFilter(Set.of(), new TargetFilter.OnlyTarget(new TargetId("alpha"))), 3)).hasSize(4);
    }

    @Test
    @DisplayName("RC-A-029: the end cursor is not a position; asking from it is refused as an invalid cursor")
    void theEndCursorIsNotAPosition() {
        assertThatThrownBy(() -> history.page(new HistoryFilter(Set.of(), new TargetFilter.AnyTarget()), new HistoryCursor.End(), 10))
            .isInstanceOfSatisfying(RequestRefusedException.class, e -> assertThat(e.code()).isEqualTo(HistoryErrorCode.HISTORY_INVALID_CURSOR));
    }

    @Test
    @DisplayName("the metadata of one operation is read by its id; an unknown id answers nothing")
    void publicMetadataByIdOrNothing() {
        Operation accepted = fx.accept(journal, fx.command("meta", "alpha", "echo", new ConflictKey.None())).operation();
        assertThat(history.publicMetadata(accepted.id()).map(Operation::snapshot)).contains(accepted.snapshot());
        assertThat(history.publicMetadata(new OperationId("f".repeat(48)))).isEmpty();
    }

    @Test
    @DisplayName("S4-AT-05: a corrupted attachment fails strict journal.find but read-only history masks only the damaged body per row")
    void historyMasksUnreadableAttachmentPerRow() throws Exception {
        Operation healthy = fx.accept(journal, fx.command("healthy", "alpha", "echo healthy", new ConflictKey.None())).operation();
        Operation damaged = fx.accept(journal, fx.command("damaged", "alpha", "echo secret", new ConflictKey.None())).operation();
        String cmdAttId = jdbc.queryForObject("SELECT command_text_attachment_id FROM operation_command WHERE operation_id = ?", String.class, damaged.id().value());
        Path blob = attachmentFiles.pathOf(new AttachmentId(cmdAttId));
        byte[] bytes = Files.readAllBytes(blob);
        bytes[bytes.length - 1] = (byte) (bytes[bytes.length - 1] ^ 0x5A);
        Files.write(blob, bytes);

        assertThatThrownBy(() -> journal.find(damaged.id())).isInstanceOf(AttachmentUnreadableException.class);

        HistoryPage page = history.page(new HistoryFilter(Set.of(), new TargetFilter.AnyTarget()), new HistoryCursor.FirstPage(), 10);
        assertThat(page.operations()).hasSize(2);
        Operation damagedView = page.operations().stream().filter(op -> op.id().equals(damaged.id())).findFirst().orElseThrow();
        Operation healthyView = page.operations().stream().filter(op -> op.id().equals(healthy.id())).findFirst().orElseThrow();
        assertThat(((CommandInput) damagedView.input()).command().value()).isEqualTo(OperationStorage.MASKED_BODY);
        assertThat(((CommandInput) damagedView.input()).callerContext().value()).isEqualTo("caller");
        assertThat(((CommandInput) healthyView.input()).command().value()).isEqualTo("echo healthy");
        assertThat(((CommandInput) history.publicMetadata(damaged.id()).orElseThrow().input()).command().value()).isEqualTo(OperationStorage.MASKED_BODY);
    }
}
