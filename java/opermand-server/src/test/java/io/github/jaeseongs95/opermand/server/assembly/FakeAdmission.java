package io.github.jaeseongs95.opermand.server.assembly;

import java.util.ArrayList;
import java.util.List;

import io.github.jaeseongs95.opermand.app.file.UploadAdmission;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;

/** The intake of uploads in the assembly tests: it remembers what it was given and answers the id it was given, or refuses when the test says the queue is full. */
final class FakeAdmission implements UploadAdmission {

    final List<NewOperation> admitted = new ArrayList<>();
    boolean full;
    void reset() {
        admitted.clear();
        full = false;
    }

    @Override
    public OperationId admit(NewOperation operation, OperationId id) {
        if (full) {
            throw new RequestRefusedException(OperationErrorCode.OPERATION_QUEUE_FULL);
        }
        admitted.add(operation);
        return id;
    }
}
