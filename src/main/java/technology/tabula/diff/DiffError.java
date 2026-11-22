package technology.tabula.diff;

/**
 * A data-quality problem found while reading one side of a two-PDF comparison.
 * Every error carries the originating file name and the page/row position of
 * the offending table row (or table header), so a data warehouse can locate
 * the problem in the source document.
 */
public class DiffError {

    public enum Type {
        /** A configured key or compare column is absent from the table header. */
        MISSING_FIELD,
        /** A key component of a data row is blank after trimming. */
        EMPTY_KEY,
        /** The same composite key occurs more than once in the same file. */
        DUPLICATE_KEY
    }

    private final Type type;
    private final String fileName;
    private final int pageNumber;
    private final int rowNumber;
    private final String column;
    private final String detail;

    public DiffError(Type type, String fileName, int pageNumber, int rowNumber, String column, String detail) {
        this.type = type;
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.rowNumber = rowNumber;
        this.column = column;
        this.detail = detail;
    }

    public Type getType() {
        return type;
    }

    public String getFileName() {
        return fileName;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * @return 1-based row number within the extracted table; the header row is
     *         row 1. {@code 0} means the error is attached to the table itself
     *         rather than to a row (e.g. a missing header column).
     */
    public int getRowNumber() {
        return rowNumber;
    }

    public String getColumn() {
        return column;
    }

    public String getDetail() {
        return detail;
    }
}
