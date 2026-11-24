import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Executes only the validated reward-page action on its assigned display. */
final class PhoneBatch {
    interface Control {
        void status(int owner, int clone, String detail);
        void check() throws Exception;
        default void accountProgress(Map<Integer, Integer> verified) {}
        default boolean prepareAd() throws Exception { return false; }
    }

    static final class Outside extends Exception {
        final String packageName;
        final String activity;
        final int user;

        Outside(String packageName, String activity, int user) {
            super("虚拟屏切到外部窗口（" + packageName + "），未点击该窗口");
            this.packageName = packageName;
            this.activity = activity;
            this.user = user;
        }
    }

    /** Rebuild the virtual display before trying another account. No taps on a foreign screen. */
    static final class Transient extends Exception {
        Transient(String message) { super(message); }
    }

    /** At least one account needs another attempt; no security or device error is retried here. */
    static final class Incomplete extends IOException {
        Incomplete(String message) { super(message); }
    }

    /** Only account-local, recoverable conditions use this exception. */
    private static final class AccountProblem extends IOException {
        AccountProblem(String message) { super(message); }
    }

    private static final class CommandTimeout extends IOException {
        CommandTimeout(String command) { super("命令超时（" + command + "）"); }
    }

    private static final class DumpUnavailable extends IOException {
        DumpUnavailable() { super("读取虚拟屏暂时失败"); }
    }

    private static final String TARGET = "com.etalien.booster";
    private static final String HOME = ".ui.MainActivity";
    private static final String CONTROLLER = "com.alienbooster.claimer";
    private static final String LAUNCHER = "com.hihonor.android.launcher";
    private static final int[] TOTALS = {9, 3, 9};
    private static final List<String> INSTALLERS = Arrays.asList(
        "com.hihonor.appmarket", "com.hihonor.gamecenter", "com.huawei.appmarket",
        "com.android.vending", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.hihonor.android.packageinstaller",
        "com.huawei.android.packageinstaller"
    );
    private static final Pattern DISPLAY = Pattern.compile("^\\s*Display: mDisplayId=(\\d+)\\b");
    private static final Pattern FOCUS = Pattern.compile(
        "mCurrentFocus=Window\\{[^}]*?\\bu(\\d+)\\s+([\\w.]+)/([\\w.$]+)"
    );
    private static final Pattern FOCUSED_TARGET = Pattern.compile(
        "mFocusedApp=.*\\bu\\d+\\s+" + Pattern.quote(TARGET) + "/"
    );
    private static final Pattern LOCKED = Pattern.compile(
        "(?:mShowingLockscreen|mKeyguardShowing|isKeyguardShowing|keyguardShowing)=true\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LOCK_WINDOW = Pattern.compile(
        "mCurrentFocus=Window\\{[^}]*(?:Keyguard|Lockscreen)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern FRACTION = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern BOUNDS = Pattern.compile(
        "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"
    );
    private static final long OPEN_TIMEOUT_MS = 55_000;
    private static final long WATCH_MS = 40_000;
    private static final int MAX_OUTPUT = 8 * 1024 * 1024;

    private final int displayId;
    private final String displayArg;
    private final Control control;
    private final File xml;
    private final List<Integer> profiles;
    private final Map<Integer, Integer> verified = new LinkedHashMap<>();
    private Integer foregroundUser;

    private PhoneBatch(int displayId, Control control, File xml, List<Integer> profiles) {
        this.displayId = displayId;
        this.displayArg = Integer.toString(displayId);
        this.control = control;
        this.xml = xml;
        this.profiles = profiles;
    }

    static void run(int displayId, Control control) throws Exception {
        run(displayId, control, false);
    }

    static void run(int displayId, Control control, boolean cloneFirst) throws Exception {
        if (displayId <= 0) throw new IllegalArgumentException("仅允许独立虚拟屏");
        runProfiles(displayId, control, Arrays.asList(0, 128), cloneFirst ? 1 : 0, false);
    }

    static void runForeground(Control control) throws Exception {
        runProfiles(0, control, Arrays.asList(0, 128), 0, false);
    }

    static void runProfiles(int displayId, Control control, List<Integer> profiles, int rotate,
            boolean foregroundContinuation) throws Exception {
        runProfiles(displayId, control, profiles, rotate, foregroundContinuation, false);
    }

    static boolean tryOneOwnerAd(Control control) throws Exception {
        return runProfiles(0, control, Collections.singletonList(0), 0, false, true);
    }

    private static boolean runProfiles(int displayId, Control control, List<Integer> profiles, int rotate,
            boolean foregroundContinuation, boolean tryOneAd) throws Exception {
        if (displayId < 0 || (foregroundContinuation && displayId != 0)) {
            throw new IllegalArgumentException("主屏续轮只允许屏幕 0");
        }
        List<Integer> checked = checkedProfiles(profiles);
        Path directory = Files.createTempDirectory(new File("/data/local/tmp").toPath(),
            "claimer-batch-", PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rwx------")));
        File xml = directory.resolve("window.xml").toFile();
        try {
            return new PhoneBatch(displayId, control, xml, checked).runAccounts(rotate, foregroundContinuation, tryOneAd);
        } finally {
            Files.deleteIfExists(xml.toPath());
            Files.deleteIfExists(directory);
        }
    }

    private static List<Integer> checkedProfiles(List<Integer> profiles) {
        if (profiles == null || profiles.isEmpty()) throw new IllegalArgumentException("没有已核对的账号");
        LinkedHashSet<Integer> checked = new LinkedHashSet<>();
        for (Integer user : profiles) {
            if (user == null || user < 0 || !checked.add(user)) {
                throw new IllegalArgumentException("账号名单包含未知或重复的用户");
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(checked));
    }

    private boolean runAccounts(int rotate, boolean foregroundContinuation, boolean tryOneAd) throws Exception {
        if (displayId == 0) {
            Focus initial = readFocus().get(0);
            if (foregroundContinuation && isTarget(initial) && isHome(initial)) {
                foregroundUser = initial.user;
            } else if (!isController(initial)) {
                throw foregroundLeft();
            }
        } else {
            Focus initial = guard(null, true);
            if (initial != null && !isHome(initial)) {
                throw new Transient("虚拟屏仍有未结束的外星仔窗口");
            }
        }
        List<Account> order = new ArrayList<>(profiles.size());
        int offset = Math.floorMod(rotate, profiles.size());
        for (int i = 0; i < profiles.size(); i++) {
            int user = profiles.get((offset + i) % profiles.size());
            String label = user == 0 ? "主号" : user == 128 ? "分身" : "账号 " + user;
            order.add(new Account(user, label));
        }
        if (tryOneAd) {
            control.check();
            return visitAccount(order.get(0), 1);
        }
        boolean workLeft;
        do {
            workLeft = false;
            for (Account account : order) {
                if (!account.eligible()) continue;
                control.check();
                try {
                    visitAccount(account, 2);
                    account.failedVisits = 0;
                    account.lastIssue = null;
                } catch (AccountProblem unavailable) {
                    account.failedVisits++;
                    account.lastIssue = unavailable.getMessage();
                    status(account.label + "：" + account.lastIssue
                        + (account.eligible() ? "，稍后再核对" : "，本次暂缓"));
                }
                workLeft |= account.eligible();
            }
        } while (workLeft);
        StringBuilder failure = new StringBuilder("本轮未领满");
        boolean incomplete = false;
        for (Account account : order) {
            if (account.complete()) continue;
            incomplete = true;
            failure.append("，").append(account.label).append("：").append(account.lastIssue);
        }
        if (incomplete) throw new Incomplete(failure.toString());
        status("已核对 " + order.size() + " 个账号今日各 21/21");
        return false;
    }

    private boolean visitAccount(Account account, int maxAttempts) throws Exception {
        int user = account.user;
        String label = account.label;
        status("正在核对" + label + "实际进度");
        restart(user);
        Page page = freshPage(user);
        record(account, page);
        boolean confirmed = false;
        // An account gets up to two ad attempts each visit, even if it has many left.
        for (int attempt = 0; attempt < maxAttempts && !page.complete(); attempt++) {
            if (page.button == null) throw new AccountProblem("没有唯一可识别的领取按钮，未点击");
            if (!isHome(guard(user, false))) throw interruptedPage(label + "已不在领取主页，未点击");
            // Activating the protection can take a moment. Refresh the button after it changes.
            if (control.prepareAd()) {
                page = freshPage(user);
                record(account, page);
                if (page.complete()) break;
                if (page.button == null) throw new AccountProblem("领取按钮已变化，未点击");
            }
            if (!isHome(guard(user, false))) throw interruptedPage(label + "已不在领取主页，未点击");
            shell(5, "input", "-d", displayArg, "tap",
                Integer.toString(page.button[0]), Integer.toString(page.button[1]));
            watch(user, label);
            restart(user);
            Page next = freshPage(user);
            boolean unchanged = Arrays.equals(page.stages, next.stages);
            record(account, next);
            page = next;
            if (unchanged) throw new AccountProblem("本条未确认入账");
            confirmed = true;
        }
        if (page.complete()) status(label + "今日进度已满 21/21");
        return confirmed;
    }

    private void record(Account account, Page page) throws IOException {
        if (account.lastPage != null) {
            for (int i = 0; i < TOTALS.length; i++) {
                if (page.stages[i] < account.lastPage.stages[i]) {
                    throw new IOException(account.label + "阶段计数倒退，已暂停");
                }
            }
            if (page.total() > account.lastPage.total()) account.failedVisits = 0;
        }
        account.lastPage = page;
        verified.put(account.user, page.total());
        control.accountProgress(Collections.unmodifiableMap(new LinkedHashMap<>(verified)));
        status(account.label + "已核对 " + page.total() + "/21");
    }

    private void status(String detail) {
        control.status(verified.getOrDefault(0, -1), verified.getOrDefault(128, -1), detail);
    }

    private void restart(int user) throws Exception {
        if (!profiles.contains(user)) throw new IOException("系统账号未经核对，已暂停");
        Focus before = null;
        if (displayId == 0) {
            before = readFocus().get(0);
            boolean stillOwned = foregroundUser == null ? isController(before)
                : isTarget(before) && before.user == foregroundUser;
            if (!stillOwned) throw foregroundLeft();
        } else {
            guard(null, true);
        }
        shell(7, "am", "force-stop", "--user", Integer.toString(user), TARGET);
        if (displayId == 0) {
            if (!isExpectedTransition(readFocus().get(0), before, user)) throw foregroundLeft();
        } else {
            guard(null, true);
        }
        String result = shell(20, "am", "start", "-W", "--display", displayArg,
            "--user", Integer.toString(user), "-n", TARGET + "/" + HOME);
        if (!result.contains("Status: ok")) throw new IOException("外星仔未能冷启动到指定屏幕");
        if (displayId == 0) {
            long deadline = now() + 2_500;
            while (true) {
                Focus current = readFocus().get(0);
                if (isTarget(current) && current.user == user) {
                    foregroundUser = user;
                    break;
                }
                if (!isExpectedTransition(current, before, user) || now() >= deadline) throw foregroundLeft();
                Thread.sleep(200);
            }
        }
    }

    private Page freshPage(int user) throws Exception {
        String lastError = "目标屏幕还未显示外星仔主页";
        for (int attempt = 0; attempt < 5; attempt++) {
            if (isHome(guard(user, true))) {
                try {
                    Files.deleteIfExists(xml.toPath());
                    String result;
                    try {
                        result = shell(13, "uiautomator", "dump", "--windows", xml.getAbsolutePath());
                    } catch (CommandTimeout | DumpUnavailable unavailable) {
                        // Recheck physical display/account/protection before another attempt.
                        guard(user, true);
                        lastError = unavailable.getMessage();
                        Thread.sleep(1_500);
                        continue;
                    }
                    if (result.contains("SecurityException") || result.contains("Permission Denial")) {
                        throw new IOException("系统拒绝读取领取页面");
                    }
                    if (!isHome(guard(user, true))) throw interruptedPage("读取时已离开外星仔主页");
                    try {
                        return readPage(xml, displayId);
                    } catch (AccountProblem malformed) {
                        lastError = malformed.getMessage();
                    }
                } finally {
                    Files.deleteIfExists(xml.toPath());
                }
            }
            Thread.sleep(1_500);
        }
        throw new AccountProblem(lastError);
    }

    private void watch(int user, String label) throws Exception {
        long tappedAt = now();
        long openedAt = -1;
        long homeSince = -1;
        long emptySince = -1;
        while (true) {
            Focus window = guard(user, true);
            long current = now();
            if (window == null) {
                if (emptySince < 0) emptySince = current;
                if (current - emptySince >= 4_000) throw interruptedPage("广告期间目标屏幕持续无焦点");
                Thread.sleep(1_200);
                continue;
            }
            emptySince = -1;
            if (!isHome(window)) {
                if (openedAt < 0) {
                    openedAt = current;
                    status(label + "广告已打开，等待播放");
                }
                homeSince = -1;
            } else if (openedAt >= 0 && homeSince < 0) {
                homeSince = current;
            }
            if (openedAt >= 0 && (current - openedAt >= WATCH_MS
                    || (homeSince >= 0 && current - homeSince >= 4_000))) return;
            if (openedAt < 0 && current - tappedAt >= OPEN_TIMEOUT_MS) {
                status(label + "未确认打开广告，正在复核进度");
                return;
            }
            Thread.sleep(1_200);
        }
    }

    private Map<Integer, Focus> readFocus() throws Exception {
        control.check();
        String output = shell(8, "dumpsys", "window", "displays");
        Map<Integer, Focus> screens = new HashMap<>();
        int current = -1;
        for (String line : output.split("\\r?\\n")) {
            Matcher header = DISPLAY.matcher(line);
            if (header.find()) {
                current = Integer.parseInt(header.group(1));
                screens.put(current, null);
                continue;
            }
            if (current == 0) {
                if (LOCKED.matcher(line).find() || LOCK_WINDOW.matcher(line).find()) {
                    throw new IOException("检测到主屏锁定，已暂停");
                }
                if (displayId != 0 && FOCUSED_TARGET.matcher(line).find()) {
                    throw new IOException("外星仔正在占用主屏，未操作");
                }
            }
            if (current < 0 || !line.contains("mCurrentFocus=")) continue;
            Matcher focus = FOCUS.matcher(line);
            if (focus.find()) {
                screens.put(current, new Focus(Integer.parseInt(focus.group(1)),
                    focus.group(2), focus.group(3)));
            } else if (!line.contains("mCurrentFocus=null")) {
                screens.put(current, new Focus(-1, "系统窗口", ""));
            }
        }
        if (!screens.containsKey(0)) throw new IOException("主屏状态无法核对，已暂停");
        Focus physical = screens.get(0);
        if (displayId != 0 && physical != null && TARGET.equals(physical.packageName)) {
            throw new IOException("外星仔在主屏使用中，未操作");
        }
        if (physical != null && INSTALLERS.contains(physical.packageName)) {
            throw new IOException("主屏显示应用市场或安装器，已停止");
        }
        return screens;
    }

    private Focus guard(Integer user, boolean emptyOk) throws Exception {
        Map<Integer, Focus> screens = readFocus();
        if (displayId == 0) return checkedForeground(screens.get(0), user, emptyOk);
        if (!screens.containsKey(displayId)) throw new Transient("目标虚拟屏已退出");
        Focus virtual = screens.get(displayId);
        if (virtual == null) {
            if (!emptyOk) throw new Transient("虚拟屏焦点消失，未点击");
            return null;
        }
        if (INSTALLERS.contains(virtual.packageName)) {
            throw new IOException("虚拟屏显示应用市场或安装器，已停止");
        }
        if (!TARGET.equals(virtual.packageName)) throw new Outside(virtual.packageName, virtual.activity, virtual.user);
        if (!profiles.contains(virtual.user) || (user != null && user != virtual.user)) {
            throw new IOException("虚拟屏账号与本轮目标不一致");
        }
        return virtual;
    }

    private Focus checkedForeground(Focus focus, Integer user, boolean emptyOk) throws IOException {
        if (focus == null && emptyOk) return null;
        if (!isTarget(focus) || (user != null && focus.user != user)) throw foregroundLeft();
        return focus;
    }

    private boolean isExpectedTransition(Focus current, Focus before, int user) {
        if (isController(before)) return isController(current);
        if (isTarget(current)) return current.user == before.user || current.user == user;
        // Only stopping the account that was visible can reveal the launcher or controller.
        return before.user == user && (current == null || isController(current)
            || (current.user == 0 && LAUNCHER.equals(current.packageName)));
    }

    private static boolean isController(Focus focus) {
        return focus != null && focus.user == 0 && CONTROLLER.equals(focus.packageName)
            && (".MainActivity".equals(focus.activity) || (CONTROLLER + ".MainActivity").equals(focus.activity));
    }

    private boolean isTarget(Focus focus) {
        return focus != null && TARGET.equals(focus.packageName) && profiles.contains(focus.user);
    }

    private static IOException foregroundLeft() {
        return new IOException("主屏已切换或账号改变，已暂停，回到外星仔领时长后再点原按钮");
    }

    private Exception interruptedPage(String message) {
        return displayId == 0 ? foregroundLeft() : new Transient(message);
    }

    private static boolean isHome(Focus focus) {
        return focus != null && (HOME.equals(focus.activity) || (TARGET + HOME).equals(focus.activity));
    }

    private static long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private static Page readPage(File xml, int displayId) throws IOException {
        try {
            if (!xml.isFile() || xml.length() == 0) throw new AccountProblem("页面层级尚未就绪");
            if (xml.length() > MAX_OUTPUT) throw new IOException("页面数据过大，未读取");
            // UiAutomator emits UTF-8. Decode first and reject DTDs before Android's parser,
            // whose supported JAXP features differ from desktop Java.
            String contents = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
            if (contents.contains("<!DOCTYPE") || contents.contains("<!ENTITY")) {
                throw new IOException("页面数据含非预期的 XML 类型声明");
            }
            InputSource source = new InputSource(new StringReader(contents));
            NodeList displays = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(source).getElementsByTagName("display");
            Element selected = null;
            for (int i = 0; i < displays.getLength(); i++) {
                Element display = (Element) displays.item(i);
                if (Integer.toString(displayId).equals(display.getAttribute("id"))) {
                    if (selected != null) throw new IOException("目标虚拟屏重复");
                    selected = display;
                }
            }
            if (selected == null) throw new AccountProblem("页面数据中暂时没有目标虚拟屏");
            List<String> labels = new ArrayList<>();
            List<int[]> buttons = new ArrayList<>();
            NodeList nodes = selected.getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                if (!TARGET.equals(node.getAttribute("package"))) continue;
                String id = node.getAttribute("resource-id");
                String text = node.getAttribute("text").trim();
                if ((TARGET + ":id/UIStateTitle").equals(id)) labels.add(text);
                if ((TARGET + ":id/UIADSubmitText").equals(id)
                        && "true".equals(node.getAttribute("enabled"))) {
                    String compact = text.replaceAll("[\\s，,：:]", "");
                    if (!compact.equals("看广告领时长") && !compact.equals("广告加载失败点击重试")) continue;
                    Matcher rect = BOUNDS.matcher(node.getAttribute("bounds"));
                    if (!rect.matches()) continue;
                    int left = Integer.parseInt(rect.group(1));
                    int top = Integer.parseInt(rect.group(2));
                    int right = Integer.parseInt(rect.group(3));
                    int bottom = Integer.parseInt(rect.group(4));
                    if (left < right && top < bottom) {
                        buttons.add(new int[]{left + (right - left) / 2, top + (bottom - top) / 2});
                    }
                }
            }
            if (labels.size() != TOTALS.length) throw new AccountProblem("没有读到三个可核对的阶段");
            int[] stages = new int[TOTALS.length];
            boolean allPriorComplete = true;
            for (int i = 0; i < TOTALS.length; i++) {
                int cap = TOTALS[i];
                String label = labels.get(i);
                Matcher fraction = FRACTION.matcher(label);
                if ("已完成".equals(label)) {
                    stages[i] = cap;
                } else if ("待解锁".equals(label)) {
                    stages[i] = 0;
                } else if (fraction.matches() && Integer.parseInt(fraction.group(2)) == cap) {
                    stages[i] = Integer.parseInt(fraction.group(1));
                } else {
                    throw new IOException("阶段状态无法核实");
                }
                if (stages[i] > cap || (stages[i] > 0 && !allPriorComplete)) {
                    throw new IOException("阶段进度顺序或数量不一致");
                }
                allPriorComplete &= stages[i] == cap;
            }
            return new Page(stages, buttons.size() == 1 ? buttons.get(0) : null);
        } catch (org.xml.sax.SAXParseException error) {
            throw new AccountProblem("页面层级还未读完整");
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("读取外星仔页面数据失败", error);
        }
    }

    private String shell(int timeoutSeconds, String... argv) throws Exception {
        String command = argv[0];
        Process process;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException error) {
            throw new IOException("设备拒绝启动命令（" + command + "）", error);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    int remaining = MAX_OUTPUT - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(n, remaining));
                }
            } catch (IOException ignored) {
                // The guarded timeout/cancellation path closes the pipe.
            }
        }, "claimer-drain");
        drain.setDaemon(true);
        drain.start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new CommandTimeout(command);
            }
            drain.join(1_000);
            if (drain.isAlive() || output.size() >= MAX_OUTPUT) {
                throw new IOException("设备拒绝命令或输出异常（" + command + "）");
            }
            String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if ("uiautomator".equals(command) &&
                    Pattern.compile("SecurityException|Permission Denial|permission denied", Pattern.CASE_INSENSITIVE)
                        .matcher(result).find()) {
                throw new IOException("系统拒绝读取虚拟屏页面");
            }
            int code = process.exitValue();
            if (code != 0) {
                if ("uiautomator".equals(command) && code != 126 && code != 127) {
                    System.out.println("DUAL_DIAG UI_DUMP_UNAVAILABLE exit=" + code);
                    throw new DumpUnavailable();
                }
                throw new IOException("设备拒绝命令（" + command + "）");
            }
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static final class Focus {
        final int user;
        final String packageName;
        final String activity;

        Focus(int user, String packageName, String activity) {
            this.user = user;
            this.packageName = packageName;
            this.activity = activity;
        }
    }

    private static final class Page {
        final int[] stages;
        final int[] button;

        Page(int[] stages, int[] button) {
            this.stages = stages;
            this.button = button;
        }

        boolean complete() {
            return Arrays.equals(stages, TOTALS);
        }

        int total() {
            return stages[0] + stages[1] + stages[2];
        }
    }

    private static final class Account {
        final int user;
        final String label;
        int failedVisits;
        Page lastPage;
        String lastIssue;

        Account(int user, String label) {
            this.user = user;
            this.label = label;
        }

        boolean complete() { return lastPage != null && lastPage.complete(); }
        boolean eligible() { return !complete() && failedVisits < 2; }
    }
}
