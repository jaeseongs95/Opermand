package io.github.jaeseongs95.opermand.app.storage;

/**
 * The guard of the file stores (2.x {@code PrivateStorage} analogue, defect-free rule of RC-B-004): asks the storage adapter whether the artifact and output folders are
 * folders the stores can use. A folder that is not there yet is created private to its owner; one that exists is used as it is, whoever else may use it (decision D-0117:
 * the owner trusts the machine). The folders are refused when the path is not a folder, or when the file store cannot make a new folder private to its owner. The adapter
 * does the file-system work (a rule per kind of file store, chosen by what the file store offers), so the use cases of this module know nothing of POSIX permission bits or
 * access control lists. The answer is a value, not an exception: a check that fails is an answer.
 */
public interface StorageProtection {

    /** Checks every area; the first one that cannot be used is the answer. Does file I/O, so it is not called inside a transaction. */
    ProtectionCheck check();
}
