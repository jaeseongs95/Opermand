package io.github.jaeseongs95.portal.app.file;

import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;

/**
 * Where the use case that copies or moves a staged file hands the upload it made: the intake of an operation into the approval flow (the same intake every other
 * request goes through, so the owner's approval, the policy and the queue apply to it). The implementation belongs to the request admission of the execution side
 * (stage 3 implementer C); the file use cases only name what they need (dependency inversion), so they can be tried with a plain fake.
 *
 * <p>Taking the same request key with the same contract again is the same operation: the answer is the id of the operation that exists. A refusal is a
 * {@code RequestRefusedException} (for example {@code OPERATION_KEY_CONFLICT} when the same key meets another contract, or a full queue).
 */
public interface UploadAdmission {

    /**
     * Accepts the upload operation and answers its id.
     *
     * @throws io.github.jaeseongs95.portal.domain.error.RequestRefusedException the request was refused, with the code of the limit or the conflict
     * @throws io.github.jaeseongs95.portal.domain.error.DispatchRefusedException the audit of the new operation did not become durable (DECISION_AUDIT, AUDIT_FAILED); nothing is stored
     */
    OperationId admit(NewOperation operation);
}
