package technology.tabula.diff;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One record of the JSON delta produced by a two-PDF comparison.
 *
 * <ul>
 *   <li>{@code added}: present in the new file only; all compared columns are
 *       listed in {@code data}.</li>
 *   <li>{@code deleted}: present in the old file only; all compared columns are
 *       listed in {@code data}.</li>
 *   <li>{@code modified}: present in both files; {@code changes} only contains
 *       the compare columns whose value actually changed, with old and new
 *       values.</li>
 * </ul>
 *
 * The composite key is always carried in declaration order. Position
 * information records where the record exists in each version; a position is
 * {@code null} on the side where the record does not exist.
 */
public class DiffEntry {

    public enum ChangeType {
        ADDED,
        DELETED,
        MODIFIED
    }

    private final ChangeType changeType;
    private final Map<String, String> key;
    private final Map<String, String> data;
    private final Map<String, String[]> changes;
    private final DiffPosition oldPosition;
    private final DiffPosition newPosition;

    public DiffEntry(ChangeType changeType,
                     Map<String, String> key,
                     Map<String, String> data,
                     Map<String, String[]> changes,
                     DiffPosition oldPosition,
                     DiffPosition newPosition) {
        this.changeType = changeType;
        this.key = key == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(key);
        this.data = data == null ? new LinkedHashMap<String, String>() : new LinkedHashMap<>(data);
        this.changes = changes == null ? new LinkedHashMap<String, String[]>() : new LinkedHashMap<>(changes);
        this.oldPosition = oldPosition;
        this.newPosition = newPosition;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public Map<String, String> getKey() {
        return key;
    }

    public Map<String, String> getData() {
        return data;
    }

    public Map<String, String[]> getChanges() {
        return changes;
    }

    public DiffPosition getOldPosition() {
        return oldPosition;
    }

    public DiffPosition getNewPosition() {
        return newPosition;
    }

    /**
     * Stable ordering of delta entries: lexicographic comparison of the trimmed
     * composite key components, in the configured key order.
     */
    public static int compareByKey(DiffEntry a, DiffEntry b, List<String> keyColumns) {
        for (String keyColumn : keyColumns) {
            String av = a.key.get(keyColumn);
            String bv = b.key.get(keyColumn);
            int cmp = av.compareTo(bv);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }
}
