import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Local shell worker for reward tasks. The socket accepts only typed dashboard commands. */
public final class DualDaemon {
    private static final File DIRECTORY = new File("/data/local/tmp/codex-dual");
    private static final String MARKET = new File(DIRECTORY, "market_lease.sh").getPath();
    private static final int PORT = 34783;
    private static final String STATUS_PROTOCOL = "[0.12 guarded] ";
    private static final String TARGET = "com.etalien.booster";
    private static final String[] STORES = {"com.hihonor.appmarket", "com.android.vending", "com.hihonor.gamecenter"};
    private static final Pattern INSTALL_PRIVILEGE = Pattern.compile(
        "android\\.permission\\.(?:INSTALL_PACKAGES|INSTALL_SELF_UPDATES|INSTALL_PACKAGE_UPDATES)" +
        ":\\s*granted=true\\b");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern INSTALL_SESSION = Pattern.compile("createdMillis=(\\d{13})");
    private static final Pattern PROFILE_ROW = Pattern.compile("(?m)^\\s*UserInfo\\{(\\d+):");
    private static final Pattern MAIN_FOCUS = Pattern.compile(
        "mCurrentFocus=Window\\{[^}]*?\\bu(\\d+)\\s+([\\w.]+)/([\\w.$]+)");
    private static final Pattern LOCKED = Pattern.compile(
        "(?:isKeyguardShowing|mShowingLockscreen|mKeyguardShowing|keyguardShowing)=true\\b",
        Pattern.CASE_INSENSITIVE);
    private static final long RUN_LIMIT_MS = 90 * 60_000L;
    private static final long QUOTA_WAIT_MS = 15 * 60_000L;
    private static final long INCOMPLETE_WAIT_MS = 2 * 60_000L;
    private static final long NETWORK_RECHECK_MS = 30_000L;
    private static final long INSTALL_RECHECK_MS = 30_000L;
    private static final List<Integer> DUAL = Arrays.asList(0, 128);
    private static final Object WAIT = new Object();
    private static DisplayManager displays;
    private static volatile String state = "IDLE";
    private static volatile String detail = "后台助手已就绪";
    private static volatile int owner = -1;
    private static volatile int clone = -1;
    private static volatile boolean stop;
    private static volatile boolean cleanupFailed;
    private static volatile boolean allowMobile;
    private static volatile Map<Integer, Integer> progress = Collections.emptyMap();
    private static Mode mode = Mode.BACKGROUND;
    private static Thread worker;

    private enum Mode {
        BACKGROUND("START", "", false, false, false),
        FOREGROUND("START_FOREGROUND", "[主屏双号] ", true, false, false),
        FOREGROUND_SOLO("START_FOREGROUND_SOLO", "[主屏仅主号] ", true, false, false),
        FOREGROUND_SOLO_TRIAL("START_FOREGROUND_SOLO_TRIAL", "[主屏仅主号] ", true, false, false),
        ALL_BACKGROUND("START_ALL_BACKGROUND", "[后台全部] ", false, true, false),
        ALL_FOREGROUND("START_ALL_FOREGROUND", "[主屏全部] ", true, true, false),
        CONTINUOUS_BACKGROUND("START_CONTINUOUS_BACKGROUND", "[后台持续] ", false, true, true),
        CONTINUOUS_FOREGROUND("START_CONTINUOUS_FOREGROUND", "[主屏持续] ", true, true, true);

        final String command;
        final String prefix;
        final boolean foreground;
        final boolean all;
        final boolean continuous;

        Mode(String command, String prefix, boolean foreground, boolean all, boolean continuous) {
            this.command = command;
            this.prefix = prefix;
            this.foreground = foreground;
            this.all = all;
            this.continuous = continuous;
        }

        static Mode fromCommand(String command) {
            for (Mode mode : values()) if (mode.command.equals(command)) return mode;
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000) throw new SecurityException("ADB shell required");
        byte[] token = fileText(new File(DIRECTORY, "token")).getBytes(StandardCharsets.US_ASCII);
        if (!new String(token, StandardCharsets.US_ASCII).matches("[0-9a-f]{64}")) {
            throw new IOException("Missing app pairing token");
        }
        Looper.prepareMainLooper();
        displays = buildDisplayManager();
        File policy = new File(DIRECTORY, "network-policy");
        allowMobile = policy.isFile() && "ALLOW_MOBILE".equals(fileText(policy));
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 4);
            try {
                shell(15, "/system/bin/sh", MARKET, "--recover");
            } catch (IOException error) {
                cleanupFailed = true;
                update("ERROR", "之前的商店保护恢复未确认，暂不运行");
            }
            System.out.println("DUAL_DAEMON_READY");
            for (;;) {
                try (Socket socket = server.accept()) {
                    handle(socket, token);
                } catch (IOException ignored) {
                    // Clients can leave while a notification or dashboard refresh is pending.
                } catch (RuntimeException error) {
                    // A malformed request or record must not terminate the long-lived listener.
                    System.out.println("DUAL_DIAG CLIENT_ERROR " + error.getClass().getSimpleName());
                }
            }
        }
    }

    private static void handle(Socket socket, byte[] token) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + 2500;
        String[] hello = readLine(socket, deadline, 45).split("\\t", -1);
        if (hello.length != 2 || !"HELLO".equals(hello[0]) || !hello[1].matches("[0-9a-f]{32}")) return;
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String nonces = hello[1] + "\t" + hex(random);
        writeLine(socket, "AUTH\t" + hex(random) + "\t" + mac(token, "hello\t" + nonces));

        String[] command = readLine(socket, deadline, 124).split("\\t", -1);
        Mode requested = command.length == 3 ? Mode.fromCommand(command[1]) : null;
        if (command.length != 3 || !"CMD".equals(command[0]) ||
            (requested == null && !command[1].matches("STATUS|STOP|NET_WIFI_ONLY|NET_ALLOW_MOBILE|SCHEDULE_ALL_BACKGROUND")
                && !TaskHistory.supports(command[1])) ||
            !MessageDigest.isEqual(mac(token, "cmd\t" + nonces + "\t" + command[1])
                .getBytes(StandardCharsets.US_ASCII), command[2].getBytes(StandardCharsets.US_ASCII))) return;
        String reply;
        if (TaskHistory.supports(command[1])) {
            try {
                reply = "HIST\t" + TaskHistory.rpc(command[1]);
            } catch (IOException error) {
                reply = "ERR\t历史记录操作未完成";
            }
            writeLine(socket, reply + "\t" + mac(token, "result\t" + nonces + "\t" + reply));
            return;
        }
        synchronized (DualDaemon.class) {
            if ("SCHEDULE_ALL_BACKGROUND".equals(command[1]) && worker != null && worker.isAlive()) {
                reply = "ERR\tBUSY";
                writeLine(socket, reply + "\t" + mac(token, "result\t" + nonces + "\t" + reply));
                return;
            }
            if (requested != null) start(requested);
            else if ("SCHEDULE_ALL_BACKGROUND".equals(command[1])) start(Mode.ALL_BACKGROUND);
            else if (command[1].startsWith("NET_")) {
                boolean next = "NET_ALLOW_MOBILE".equals(command[1]);
                if (allowMobile != next) {
                    File temp = new File(DIRECTORY, "network-policy.tmp");
                    Files.write(temp.toPath(), (next ? "ALLOW_MOBILE" : "WIFI_ONLY").getBytes(StandardCharsets.US_ASCII));
                    Files.move(temp.toPath(), new File(DIRECTORY,"network-policy").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    allowMobile = next;
                }
            }
            else if ("STOP".equals(command[1]) && worker != null && worker.isAlive()) {
                stop = true;
                synchronized (WAIT) { WAIT.notifyAll(); }
                update("RUNNING", "正在暂停并收尾");
            }
            reply = "OK\t" + state + "\t" + owner + "\t" + clone + "\t" + STATUS_PROTOCOL + mode.prefix + detail;
        }
        writeLine(socket, reply + "\t" + mac(token, "result\t" + nonces + "\t" + reply));
    }

    private static String readLine(Socket socket, long deadline, int limit) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(limit);
        InputStream input = socket.getInputStream();
        for (int i = 0; i < limit; i++) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new IOException("Protocol timed out");
            socket.setSoTimeout((int) remaining);
            int value = input.read();
            if (value == '\n') return raw.toString("US-ASCII");
            if (value < 0 || value > 126 || (value < 32 && value != '\t')) throw new IOException("Invalid request");
            raw.write(value);
        }
        throw new IOException("Request line too long");
    }

    private static void writeLine(Socket socket, String line) throws IOException {
        socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String mac(byte[] token, String message) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token, "HmacSHA256"));
            return hex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException error) {
            throw new IOException("Authentication unavailable", error);
        }
    }

    private static String hex(byte[] bytes) {
        String digits = "0123456789abcdef";
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(digits.charAt((b >> 4) & 15)).append(digits.charAt(b & 15));
        return out.toString();
    }

    private static void start(Mode requested) {
        if (worker != null && worker.isAlive()) return;
        mode = requested;
        System.out.println("DUAL_MODE " + requested.name());
        stop = false;
        owner = -1;
        clone = -1;
        progress = Collections.emptyMap();
        update("RUNNING", "正在核对安装保护");
        worker = new Thread(() -> run(requested), "dual-claim");
        worker.start();
    }

    private static void run(Mode requested) {
        String finalState = "DONE";
        String finalDetail = "本轮已核对所有选定账号";
        try {
            if (cleanupFailed) {
                try {
                    shell(15, "/system/bin/sh", MARKET, "--recover");
                    cleanupFailed = false;
                } catch (IOException error) {
                    throw new IOException("上次的商店保护仍未恢复，稍后再点继续");
                }
            }
            boolean continuation = false;
            for (;;) {
                checkStop();
                Selection selection = requested.all ? discoverProfiles() : new Selection(
                    requested == Mode.FOREGROUND_SOLO || requested == Mode.FOREGROUND_SOLO_TRIAL
                        ? Collections.singletonList(0) : DUAL, 0);
                if (selection.profiles.isEmpty()) throw new IOException("当前没有可核对的外星仔账号");
                boolean complete;
                String result = "";
                try {
                    result = runCycle(requested, selection, continuation);
                    complete = true;
                } catch (InstallChangedBeforeAd busy) {
                    if (requested.foreground) throw busy;
                    update("WAITING", withProgress(selection, "等待已有应用更新结束，可暂停"));
                    awaitNextCycle(requested, selection, INSTALL_RECHECK_MS);
                    continuation = true;
                    continue;
                } catch (NetworkUnavailable unavailable) {
                    if (!requested.continuous) throw unavailable;
                    awaitNetwork(requested, selection);
                    continuation = true;
                    continue;
                } catch (PhoneBatch.Incomplete | CycleLimit notFinished) {
                    if (!requested.continuous) throw notFinished;
                    complete = false;
                }
                if (!requested.continuous) {
                    finalDetail = requested == Mode.FOREGROUND_SOLO_TRIAL ? result :
                        selection.profiles.size() + " 个可用账号已领满" + selection.skippedNote();
                    break;
                }
                long delay = complete ? QUOTA_WAIT_MS : INCOMPLETE_WAIT_MS;
                update("RUNNING", withProgress(selection, complete
                    ? "已领满，15 分钟后再查额度" : "部分账号暂不可领，2 分钟后再查"));
                awaitNextCycle(requested, selection, delay);
                continuation = true;
            }
        } catch (Stopped stopped) {
            finalState = "STOPPED";
            finalDetail = "已暂停，回到领取助手点原按钮可继续";
        } catch (Exception error) {
            finalState = "ERROR";
            finalDetail = sanitize(error.getMessage());
            System.out.println("DUAL_DIAG WORKER_FAILURE " + error.getClass().getSimpleName());
        } finally {
            update(finalState, finalDetail);
        }
    }

    private static String runCycle(Mode requested, Selection selection, boolean continuation) throws Exception {
        Lease lease = new Lease(selection.profiles);
        long startedAt = System.currentTimeMillis();
        long startClock = SystemClock.elapsedRealtime();
        Map<Integer,Integer> first = new TreeMap<>();
        Map<Integer,Integer> latest = new TreeMap<>();
        String outcome = "已领满";
        progress = Collections.emptyMap();
        owner = -1;
        clone = -1;
        try {
            update("RUNNING", withProgress(selection, "正在核对账号和网络"));
            lease.preflight(requested, selection);
            checkStop();
            PhoneBatch.Control control = new PhoneBatch.Control() {
                @Override public void accountProgress(Map<Integer, Integer> verified) {
                    for (Map.Entry<Integer,Integer> account : verified.entrySet()) {
                        if (account.getValue() < 0) continue;
                        first.putIfAbsent(account.getKey(), account.getValue());
                        latest.put(account.getKey(), account.getValue());
                    }
                    progress = new TreeMap<>(latest);
                }

                @Override public void status(int a, int b, String message) {
                    if (a >= 0) owner = a;
                    if (b >= 0) clone = b;
                    update("RUNNING", withProgress(selection, message));
                }

                @Override public void check() throws Exception {
                    checkStop();
                    lease.check(false);
                }

                @Override public boolean prepareAd() throws Exception {
                    checkStop();
                    lease.checkNetwork(true);
                    if (lease.started) {
                        lease.check(false);
                        return false;
                    }
                    update("RUNNING", withProgress(selection, "正在启用本轮广告安装保护"));
                    lease.start();
                    return true;
                }
            };
            if (requested == Mode.FOREGROUND_SOLO_TRIAL) {
                boolean confirmed = PhoneBatch.tryOneOwnerAd(control);
                lease.check(true);
                outcome = confirmed ? "试跑 1 条已确认到账" : "主号今日已满 21/21";
                return outcome;
            }
            if (requested.foreground) {
                PhoneBatch.runProfiles(0, control, selection.profiles, 0, continuation);
            } else {
                runBackground(lease, control, selection.profiles);
            }
            lease.check(true);
            for (int user : selection.profiles) {
                if (!Integer.valueOf(21).equals(progress.get(user))) {
                    throw new IOException("仍有账号的满额进度尚未核实");
                }
            }
            return outcome;
        } catch (Exception failure) {
            outcome = failure instanceof Stopped ? "已暂停" : sanitize(failure.getMessage());
            if (failure instanceof InstallChangedBeforeAd && !requested.foreground) outcome = null;
            throw failure;
        } finally {
            update("RUNNING", withProgress(selection, lease.started ? "正在恢复应用商店" : "正在收尾"));
            boolean restored = lease.close();
            int gained = 0;
            for (Map.Entry<Integer,Integer> account : latest.entrySet()) {
                gained += Math.max(0, account.getValue() - first.get(account.getKey()));
            }
            try {
                if (!restored || (outcome != null &&
                    (!requested.continuous || gained > 0 || !"已领满".equals(outcome)))) {
                    TaskHistory.add(startedAt, System.currentTimeMillis(), SystemClock.elapsedRealtime() - startClock,
                        requested.name(), restored ? outcome : "未确认商店恢复", gained);
                }
            } catch (IOException persistError) {
                System.out.println("DUAL_DIAG HISTORY_WRITE_FAILED");
                if (restored) throw new IOException("任务结束，耗时记录保存失败");
            }
            if (!restored) {
                cleanupFailed = true;
                throw new IOException("未确认商店已恢复，请重新接入后台助手");
            }
        }
    }

    private static void awaitNetwork(Mode requested, Selection selection) throws Exception {
        do {
            update("WAITING", withProgress(selection, allowMobile
                ? "等待可用网络，恢复后继续" : "等待可用 Wi-Fi，恢复后继续"));
            awaitNextCycle(requested, selection, NETWORK_RECHECK_MS);
        } while (!networkAvailable());
    }

    private static void awaitNextCycle(Mode requested, Selection selection, long delay) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + delay;
        for (;;) {
            checkStop();
            // The wait owns no screen and has already restored the app stores.
            if (requested.foreground && !foregroundStillAvailable(selection.profiles)) throw new Stopped();
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) return;
            synchronized (WAIT) {
                checkStop();
                try {
                    WAIT.wait(Math.min(left, requested.foreground ? 10_000 : 60_000));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new Stopped();
                }
            }
        }
    }

    private static boolean foregroundStillAvailable(List<Integer> profiles) throws IOException {
        String dump = shell(8, "dumpsys", "window", "displays");
        Matcher display = Pattern.compile("(?m)^\\s*Display: mDisplayId=(\\d+)\\b").matcher(dump);
        while (display.find()) {
            if (!"0".equals(display.group(1))) continue;
            int start = display.end();
            String main = dump.substring(start, display.find() ? display.start() : dump.length());
            if (LOCKED.matcher(main).find()) return false;
            Matcher focus = MAIN_FOCUS.matcher(main);
            if (!focus.find()) return false;
            int user = Integer.parseInt(focus.group(1));
            return ("com.alienbooster.claimer".equals(focus.group(2)) && user == 0 &&
                    focus.group(3).matches("(?:com\\.alienbooster\\.claimer)?\\.MainActivity")) ||
                (TARGET.equals(focus.group(2)) && profiles.contains(user) &&
                    focus.group(3).matches("(?:com\\.etalien\\.booster)?\\.ui\\.MainActivity"));
        }
        return false;
    }

    private static Selection discoverProfiles() throws IOException {
        String users = shell(8, "pm", "list", "users");
        Set<Integer> identifiers = new TreeSet<>();
        Matcher rows = PROFILE_ROW.matcher(users);
        while (rows.find()) identifiers.add(Integer.parseInt(rows.group(1)));
        if (!identifiers.contains(0)) throw new IOException("无法核对 Android 用户列表");
        List<Integer> selected = new ArrayList<>();
        int skipped = 0;
        for (int user : identifiers) {
            try {
                String id = Integer.toString(user);
                String installed = shell(5, "pm", "list", "packages", "--user", id, TARGET);
                if (!checkedTargetListing(installed)) continue;
                String enabled = shell(5, "pm", "list", "packages", "-e", "--user", id, TARGET);
                if (checkedTargetListing(enabled)) selected.add(user);
                else skipped++;
            } catch (IOException inaccessible) {
                if (user == 0) throw new IOException("系统未能核对主空间外星仔");
                skipped++;
            }
        }
        return new Selection(selected, skipped);
    }

    private static boolean hasPackage(String listing, String pkg) {
        return Arrays.asList(listing.split("\\r?\\n")).contains("package:" + pkg);
    }

    private static boolean checkedTargetListing(String listing) throws IOException {
        // On some ROMs pm prints a permission error but still exits with code zero.
        for (String row : listing.split("\\r?\\n")) {
            String line = row.trim();
            if (!line.isEmpty() && !line.matches("package:[a-zA-Z0-9_.]+")) {
                throw new IOException("无法读取该空间的应用列表");
            }
        }
        return hasPackage(listing, TARGET);
    }

    private static String withProgress(Selection selection, String message) {
        if (!mode.all) return message;
        StringBuilder text = new StringBuilder(message);
        Map<Integer, Integer> values = progress;
        int shown = 0;
        int copy = 0;
        for (int user : selection.profiles) {
            if (shown == 5) {
                text.append(" · 另有 ").append(selection.profiles.size() - shown).append(" 个账号");
                break;
            }
            shown++;
            text.append(" · ").append(user == 0 ? "主号" : "分身 " + (++copy)).append(' ')
                .append(values.containsKey(user) ? values.get(user).toString() : "—")
                .append("/21");
        }
        if (selection.skipped > 0) text.append(selection.skippedNote());
        return text.toString();
    }

    private static final class Selection {
        final List<Integer> profiles;
        final int skipped;

        Selection(List<Integer> profiles, int skipped) {
            this.profiles = profiles;
            this.skipped = skipped;
        }

        String skippedNote() {
            return skipped == 0 ? "" : "，另有 " + skipped + " 个空间不可核对或不可用";
        }
    }

    private static void runBackground(Lease lease, PhoneBatch.Control control, List<Integer> profiles) throws Exception {
        int lastTotal = -1;
        int noProgress = 0;
        int rotate = 0;
        for (;;) {
            try (Screen screen = new Screen()) {
                PhoneBatch.runProfiles(screen.id(), control, profiles, rotate, false);
                return;
            } catch (PhoneBatch.Outside | PhoneBatch.Transient interruption) {
                checkStop();
                if (interruption instanceof PhoneBatch.Outside) {
                    PhoneBatch.Outside outside = (PhoneBatch.Outside) interruption;
                    System.out.println("DUAL_DIAG VIRTUAL_OUTSIDE u" + outside.user + " " +
                        outside.packageName + "/" + outside.activity);
                    if (!canRecoverFrom(outside, profiles)) {
                        throw new IOException("广告跳到系统窗口或有安装权限的应用，已暂停");
                    }
                }
                lease.check(true);
                int total = 0;
                for (int value : progress.values()) total += Math.max(0, value);
                noProgress = total > lastTotal ? 0 : noProgress + 1;
                lastTotal = total;
                if (noProgress >= 3) {
                    throw new IOException("重建虚拟屏后连续未推进，请稍后点后台领双号");
                }
                rotate = (rotate + 1) % profiles.size();
                update("RUNNING", "虚拟屏中断，正在重建并核对另一账号");
                SystemClock.sleep(900);
            }
        }
    }

    // The old owned display has already closed. These checks never open the foreign app.
    private static boolean canRecoverFrom(PhoneBatch.Outside outside, List<Integer> profiles) throws IOException {
        String pkg = outside.packageName;
        if ((outside.user != 0 && !profiles.contains(outside.user)) ||
                !pkg.matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) return false;
        String userId = Integer.toString(outside.user);
        // Closing this chooser with its owning display does not select any external destination.
        boolean honorChooser = "com.hihonor.android.internal.app".equals(pkg) &&
            (".HwResolverActivity".equals(outside.activity) || (pkg + ".HwResolverActivity").equals(outside.activity));
        if (!honorChooser) {
            String packages = shell(5, "pm", "list", "packages", "-3", "--user", userId, pkg);
            if (!Arrays.asList(packages.split("\\r?\\n")).contains("package:" + pkg)) return false;
        }
        String metadata = shell(8, "dumpsys", "package", pkg);
        if (!metadata.contains("Package [" + pkg + "]") || INSTALL_PRIVILEGE.matcher(metadata).find()) return false;
        if (honorChooser && !metadata.contains("codePath=/system/app/HnResolver")) return false;
        String appOps = shell(5, "cmd", "appops", "get", "--user", userId, pkg, "REQUEST_INSTALL_PACKAGES");
        Matcher explicit = Pattern.compile("(?m)^REQUEST_INSTALL_PACKAGES:[ \\t]*(\\w+)").matcher(appOps);
        if (explicit.find()) return explicit.group(1).matches("ignore|deny|default");
        return appOps.contains("No operations.") && Pattern.compile("(?m)^Default mode:[ \\t]*default\\b")
            .matcher(appOps).find();
    }

    private static synchronized void update(String next, String message) {
        state = next;
        detail = sanitize(message);
        System.out.println("DUAL_STATUS " + next + " " + owner + " " + clone + " " + detail);
    }

    private static String sanitize(String message) {
        if (message == null || message.trim().isEmpty()) return "后台流程异常，可重新开始";
        return message.replaceAll("[\\r\\n\\t]", " ").substring(0, Math.min(150, message.length()));
    }

    private static void checkStop() throws Stopped {
        if (stop) throw new Stopped();
    }

    private static final class Stopped extends Exception {}
    private static final class CycleLimit extends IOException {
        CycleLimit() { super("本轮已到时间上限，可点按钮继续"); }
    }
    private static final class NetworkUnavailable extends IOException {
        NetworkUnavailable(boolean mobileAllowed) {
            super(mobileAllowed ? "当前没有可验证的可用网络，已暂停" : "当前没有可用 Wi‑Fi，已暂停以节省移动数据");
        }
    }

    private static final class InstallBusy extends IOException {
        InstallBusy() { super("手机存在活跃的应用安装任务，稍后再试"); }
    }

    private static final class InstallChangedBeforeAd extends IOException {
        InstallChangedBeforeAd(String detail) { super(detail); }
    }

    private static final class Lease {
        private final String base = new File(DIRECTORY, "session-" + UUID.randomUUID()).getPath();
        private final Set<String> initial = new HashSet<>();
        private final Set<String> stores = new HashSet<>();
        private final List<Integer> profiles;
        private boolean started;
        private long runDeadline;
        private long lastCheck;
        private long lastNetworkCheck;

        Lease(List<Integer> profiles) {
            this.profiles = profiles;
        }

        void preflight(Mode requested, Selection selection) throws Exception {
            for (;;) {
                checkStop();
                checkNetwork(true);
                try {
                    initial.addAll(installSessions());
                    break;
                } catch (InstallBusy busy) {
                    if (requested.foreground) throw busy;
                    update("WAITING", withProgress(selection, "等待已有应用更新结束，可暂停"));
                    awaitNextCycle(requested, selection, INSTALL_RECHECK_MS);
                }
            }
            update("RUNNING", withProgress(selection, "正在核对直接安装权限"));
            verifyInstallPermissions();
            runDeadline = SystemClock.elapsedRealtime() + RUN_LIMIT_MS;
        }

        void start() throws Exception {
            check(true);
            String installed = shell(8, "pm", "list", "packages", "--user", "0");
            for (String store : STORES) if (hasPackage(installed, store)) stores.add(store);
            touch(base + ".lease");
            List<String> command = new ArrayList<>(Arrays.asList("/system/bin/setsid", "/system/bin/sh", MARKET, base));
            for (int user : profiles) command.add(Integer.toString(user));
            java.lang.Process watchdog = new ProcessBuilder(command)
                .redirectErrorStream(true).redirectOutput(new File(base + ".market-log")).start();
            started = true;
            long deadline = SystemClock.elapsedRealtime() + 25_000;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (new File(base + ".market-ready").isFile()) {
                    check(true);
                    return;
                }
                File failure = new File(base + ".market-error");
                if (failure.isFile()) throw new IOException(fileText(failure));
                if (!watchdog.isAlive() && watchdog.exitValue() != 0) {
                    throw new IOException("无法启动应用安装保护");
                }
                checkStop();
                SystemClock.sleep(200);
            }
            throw new IOException("应用安装保护启动超时");
        }

        void check(boolean immediately) throws Exception {
            checkStop();
            checkNetwork(immediately);
            long now = SystemClock.elapsedRealtime();
            if (now >= runDeadline) throw new CycleLimit();
            if (!immediately && now - lastCheck < 8_000) return;
            verifyProtection(now);
        }

        private void verifyProtection(long now) throws IOException {
            verifyInstallPermissions();
            Set<String> sessions;
            try {
                sessions = installSessions();
            } catch (InstallBusy busy) {
                if (!started) throw new InstallChangedBeforeAd(busy.getMessage());
                throw busy;
            }
            if (!initial.containsAll(sessions)) {
                String message = "检测到新安装会话，已暂停";
                if (!started) throw new InstallChangedBeforeAd(message);
                throw new IOException(message);
            }
            if (!started) {
                lastCheck = now;
                return;
            }
            if (!new File(base + ".market-ready").exists() || new File(base + ".market-restored").exists()) {
                throw new IOException("应用安装保护已退出，已暂停");
            }
            String disabled = shell(5, "pm", "list", "packages", "-d", "--user", "0");
            Set<String> observed = new HashSet<>(Arrays.asList(disabled.split("\\r?\\n")));
            for (String store : stores) {
                if (!observed.contains("package:" + store)) {
                    throw new IOException("应用商店安装保护已失效，已暂停");
                }
            }
            touch(base + ".lease");
            lastCheck = now;
        }

        void checkNetwork(boolean force) throws IOException {
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastNetworkCheck < 2500) return;
            if (!networkAvailable()) {
                if (started) verifyProtection(now);
                throw new NetworkUnavailable(allowMobile);
            }
            lastNetworkCheck = now;
        }

        private void verifyInstallPermissions() throws IOException {
            for (int profile : profiles) {
                String op = shell(5, "cmd", "appops", "get", "--user", Integer.toString(profile), TARGET,
                                  "REQUEST_INSTALL_PACKAGES");
                if (!Pattern.compile("REQUEST_INSTALL_PACKAGES:\\s*(ignore|deny)\\b").matcher(op).find()) {
                    throw new IOException("外星仔安装权限状态发生变化，已暂停");
                }
            }
        }

        boolean close() {
            if (!started) return true;
            try {
                touch(base + ".market-stop");
                long deadline = SystemClock.elapsedRealtime() + 40_000;
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (new File(base + ".market-restored").isFile()) return true;
                    SystemClock.sleep(200);
                }
            } catch (IOException ignored) {}
            return false;
        }
    }

    private static Set<String> installSessions() throws IOException {
        String text = shell(6, "dumpsys", "package", "installs");
        int active = text.indexOf("Active install sessions:");
        int finalized = text.indexOf("Finalized install sessions:");
        if (active < 0 || finalized <= active || !text.contains("Historical install sessions:")) {
            throw new IOException("无法核对手机安装会话");
        }
        if (!text.substring(active + "Active install sessions:".length(), finalized).trim().isEmpty()) {
            throw new InstallBusy();
        }
        Set<String> sessions = new HashSet<>();
        Matcher matcher = INSTALL_SESSION.matcher(text);
        while (matcher.find()) sessions.add(matcher.group(1));
        return sessions;
    }

    private static boolean networkAvailable() throws IOException {
        String dump = shell(5, "dumpsys", "connectivity");
        return usableNetwork(dump, allowMobile);
    }

    static boolean usableNetwork(String dump, boolean mobileAllowed) {
        Matcher current = Pattern.compile("(?m)^\\s*Active default network:\\s*(\\d+)\\s*$").matcher(dump);
        if (!current.find()) return false;
        String agent = "NetworkAgentInfo{network{" + current.group(1) + "}";
        for (String row : dump.split("\\r?\\n")) {
            if (!row.trim().startsWith(agent)) continue;
            Matcher caps = Pattern.compile("Transports:\\s*([^\\]]*?)\\s+Capabilities:\\s*([A-Z0-9_&]+)").matcher(row);
            if (!caps.find()) return false;
            String transports = caps.group(1);
            List<String> capabilities = Arrays.asList(caps.group(2).split("&"));
            if (!capabilities.contains("INTERNET") || !capabilities.contains("VALIDATED")) return false;
            return mobileAllowed || (Pattern.compile("\\bWIFI\\b").matcher(transports).find() &&
                !Pattern.compile("\\bCELLULAR\\b").matcher(transports).find());
        }
        return false;
    }

    private static void touch(String file) throws IOException {
        shell(4, "/system/bin/touch", file);
    }

    private static String fileText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
    }

    private static String shell(int seconds, String... command) throws IOException {
        java.lang.Process child = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean[] overflow = {false};
        Thread reader = new Thread(() -> {
            try (InputStream input = child.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read <= 2_000_000 && !overflow[0]) output.write(buffer, 0, read);
                    else overflow[0] = true;
                }
            } catch (IOException ignored) {}
        }, "dual-shell-output");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!child.waitFor(seconds, TimeUnit.SECONDS)) throw new IOException("系统检查超时");
            reader.join(500);
            if (child.exitValue() != 0 || reader.isAlive() || overflow[0]) throw new IOException("系统检查失败：" + command[0]);
            return output.toString("UTF-8");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("系统检查已中断");
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    private static final class Screen implements AutoCloseable {
        private final HandlerThread thread = new HandlerThread("dual-display-frames");
        private ImageReader reader;
        private VirtualDisplay screen;

        Screen() throws IOException {
            thread.start();
            try {
                reader = ImageReader.newInstance(720, 1600, PixelFormat.RGBA_8888, 2);
                reader.setOnImageAvailableListener(source -> {
                    try (Image unused = source.acquireLatestImage()) {
                        // Drain the off-screen surface. No main-screen capture or persistent recording.
                    } catch (IllegalStateException ignored) {}
                }, new Handler(thread.getLooper()));
                screen = displays.createVirtualDisplay("alien-dual-task", 720, 1600, 320,
                                                       reader.getSurface(), 0x17dc9);
                if (screen == null || id() <= 0) throw new IOException("系统未创建独立虚拟屏");
            } catch (RuntimeException | IOException error) {
                close();
                throw new IOException("无法创建独立虚拟屏", error);
            }
        }

        int id() {
            return screen.getDisplay().getDisplayId();
        }

        @Override public void close() {
            if (screen != null) screen.release();
            if (reader != null) reader.close();
            thread.quitSafely();
        }
    }

    private static DisplayManager buildDisplayManager() throws Exception {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        set(type, null, "sCurrentActivityThread", thread);
        set(type, thread, "mSystemThread", true);
        Class<?> controller = Class.forName("android.app.ConfigurationController");
        Constructor<?> config = controller.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
        config.setAccessible(true);
        set(type, thread, "mConfigurationController", config.newInstance(thread));
        Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
        Context shell = new ContextWrapper(system) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
            @Override public Context getApplicationContext() { return this; }
        };
        Constructor<DisplayManager> manager = DisplayManager.class.getDeclaredConstructor(Context.class);
        manager.setAccessible(true);
        return manager.newInstance(shell);
    }

    private static void set(Class<?> type, Object owner, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
