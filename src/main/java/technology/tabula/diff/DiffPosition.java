package technology.tabula.diff;

/**
 * Location of a record inside one of the compared PDFs: the source file name,
 * the 1-based PDF page number and the 1-based row number within the extracted
 * table (the header row is row 1).
 */
public class DiffPosition {

    private final String fileName;
    private final int pageNumber;
    private final int rowNumber;

    public DiffPosition(String fileName, int pageNumber, int rowNumber) {
        this.fileName = fileName;
        this.pageNumber = pageNumber;
        this.rowNumber = rowNumber;
    }

    public String getFileName() {
        return fileName;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public int getRowNumber() {
        return rowNumber;
    }
}
