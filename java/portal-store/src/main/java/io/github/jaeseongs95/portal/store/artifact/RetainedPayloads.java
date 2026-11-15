package io.github.jaeseongs95.portal.store.artifact;

/**
 * The bytes that stay on the disk of the artifact store without a row: payloads the start-up check moved to quarantine, and that nothing deletes. They are counted apart
 * from the staging budget: they are shown to the owner ({@code ArtifactBudget#retainedBytes()}) and never decide whether a new transfer fits, because nothing frees them
 * by itself and a budget that counted them would refuse every transfer for good once they grew (defect D-0012; this replaces design 13.6, RC-A-169, RC-A-170). They still
 * use the disk, which the disk check of a reservation sees through the usable space. The read is file I/O, so the caller makes it outside a transaction (Principles 4.4).
 */
public interface RetainedPayloads {

    /** The total size of the files in quarantine; zero when there is none. */
    long retainedBytes();
}
