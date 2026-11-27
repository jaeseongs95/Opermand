package io.github.jaeseongs95.opermand.app.storage;

/**
 * The folders of the file stores that hold bytes the owner must keep private: artifacts (staged and transferred files), command outputs, encrypted operation-body
 * attachments, and the attachment keyring.
 */
public enum StorageArea {
    ARTIFACTS, OUTPUTS, ATTACHMENTS, KEYRING
}
