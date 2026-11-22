package technology.tabula.diff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of a two-PDF comparison: the sorted delta entries plus every
 * data-quality error encountered while reading the two files.
 */
public class DiffResult {

    private final List<DiffEntry> entries;
    private final List<DiffError> errors;

    public DiffResult(List<DiffEntry> entries, List<DiffError> errors) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    /**
     * @return delta entries stably sorted by composite key; an empty list when
     *         nothing changed.
     */
    public List<DiffEntry> getEntries() {
        return entries;
    }

    public List<DiffError> getErrors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
