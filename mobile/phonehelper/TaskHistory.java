import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/** History for completed work intervals. Waiting for the next quota is not an interval. */
final class TaskHistory {
    private static final Path FILE = Paths.get("/data/local/tmp/codex-dual/task-history.json");
    private static final int MAX_RECORDS = 100;
    private static final int MAX_BYTES = 90 * 1024;
    private static final Pattern DELETE = Pattern.compile(
        "DELETE_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );

    private TaskHistory() {}

    static boolean supports(String command) {
        return "HISTORY".equals(command) || "CLEAR_HISTORY".equals(command) ||
            (command != null && DELETE.matcher(command).matches());
    }

    static synchronized void add(long started, long end, long elapsed, String mode, String reason,
                                 int gained) throws IOException {
        if (started <= 0 || end <= 0 || elapsed < 0 || gained < 0) {
            throw new IOException("记录的时间或计数无效");
        }
        try {
            JSONObject record = new JSONObject()
                .put("id", UUID.randomUUID().toString()).put("started", started).put("end", end)
                .put("elapsed", elapsed).put("mode", label(mode, 20)).put("reason", label(reason, 100))
                .put("gained", gained);
            JSONArray previous = read();
            JSONArray next = new JSONArray().put(record);
            for (int i = 0; i < previous.length() && next.length() < MAX_RECORDS; i++) {
                next.put(previous.getJSONObject(i));
            }
            write(next);
        } catch (JSONException invalid) {
            throw new IOException("无法写入领取记录", invalid);
        }
    }

    static synchronized String rpc(String command) throws IOException {
        if (!supports(command)) throw new IOException("未知记录操作");
        try {
            JSONArray records = read();
            int removed = 0;
            if ("CLEAR_HISTORY".equals(command)) {
                removed = records.length();
                records = new JSONArray();
                write(records);
            } else if (command.startsWith("DELETE_")) {
                String id = command.substring(7);
                JSONArray remaining = new JSONArray();
                for (int i = 0; i < records.length(); i++) {
                    JSONObject row = records.getJSONObject(i);
                    if (id.equals(row.getString("id"))) removed++;
                    else remaining.put(row);
                }
                records = remaining;
                if (removed > 0) write(records);
            }
            return new JSONObject().put("ok", true).put("deleted", removed)
                .put("records", records).toString();
        } catch (JSONException invalid) {
            throw new IOException("领取记录格式无效", invalid);
        }
    }

    private static JSONArray read() throws IOException, JSONException {
        if (Files.notExists(FILE)) return new JSONArray();
        if (Files.size(FILE) > MAX_BYTES) throw new IOException("领取记录文件过大");
        JSONArray array = new JSONArray(new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8));
        if (array.length() > MAX_RECORDS) throw new IOException("领取记录条数无效");
        return array;
    }

    private static void write(JSONArray array) throws IOException {
        byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("领取记录超出容量");
        Path temp = Files.createTempFile(FILE.getParent(), "history-", ".new");
        try {
            try (FileOutputStream stream = new FileOutputStream(temp.toFile())) {
                stream.write(bytes);
                stream.getFD().sync();
            }
            Files.move(temp, FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String label(String value, int points) {
        if (value == null) return "";
        String plain = value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").trim();
        int length = Math.min(plain.codePointCount(0, plain.length()), points);
        return plain.substring(0, plain.offsetByCodePoints(0, length));
    }
}
