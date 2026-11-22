package technology.tabula.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;

/**
 * Configuration of an optional two-PDF comparison: the composite primary key
 * column names (resolved against every table's first header row) and the
 * optional subset of columns whose values must be compared.
 *
 * Both lists are given by the column header names as they appear in the first
 * row of each extracted table. When no compare columns are configured, every
 * non-key column is compared.
 */
public class DiffConfig {

    public static final String DIFF_OPTION = "diff";
    public static final String KEYS_OPTION = "keys";
    public static final String COMPARE_FIELDS_OPTION = "compare-fields";

    private final List<String> keyColumns;
    private final List<String> compareColumns;

    public DiffConfig(List<String> keyColumns, List<String> compareColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("At least one key column is required in diff mode");
        }
        this.keyColumns = Collections.unmodifiableList(new ArrayList<>(keyColumns));
        this.compareColumns = compareColumns == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(compareColumns));
    }

    /**
     * Builds a {@link DiffConfig} from the {@code --keys} / {@code --compare-fields}
     * command line options. Column names are separated by commas.
     */
    public static DiffConfig fromCommandLine(CommandLine line) throws ParseException {
        if (!line.hasOption(KEYS_OPTION)) {
            throw new ParseException("Diff mode requires --keys <COLUMN_NAMES>");
        }
        List<String> keys = parseColumnNameList(line.getOptionValue(KEYS_OPTION));
        if (keys.isEmpty()) {
            throw new ParseException("Diff mode requires at least one key column in --keys");
        }
        List<String> compare = line.hasOption(COMPARE_FIELDS_OPTION)
                ? parseColumnNameList(line.getOptionValue(COMPARE_FIELDS_OPTION))
                : Collections.<String>emptyList();
        if (line.hasOption(COMPARE_FIELDS_OPTION) && compare.isEmpty()) {
            throw new ParseException("--compare-fields must list at least one column");
        }
        return new DiffConfig(keys, compare);
    }

    public static List<String> parseColumnNameList(String optionValue) {
        List<String> names = new ArrayList<>();
        if (optionValue == null) {
            return names;
        }
        for (String part : optionValue.split(",", -1)) {
            String name = part.trim();
            if (!name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    public List<String> getKeyColumns() {
        return keyColumns;
    }

    /**
     * @return the explicitly configured compare columns, or an empty list when
     *         every non-key column must be compared.
     */
    public List<String> getCompareColumns() {
        return compareColumns;
    }
}
