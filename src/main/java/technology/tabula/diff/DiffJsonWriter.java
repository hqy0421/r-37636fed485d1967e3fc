package technology.tabula.diff;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * Serializes {@link DiffResult}s to the delta JSON format. The delta itself is
 * always a plain JSON array (empty when nothing changed); data-quality errors
 * are emitted one JSON object per line so they can be kept on stderr without
 * corrupting the machine-readable delta on stdout.
 */
public class DiffJsonWriter {

    private final Gson gson;

    public DiffJsonWriter() {
        this.gson = new GsonBuilder()
                .setFieldNamingPolicy(com.google.gson.FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .disableHtmlEscaping()
                .registerTypeAdapter(DiffEntry.class, (JsonSerializer<DiffEntry>) DiffJsonWriter::serializeEntry)
                .registerTypeAdapter(DiffPosition.class, (JsonSerializer<DiffPosition>) DiffJsonWriter::serializePosition)
                .create();
    }

    private static JsonElement serializeEntry(DiffEntry entry, java.lang.reflect.Type type,
                                              JsonSerializationContext context) {
        JsonObject json = new JsonObject();
        json.addProperty("change_type", entry.getChangeType().name().toLowerCase(java.util.Locale.ROOT));
        json.add("key", context.serialize(entry.getKey(), Map.class));
        // Only the field relevant to the change type is emitted: full record for
        // added/deleted, changed-only fields for modified.
        if (entry.getChangeType() == DiffEntry.ChangeType.MODIFIED) {
            json.add("changes", context.serialize(entry.getChanges(), Map.class));
        } else {
            json.add("data", context.serialize(entry.getData(), Map.class));
        }
        if (entry.getOldPosition() != null) {
            json.add("old_position", context.serialize(entry.getOldPosition(), DiffPosition.class));
        }
        if (entry.getNewPosition() != null) {
            json.add("new_position", context.serialize(entry.getNewPosition(), DiffPosition.class));
        }
        return json;
    }

    private static JsonElement serializePosition(DiffPosition position, java.lang.reflect.Type type,
                                                 JsonSerializationContext context) {
        JsonObject json = new JsonObject();
        json.addProperty("file_name", position.getFileName());
        json.addProperty("page_number", position.getPageNumber());
        json.addProperty("row_number", position.getRowNumber());
        return json;
    }

    /** Writes the delta as a JSON array, followed by a newline. */
    public void writeDelta(Appendable out, DiffResult result) throws IOException {
        out.append(gson.toJson(result.getEntries()));
        out.append('\n');
    }

    /** Writes one JSON object per error, each terminated by a newline. */
    public void writeErrors(Appendable out, List<DiffError> errors) throws IOException {
        for (DiffError error : errors) {
            out.append(gson.toJson(error));
            out.append('\n');
        }
    }
}
