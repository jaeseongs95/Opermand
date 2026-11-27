package io.github.jaeseongs95.opermand.migrate.source;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * One row of a 2.x journal as it was stored, nothing interpreted yet: where it came from, its id, the request key column (null when the table has none or the row has
 * none), and the JSON body as text. The body text is the UTF-8 of the stored value decoded in one piece and strictly, so that a character is never split or replaced; a row
 * whose id or body cannot be read that way carries a {@code problem} instead (and a null body), and the planner reports it rather than guessing.
 *
 * @param source the file the row is in, such as {@code operations.sqlite}
 * @param id the id column, or null when it is missing
 * @param requestKey the request key column, or null
 * @param body the body, or null when {@code problem} is set
 * @param problem why the row could not be read, or null
 */
public record LegacyRow(String source, String id, String requestKey, String body, String problem) {

    /** Reads the row the result set is on; the columns are, in order, the id, the request key (may be null) and the body. */
    public static LegacyRow read(String source, ResultSet rows) throws SQLException {
        String id = rows.getString(1);
        String requestKey = rows.getString(2);
        byte[] raw = rows.getBytes(3);
        if (id == null || id.isEmpty()) {
            return new LegacyRow(source, null, requestKey, null, "the id column is empty");
        }
        if (raw == null) {
            return new LegacyRow(source, id, requestKey, null, "the body column is empty");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString();
            return new LegacyRow(source, id, requestKey, text, null);
        } catch (CharacterCodingException e) {
            return new LegacyRow(source, id, requestKey, null, "the body is not well-formed UTF-8");
        }
    }

    /** True when the body was read. */
    public boolean readable() {
        return problem == null;
    }

    /** The place of the row in a message: file and id. */
    public String reference() {
        return source + "#" + (id == null ? "(no id)" : id);
    }
}
