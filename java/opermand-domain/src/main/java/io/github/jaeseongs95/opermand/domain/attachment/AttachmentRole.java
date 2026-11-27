package io.github.jaeseongs95.opermand.domain.attachment;

/**
 * Which operation body slot an encrypted attachment belongs to (D-0092, stage 4 design B-15). Bound into the AEAD associated data (AAD) so an attachment file cannot be
 * swapped across roles without failing decryption.
 */
public enum AttachmentRole {
    COMMAND_TEXT,
    CALLER_CONTEXT,
    DISPLAY_CONTEXT,
    LAUNCHER_COMMAND,
    ARGV_JSON,
    RESULT_TEXTS
}
