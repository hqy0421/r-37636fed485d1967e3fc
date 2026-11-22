package technology.tabula.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;

/**
 * Compares the tables extracted from two versions of the same PDF document and
 * produces a keyed JSON-serializable delta.
 *
 * <p>Each extracted table is read independently: its first row is treated as
 * the header, and the configured composite key / compare column names are
 * resolved against that header. A table that lacks a configured column is
 * reported (with file and page/row position) and skipped. Records are keyed by
 * the trimmed composite key, which must be non-empty and unique across every
 * table of a given file, including tables continued on later pages; violations
 * are reported with the offending position.
 *
 * <p>Page breaks, line breaks inside cells and column reordering never produce
 * changes by themselves: rows are matched by key, line breaks are normalized
 * to spaces before comparison and columns are looked up by name.
 */
public class TableDiffer {

    private final DiffConfig config;

    public TableDiffer(DiffConfig config) {
        this.config = config;
    }

    /** A single parsed record, enriched with its position in the source file. */
    private static final class Record {
        final List<String> keyValues;
        final Map<String, String> values;
        final DiffPosition position;

        Record(List<String> keyValues, Map<String, String> values, DiffPosition position) {
            this.keyValues = keyValues;
            this.values = values;
            this.position = position;
        }
    }

    /** Result of reading one side of the comparison. */
    static final class ParsedSide {
        final TreeMap<List<String>, Record> records = new TreeMap<>(new ListLexicographicComparator());
        final List<DiffError> errors = new ArrayList<>();
    }

    public DiffResult diff(List<Table> oldTables, String oldFileName,
                           List<Table> newTables, String newFileName) {
        ParsedSide oldSide = parse(oldTables, oldFileName);
        ParsedSide newSide = parse(newTables, newFileName);

        List<DiffError> errors = new ArrayList<>(oldSide.errors);
        errors.addAll(newSide.errors);

        List<DiffEntry> entries = new ArrayList<>();

        for (Map.Entry<List<String>, Record> entry : newSide.records.entrySet()) {
            List<String> key = entry.getKey();
            Record newRecord = entry.getValue();
            Record oldRecord = oldSide.records.get(key);

            if (oldRecord == null) {
                entries.add(new DiffEntry(
                        DiffEntry.ChangeType.ADDED,
                        keyMap(key),
                        new LinkedHashMap<>(newRecord.values),
                        null,
                        null,
                        newRecord.position));
                continue;
            }

            Map<String, String[]> changes = new LinkedHashMap<>();
            for (Map.Entry<String, String> field : newRecord.values.entrySet()) {
                String column = field.getKey();
                String oldValue = oldRecord.values.get(column);
                String newValue = field.getValue();
                if (oldValue == null || !oldValue.equals(newValue)) {
                    changes.put(column, new String[]{oldValue == null ? "" : oldValue, newValue});
                }
            }
            if (!changes.isEmpty()) {
                entries.add(new DiffEntry(
                        DiffEntry.ChangeType.MODIFIED,
                        keyMap(key),
                        null,
                        changes,
                        oldRecord.position,
                        newRecord.position));
            }
        }

        for (Map.Entry<List<String>, Record> entry : oldSide.records.entrySet()) {
            if (!newSide.records.containsKey(entry.getKey())) {
                Record oldRecord = entry.getValue();
                entries.add(new DiffEntry(
                        DiffEntry.ChangeType.DELETED,
                        keyMap(entry.getKey()),
                        new LinkedHashMap<>(oldRecord.values),
                        null,
                        oldRecord.position,
                        null));
            }
        }

        entries.sort((a, b) -> DiffEntry.compareByKey(a, b, config.getKeyColumns()));
        return new DiffResult(entries, errors);
    }

    private Map<String, String> keyMap(List<String> keyValues) {
        Map<String, String> key = new LinkedHashMap<>();
        List<String> keyColumns = config.getKeyColumns();
        for (int i = 0; i < keyColumns.size(); i++) {
            key.put(keyColumns.get(i), keyValues.get(i));
        }
        return key;
    }

    /** Column schema resolved from a table header, shared by continuation tables. */
    private static final class Schema {
        final List<String> keyColumns;
        final List<Integer> keyIndexes;
        final List<String> compareColumns;
        final List<Integer> compareIndexes;

        Schema(List<String> keyColumns, List<Integer> keyIndexes,
               List<String> compareColumns, List<Integer> compareIndexes) {
            this.keyColumns = keyColumns;
            this.keyIndexes = keyIndexes;
            this.compareColumns = compareColumns;
            this.compareIndexes = compareIndexes;
        }
    }

    private ParsedSide parse(List<Table> tables, String fileName) {
        ParsedSide side = new ParsedSide();
        // The first table that carries a usable header establishes the schema;
        // header-less tables continued on later pages reuse it.
        Schema schema = null;

        for (Table table : tables) {
            List<List<RectangularTextContainer>> rows = table.getRows();
            if (rows.isEmpty()) {
                continue;
            }

            int pageNumber = table.getPageNumber();
            Map<String, Integer> headerIndex = headerIndex(rows.get(0));

            if (schema == null) {
                schema = resolveSchema(headerIndex, side, fileName, pageNumber);
                if (schema == null) {
                    // No usable schema could be established from the first table;
                    // keep looking for a table with a valid header.
                    continue;
                }
            } else if (!hasRequiredHeaders(headerIndex, schema)) {
                // A continuation table whose first row is data rather than a
                // repeated header: reuse the established schema and read every
                // row, starting at row index 0.
                readRows(side, schema, rows, 0, fileName, pageNumber);
                continue;
            }

            // Normal case: row 0 is a header. A repeated header later in the
            // same table is skipped while reading rows.
            readRows(side, schema, rows, 1, fileName, pageNumber);
        }

        return side;
    }

    private Map<String, Integer> headerIndex(List<RectangularTextContainer> headerRow) {
        Map<String, Integer> headerIndex = new LinkedHashMap<>();
        for (int col = 0; col < headerRow.size(); col++) {
            String header = headerRow.get(col).getText().trim();
            if (!header.isEmpty() && !headerIndex.containsKey(header)) {
                headerIndex.put(header, col);
            }
        }
        return headerIndex;
    }

    private boolean hasRequiredHeaders(Map<String, Integer> headerIndex, Schema schema) {
        for (String keyColumn : schema.keyColumns) {
            if (!headerIndex.containsKey(keyColumn)) {
                return false;
            }
        }
        for (String compareColumn : schema.compareColumns) {
            if (!headerIndex.containsKey(compareColumn)) {
                return false;
            }
        }
        return true;
    }

    private Schema resolveSchema(Map<String, Integer> headerIndex, ParsedSide side,
                                 String fileName, int pageNumber) {
        List<Integer> keyIndexes = new ArrayList<>();
        boolean valid = true;
        for (String keyColumn : config.getKeyColumns()) {
            Integer idx = headerIndex.get(keyColumn);
            if (idx == null) {
                side.errors.add(new DiffError(DiffError.Type.MISSING_FIELD, fileName,
                        pageNumber, 1, keyColumn,
                        "Key column '" + keyColumn + "' not found in table header"));
                valid = false;
            } else {
                keyIndexes.add(idx);
            }
        }

        List<String> effectiveCompareColumns = new ArrayList<>(
                config.getCompareColumns().isEmpty()
                        ? new ArrayList<>(headerIndex.keySet())
                        : config.getCompareColumns());
        effectiveCompareColumns.removeAll(config.getKeyColumns());

        List<Integer> compareIndexes = new ArrayList<>();
        for (String compareColumn : effectiveCompareColumns) {
            Integer idx = headerIndex.get(compareColumn);
            if (idx == null) {
                side.errors.add(new DiffError(DiffError.Type.MISSING_FIELD, fileName,
                        pageNumber, 1, compareColumn,
                        "Compare column '" + compareColumn + "' not found in table header"));
                valid = false;
            } else {
                compareIndexes.add(idx);
            }
        }

        return valid
                ? new Schema(config.getKeyColumns(), keyIndexes, effectiveCompareColumns, compareIndexes)
                : null;
    }

    private void readRows(ParsedSide side, Schema schema,
                          List<List<RectangularTextContainer>> rows, int startRow,
                          String fileName, int pageNumber) {
        for (int rowIdx = startRow; rowIdx < rows.size(); rowIdx++) {
            List<RectangularTextContainer> row = rows.get(rowIdx);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNumber = rowIdx + 1; // 1-based, header is row 1.

            // A repeated header row inside a continued table is never a record.
            if (isRepeatedHeader(row, schema)) {
                continue;
            }

            List<String> keyValues = new ArrayList<>(schema.keyIndexes.size());
            boolean emptyKey = false;
            for (int k = 0; k < schema.keyIndexes.size(); k++) {
                String value = cellText(row, schema.keyIndexes.get(k)).trim();
                if (value.isEmpty()) {
                    side.errors.add(new DiffError(DiffError.Type.EMPTY_KEY, fileName,
                            pageNumber, rowNumber, schema.keyColumns.get(k),
                            "Empty key component '" + schema.keyColumns.get(k) + "'"));
                    emptyKey = true;
                }
                keyValues.add(value);
            }
            if (emptyKey) {
                continue;
            }

            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < schema.compareColumns.size(); c++) {
                values.put(schema.compareColumns.get(c),
                        normalizeValue(cellText(row, schema.compareIndexes.get(c))));
            }

            Record previous = side.records.get(keyValues);
            if (previous != null) {
                side.errors.add(new DiffError(DiffError.Type.DUPLICATE_KEY, fileName,
                        pageNumber, rowNumber, null,
                        "Duplicate composite key " + keyValues
                                + ", first seen at page " + previous.position.getPageNumber()
                                + " row " + previous.position.getRowNumber()));
                continue;
            }

            side.records.put(keyValues,
                    new Record(keyValues, values, new DiffPosition(fileName, pageNumber, rowNumber)));
        }
    }

    private boolean isRepeatedHeader(List<RectangularTextContainer> row, Schema schema) {
        for (int k = 0; k < schema.keyColumns.size(); k++) {
            if (!cellText(row, schema.keyIndexes.get(k)).trim().equals(schema.keyColumns.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static String cellText(List<RectangularTextContainer> row, int col) {
        if (col >= row.size()) {
            return "";
        }
        String text = row.get(col).getText();
        return text == null ? "" : text;
    }

    private static boolean isBlankRow(List<RectangularTextContainer> row) {
        for (RectangularTextContainer cell : row) {
            if (cell != null && !cell.getText().trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Line breaks (CR/LF) are normalized to spaces so that wrapping inside a
     * cell is never reported as a change. No other whitespace folding is done.
     */
    static String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
