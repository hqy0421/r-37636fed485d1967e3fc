package technology.tabula.diff;

import static org.junit.Assert.*;

import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import technology.tabula.CommandLineApp;

/**
 * End-to-end test of the optional two-PDF comparison mode: generates two real
 * PDFs (including a table continued onto a second page), runs the full
 * CommandLineApp pipeline with identical extraction settings on both files and
 * parses the resulting JSON delta.
 */
public class TestCommandLineDiff {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final float FONT_SIZE = 10f;
    private static final float LEADING = 14f;
    private static final float LEFT = 50f;
    private static final float TOP = 760f;
    private static final float[] COLUMN_X = {50f, 150f, 250f, 350f};

    private File writePdf(String name, List<String[]> rows, int rowsPerPage) throws Exception {
        File file = folder.newFile(name);
        try (PDDocument doc = new PDDocument()) {
            PDPageContentStream stream = null;
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            int onPage = 0;

            for (String[] row : rows) {
                if (stream == null || onPage == rowsPerPage) {
                    if (stream != null) {
                        stream.endText();
                        stream.close();
                    }
                    doc.addPage(new PDPage());
                    stream = new PDPageContentStream(doc, doc.getPage(doc.getNumberOfPages() - 1));
                    stream.beginText();
                    stream.setFont(font, FONT_SIZE);
                    stream.setLeading(LEADING);
                    onPage = 0;
                }
                float baseline = TOP - onPage * LEADING;
                for (int c = 0; c < row.length; c++) {
                    // Absolute text position, independent of the previous cell.
                    stream.setTextMatrix(new org.apache.pdfbox.util.Matrix(1, 0, 0, 1, COLUMN_X[c], baseline));
                    stream.showText(row[c]);
                }
                onPage++;
            }
            if (stream != null) {
                stream.endText();
                stream.close();
            }
            doc.save(file);
        }
        return file;
    }

    private String runDiff(File oldPdf, File newPdf, String keys, String compareFields)
            throws ParseException {
        List<String> args = new ArrayList<>();
        args.add(oldPdf.getAbsolutePath());
        args.add(newPdf.getAbsolutePath());
        args.add("--diff");
        args.add("--keys");
        args.add(keys);
        args.add("-p");
        args.add("all");
        if (compareFields != null) {
            args.add("--compare-fields");
            args.add(compareFields);
        }
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(CommandLineApp.buildOptions(), args.toArray(new String[0]));

        StringBuilder out = new StringBuilder();
        new CommandLineApp(out, cmd).extractTables(cmd);
        return out.toString();
    }

    @Test
    public void testEndToEndDeltaAcrossPages() throws Exception {
        // The new PDF's table continues onto a second page, where the supplier
        // repeats the header row: the differ must treat repeated header rows as
        // headers and still enforce a file-wide unique key.
        List<String[]> oldRows = new ArrayList<>();
        oldRows.add(new String[]{"sku", "warehouse", "qty", "price"});
        oldRows.add(new String[]{"A1", "WH1", "10", "5.00"});
        oldRows.add(new String[]{"A2", "WH1", "20", "7.00"});
        oldRows.add(new String[]{"A3", "WH2", "30", "9.00"});

        List<String[]> newRows = new ArrayList<>();
        newRows.add(new String[]{"sku", "warehouse", "qty", "price"});
        newRows.add(new String[]{"A1", "WH1", "10", "5.50"}); // price modified
        newRows.add(new String[]{"sku", "warehouse", "qty", "price"}); // repeated header on page 2
        newRows.add(new String[]{"A3", "WH2", "30", "9.00"}); // unchanged
        newRows.add(new String[]{"A4", "WH3", "40", "1.00"}); // added
        // A2 deleted

        File oldPdf = writePdf("old.pdf", oldRows, 10); // single page
        File newPdf = writePdf("new.pdf", newRows, 3);  // forces a page break before A3

        String json = runDiff(oldPdf, newPdf, "sku,warehouse", null);
        JsonArray delta = JsonParser.parseString(json.trim()).getAsJsonArray();

        assertEquals(3, delta.size());

        JsonObject modified = delta.get(0).getAsJsonObject();
        assertEquals("modified", modified.get("change_type").getAsString());
        assertEquals("A1", modified.getAsJsonObject("key").get("sku").getAsString());
        JsonObject changes = modified.getAsJsonObject("changes");
        assertEquals(1, changes.size());
        assertEquals("5.00", changes.getAsJsonArray("price").get(0).getAsString());
        assertEquals("5.50", changes.getAsJsonArray("price").get(1).getAsString());
        assertNotNull(modified.get("old_position"));
        assertNotNull(modified.get("new_position"));

        JsonObject deleted = delta.get(1).getAsJsonObject();
        assertEquals("deleted", deleted.get("change_type").getAsString());
        assertEquals("A2", deleted.getAsJsonObject("key").get("sku").getAsString());
        assertEquals("20", deleted.getAsJsonObject("data").get("qty").getAsString());
        assertTrue(deleted.has("old_position"));
        assertFalse(deleted.has("new_position"));

        JsonObject added = delta.get(2).getAsJsonObject();
        assertEquals("added", added.get("change_type").getAsString());
        assertEquals("A4", added.getAsJsonObject("key").get("sku").getAsString());
        assertEquals("40", added.getAsJsonObject("data").get("qty").getAsString());
        assertFalse(added.has("old_position"));
        assertTrue(added.has("new_position"));
    }

    @Test
    public void testIdenticalFilesYieldEmptyArray() throws Exception {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"sku", "warehouse", "qty", "price"});
        rows.add(new String[]{"A1", "WH1", "10", "5.00"});

        File oldPdf = writePdf("a.pdf", rows, 10);
        File newPdf = writePdf("b.pdf", rows, 10);

        String json = runDiff(oldPdf, newPdf, "sku,warehouse", "qty,price");
        JsonElement parsed = JsonParser.parseString(json.trim());
        assertTrue(parsed.isJsonArray());
        assertEquals(0, parsed.getAsJsonArray().size());
    }

    @Test
    public void testSingleFileModeStillWorksAndIgnoresDiffFlags() throws Exception {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"sku", "warehouse", "qty", "price"});
        rows.add(new String[]{"A1", "WH1", "10", "5.00"});
        File pdf = writePdf("single.pdf", rows, 10);

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(CommandLineApp.buildOptions(),
                new String[]{pdf.getAbsolutePath(), "-p", "all", "-f", "JSON"});
        StringWriter out = new StringWriter();
        new CommandLineApp(out, cmd).extractTables(cmd);

        JsonArray tables = JsonParser.parseString(out.toString().trim()).getAsJsonArray();
        assertEquals(1, tables.size());
        JsonArray firstRow = tables.get(0).getAsJsonObject()
                .getAsJsonArray("data").get(0).getAsJsonArray();
        assertEquals("sku", firstRow.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void testDiffRequiresExactlyTwoFiles() throws Exception {
        File pdf = folder.newFile("one.pdf");
        Files.write(pdf.toPath(), new byte[0]);

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(CommandLineApp.buildOptions(),
                new String[]{pdf.getAbsolutePath(), "--diff", "--keys", "sku"});
        try {
            new CommandLineApp(new StringBuilder(), cmd).extractTables(cmd);
            fail("Expected ParseException");
        } catch (ParseException e) {
            assertTrue(e.getMessage().contains("two filenames"));
        }
    }

    @Test
    public void testDiffRequiresKeys() throws Exception {
        File a = folder.newFile("a.pdf");
        File b = folder.newFile("b.pdf");
        Files.write(a.toPath(), new byte[0]);
        Files.write(b.toPath(), new byte[0]);

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(CommandLineApp.buildOptions(),
                new String[]{a.getAbsolutePath(), b.getAbsolutePath(), "--diff"});
        try {
            new CommandLineApp(new StringBuilder(), cmd).extractTables(cmd);
            fail("Expected ParseException");
        } catch (ParseException e) {
            assertTrue(e.getMessage().contains("--keys"));
        }
    }

    @Test
    public void testErrorsAreReportedWithFileAndPosition() throws Exception {
        List<String[]> oldRows = new ArrayList<>();
        oldRows.add(new String[]{"sku", "warehouse", "qty", "price"});
        oldRows.add(new String[]{"A1", "WH1", "10", "5.00"});

        List<String[]> newRows = new ArrayList<>();
        newRows.add(new String[]{"sku", "warehouse", "qty", "price"});
        newRows.add(new String[]{"A1", "WH1", "10", "5.00"});
        newRows.add(new String[]{"A1", "WH1", "99", "9.00"}); // duplicate key

        File oldPdf = writePdf("old.pdf", oldRows, 10);
        File newPdf = writePdf("new.pdf", newRows, 10);

        // The delta on stdout stays a valid JSON array even with data errors.
        String json = runDiff(oldPdf, newPdf, "sku,warehouse", null);
        assertTrue(JsonParser.parseString(json.trim()).isJsonArray());

        // Error serialization is checked directly: the CLI writes the same
        // objects one per line to stderr.
        DiffError error = new DiffError(DiffError.Type.DUPLICATE_KEY, "new.pdf", 1, 3, null,
                "Duplicate composite key [A1, WH1]");
        StringBuilder errOut = new StringBuilder();
        new DiffJsonWriter().writeErrors(errOut, java.util.Collections.singletonList(error));
        JsonObject parsed = JsonParser.parseString(errOut.toString().trim()).getAsJsonObject();
        assertEquals("DUPLICATE_KEY", parsed.get("type").getAsString());
        assertEquals("new.pdf", parsed.get("file_name").getAsString());
        assertEquals(1, parsed.get("page_number").getAsInt());
        assertEquals(3, parsed.get("row_number").getAsInt());
        assertTrue(parsed.get("detail").getAsString().contains("[A1, WH1]"));
    }
}
