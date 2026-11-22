package technology.tabula.diff;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Test;

import technology.tabula.Cell;
import technology.tabula.Page;
import technology.tabula.Table;
import technology.tabula.TextChunk;
import technology.tabula.TextElement;
import technology.tabula.extractors.ExtractionAlgorithm;

public class TestTableDiffer {

    private static final ExtractionAlgorithm ALGORITHM = new ExtractionAlgorithm() {
        @Override
        public List<? extends Table> extract(Page page) {
            return new ArrayList<>();
        }

        @Override
        public String toString() {
            return "test";
        }
    };

    private static Cell cell(String text, int row, int col) {
        Cell cell = new Cell(row * 12f, col * 60f, 50f, 10f);
        if (!text.isEmpty()) {
            // A CR in the payload splits it into two text chunks placed on two
            // visual lines, mirroring a cell wrapped across lines in a PDF.
            String[] lines = text.split("\r", -1);
            List<TextChunk> chunks = new ArrayList<>(lines.length);
            for (int line = 0; line < lines.length; line++) {
                TextElement element = new TextElement(row * 12f + line * 12f, col * 60f, 10, 10,
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10, lines[line], 2);
                chunks.add(new TextChunk(element));
            }
            cell.setTextElements(chunks);
        }
        return cell;
    }

    private static Table table(int pageNumber, String[][] rows) {
        Table table = new Table(ALGORITHM);
        table.setPageNumber(pageNumber);
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length; c++) {
                table.add(cell(rows[r][c], r, c), r, c);
            }
        }
        return table;
    }

    private static DiffConfig config(String keys, String compare) {
        return new DiffConfig(DiffConfig.parseColumnNameList(keys),
                DiffConfig.parseColumnNameList(compare));
    }

    private static Table inventoryTable(int page, String[] extraRows) {
        String[][] rows = new String[extraRows.length + 1][];
        rows[0] = new String[]{"sku", "warehouse", "qty", "price"};
        for (int i = 0; i < extraRows.length; i++) {
            rows[i + 1] = extraRows[i].split("\\|", -1);
        }
        return table(page, rows);
    }

    @Test
    public void testAddedDeletedModifiedAndUnchanged() {
        Table oldTable = inventoryTable(1, new String[]{
                "A1|WH1|10|5.00",
                "A2|WH1|20|7.00",
                "A3|WH2|30|9.00"
        });
        Table newTable = inventoryTable(1, new String[]{
                "A1|WH1|10|5.50",   // price changed
                "A3|WH2|30|9.00",   // unchanged
                "A4|WH3|40|1.00"    // added
        });
        // A2 was deleted

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertFalse(result.hasErrors());
        assertEquals(3, result.getEntries().size());

        // Sorted by composite key: A1, A2, A4
        DiffEntry first = result.getEntries().get(0);
        assertEquals(DiffEntry.ChangeType.MODIFIED, first.getChangeType());
        assertEquals("A1", first.getKey().get("sku"));
        assertEquals("WH1", first.getKey().get("warehouse"));
        assertEquals(1, first.getChanges().size());
        assertArrayEquals(new String[]{"5.00", "5.50"}, first.getChanges().get("price"));
        assertFalse(first.getChanges().containsKey("qty"));
        assertEquals("old.pdf", first.getOldPosition().getFileName());
        assertEquals(1, first.getOldPosition().getPageNumber());
        assertEquals(2, first.getOldPosition().getRowNumber());
        assertEquals("new.pdf", first.getNewPosition().getFileName());
        assertEquals(2, first.getNewPosition().getRowNumber());

        DiffEntry second = result.getEntries().get(1);
        assertEquals(DiffEntry.ChangeType.DELETED, second.getChangeType());
        assertEquals("A2", second.getKey().get("sku"));
        assertEquals("20", second.getData().get("qty"));
        assertEquals("7.00", second.getData().get("price"));
        assertNull(second.getNewPosition());
        assertNotNull(second.getOldPosition());

        DiffEntry third = result.getEntries().get(2);
        assertEquals(DiffEntry.ChangeType.ADDED, third.getChangeType());
        assertEquals("A4", third.getKey().get("sku"));
        assertEquals("40", third.getData().get("qty"));
        assertEquals("1.00", third.getData().get("price"));
        assertNull(third.getOldPosition());
        assertNotNull(third.getNewPosition());
    }

    @Test
    public void testNoChangesProducesEmptyDelta() {
        String[] data = new String[]{"A1|WH1|10|5.00", "A2|WH1|20|7.00"};
        Table oldTable = inventoryTable(1, data);
        Table newTable = inventoryTable(2, data); // different page number, same content

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertFalse(result.hasErrors());
        assertTrue(result.getEntries().isEmpty());
    }

    @Test
    public void testLineBreaksAndColumnReorderAreNotChanges() {
        Table oldTable = table(1, new String[][]{
                {"sku", "warehouse", "qty", "price"},
                {"A1", "WH1", "in stock", "5.00"}
        });

        // Same values, qty wrapped onto a second line and columns in a different order.
        Table newTable = table(1, new String[][]{
                {"price", "sku", "warehouse", "qty"},
                {"5.00", "A1", "WH1", "in\rstock"}
        });

        DiffResult result = new TableDiffer(config("sku,warehouse", "qty,price"))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertFalse(result.hasErrors());
        assertTrue(result.getEntries().isEmpty());
    }

    @Test
    public void testKeysAreTrimmedAndUniquenessSpansPages() {
        Table page1 = inventoryTable(1, new String[]{" A1 |WH1|10|5.00"});
        Table page2 = inventoryTable(2, new String[]{"A1|WH1|11|6.00"}); // same trimmed key

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(page1, page2), "new.pdf",
                        Arrays.asList(inventoryTable(1, new String[]{"A1|WH1|10|5.00"})), "old.pdf");

        // The duplicate is reported with both positions, and the row is excluded from the delta.
        assertEquals(1, result.getErrors().size());
        DiffError error = result.getErrors().get(0);
        assertEquals(DiffError.Type.DUPLICATE_KEY, error.getType());
        assertEquals("new.pdf", error.getFileName());
        assertEquals(2, error.getPageNumber());
        assertEquals(2, error.getRowNumber());
        assertTrue(error.getDetail().contains("page 1 row 2"));
        assertTrue(result.getEntries().isEmpty());
    }

    @Test
    public void testEmptyKeyComponentIsReportedWithPosition() {
        Table oldTable = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table newTable = inventoryTable(1, new String[]{"|WH1|10|5.00"});

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertEquals(1, result.getErrors().size());
        DiffError error = result.getErrors().get(0);
        assertEquals(DiffError.Type.EMPTY_KEY, error.getType());
        assertEquals("new.pdf", error.getFileName());
        assertEquals(1, error.getPageNumber());
        assertEquals(2, error.getRowNumber());
        assertEquals("sku", error.getColumn());
        // The invalid row cannot participate, so the old record looks deleted.
        assertEquals(1, result.getEntries().size());
        assertEquals(DiffEntry.ChangeType.DELETED, result.getEntries().get(0).getChangeType());
    }

    @Test
    public void testMissingFieldIsReportedAndTableSkipped() {
        Table oldTable = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table newTable = table(1, new String[][]{
                {"sku", "qty", "price"},
                {"A1", "10", "5.50"}
        });

        DiffResult result = new TableDiffer(config("sku,warehouse", "qty,price"))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertEquals(1, result.getErrors().size());
        DiffError error = result.getErrors().get(0);
        assertEquals(DiffError.Type.MISSING_FIELD, error.getType());
        assertEquals("warehouse", error.getColumn());
        assertEquals("new.pdf", error.getFileName());
        assertEquals(1, error.getPageNumber());
        assertEquals(1, error.getRowNumber()); // header row
        // Skipping the new table makes the old record appear deleted.
        assertEquals(1, result.getEntries().size());
        assertEquals(DiffEntry.ChangeType.DELETED, result.getEntries().get(0).getChangeType());
    }

    @Test
    public void testOnlyConfiguredCompareFieldsAreObserved() {
        Table oldTable = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table newTable = inventoryTable(1, new String[]{"A1|WH1|99|5.00"}); // qty changed, price same

        DiffResult result = new TableDiffer(config("sku,warehouse", "price"))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertFalse(result.hasErrors());
        assertTrue(result.getEntries().isEmpty());
    }

    @Test
    public void testStableSortingByCompositeKey() {
        Table oldTable = inventoryTable(1, new String[0]);
        Table newTable = inventoryTable(1, new String[]{
                "B1|WH1|1|1",
                "A1|WH2|2|2",
                "A1|WH1|3|3"
        });

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertFalse(result.hasErrors());
        assertEquals(3, result.getEntries().size());
        assertEquals("A1", result.getEntries().get(0).getKey().get("sku"));
        assertEquals("WH1", result.getEntries().get(0).getKey().get("warehouse"));
        assertEquals("A1", result.getEntries().get(1).getKey().get("sku"));
        assertEquals("WH2", result.getEntries().get(1).getKey().get("warehouse"));
        assertEquals("B1", result.getEntries().get(2).getKey().get("sku"));
    }

    @Test
    public void testContinuationHeaderOnLaterPageIsSkipped() {
        Table page1 = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table page2 = inventoryTable(2, new String[]{"A2|WH1|20|7.00"});

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(page1, page2), "old.pdf",
                        Arrays.asList(inventoryTable(1, new String[]{"A1|WH1|10|5.00"})), "new.pdf");

        assertFalse(result.hasErrors());
        assertEquals(1, result.getEntries().size());
        assertEquals(DiffEntry.ChangeType.DELETED, result.getEntries().get(0).getChangeType());
        assertEquals("A2", result.getEntries().get(0).getKey().get("sku"));
        assertEquals(2, result.getEntries().get(0).getOldPosition().getPageNumber());
        assertEquals(2, result.getEntries().get(0).getOldPosition().getRowNumber());
    }

    @Test
    public void testRepeatedHeaderInsideOneTableIsNotARecord() {
        Table oldTable = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table newTable = table(1, new String[][]{
                {"sku", "warehouse", "qty", "price"},
                {"A1", "WH1", "10", "5.50"},
                {"sku", "warehouse", "qty", "price"}, // header repeated mid-table
                {"A2", "WH1", "20", "7.00"}
        });

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertFalse(result.hasErrors());
        assertEquals(2, result.getEntries().size());
        assertEquals("A1", result.getEntries().get(0).getKey().get("sku"));
        assertEquals(DiffEntry.ChangeType.MODIFIED, result.getEntries().get(0).getChangeType());
        assertEquals("A2", result.getEntries().get(1).getKey().get("sku"));
        assertEquals(DiffEntry.ChangeType.ADDED, result.getEntries().get(1).getChangeType());
        // A2 is the 4th physical row (1-based, header is row 1).
        assertEquals(4, result.getEntries().get(1).getNewPosition().getRowNumber());
    }

    @Test
    public void testHeaderlessContinuationTableReusesSchema() {
        Table oldPage1 = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table newPage1 = inventoryTable(1, new String[]{"A1|WH1|10|5.50"});
        // Second page's table starts directly with data: no header row.
        Table newPage2 = table(2, new String[][]{
                {"A2", "WH1", "20", "7.00"}
        });

        DiffResult result = new TableDiffer(config("sku,warehouse", ""))
                .diff(Arrays.asList(oldPage1), "old.pdf",
                        Arrays.asList(newPage1, newPage2), "new.pdf");

        assertFalse(result.hasErrors());
        assertEquals(2, result.getEntries().size());
        assertEquals("A1", result.getEntries().get(0).getKey().get("sku"));
        assertEquals("A2", result.getEntries().get(1).getKey().get("sku"));
        DiffPosition addedAt = result.getEntries().get(1).getNewPosition();
        assertEquals(2, addedAt.getPageNumber());
        assertEquals(1, addedAt.getRowNumber()); // first physical row of the continuation table
    }

    @Test
    public void testCompareFieldsExplicitlyExcludeKeyColumns() {
        // Key columns listed again among compare fields must not be compared twice.
        Table oldTable = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table newTable = inventoryTable(1, new String[]{"A1|WH1|10|5.50"});

        DiffResult result = new TableDiffer(config("sku,warehouse", "sku,warehouse,price"))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        assertFalse(result.hasErrors());
        assertEquals(1, result.getEntries().size());
        DiffEntry entry = result.getEntries().get(0);
        assertEquals(DiffEntry.ChangeType.MODIFIED, entry.getChangeType());
        assertArrayEquals(new String[]{"5.00", "5.50"}, entry.getChanges().get("price"));
        assertFalse(entry.getChanges().containsKey("sku"));
    }

    @Test
    public void testJsonSerializationShape() throws Exception {        Table oldTable = inventoryTable(1, new String[]{"A1|WH1|10|5.00"});
        Table newTable = inventoryTable(1, new String[]{"A1|WH1|10|5.50"});

        DiffResult result = new TableDiffer(config("sku,warehouse", "price"))
                .diff(Arrays.asList(oldTable), "old.pdf", Arrays.asList(newTable), "new.pdf");

        StringBuilder out = new StringBuilder();
        new DiffJsonWriter().writeDelta(out, result);
        String json = out.toString().trim();

        assertTrue(json.startsWith("["));
        assertTrue(json.contains("\"change_type\":\"modified\""));
        assertTrue(json.contains("\"key\":{\"sku\":\"A1\",\"warehouse\":\"WH1\"}"));
        assertTrue(json.contains("\"changes\":{\"price\":[\"5.00\",\"5.50\"]}"));
        assertTrue(json.contains("\"old_position\":{\"file_name\":\"old.pdf\",\"page_number\":1,\"row_number\":2}"));
        assertTrue(json.contains("\"new_position\":{\"file_name\":\"new.pdf\",\"page_number\":1,\"row_number\":2}"));
        assertFalse(json.contains("\"data\""));

        StringBuilder empty = new StringBuilder();
        new DiffJsonWriter().writeDelta(empty,
                new DiffResult(new ArrayList<DiffEntry>(), new ArrayList<DiffError>()));
        assertEquals("[]", empty.toString().trim());
    }
}
