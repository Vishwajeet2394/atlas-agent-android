package com.atlas.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The phone agent. Reads the screen of the app in front, asks the AI for the next step, and taps/types/scrolls.
 * It never presses a button that books, orders, pays or sends without the user tapping Confirm in its overlay.
 */
public class AgentService extends AccessibilityService {
    static volatile AgentService I;
    private static final JSONArray LOG = new JSONArray();
    private static volatile boolean stop = false;
    private static volatile boolean busy = false;

    private Handler ui;
    private volatile long lastEvent = 0;
    private WindowManager wm;
    private LinearLayout card;
    private TextView tTitle, tBody;
    private EditText input;
    private TextView bA, bB;
    private WindowManager.LayoutParams lp;
    private boolean shown = false;

    // reply from the overlay: "confirm", "cancel", "stop" or the user's typed answer
    private final AtomicReference<String> reply = new AtomicReference<>();
    private volatile CountDownLatch waitReply;

    // anything that commits money or messages
    static final Pattern SENSITIVE = Pattern.compile(
            "(place\\s*(your\\s*)?order|pay\\s*&\\s*place|complete\\s*(order|purchase|payment)|book\\s*tickets?|pay\\s*and\\s*book|confirm\\s*&\\s*pay|pay\\b|pay\\s*₹|pay\\s*rs|pay\\s*now|proceed\\s*to\\s*pay|make\\s*payment|confirm\\s*(ride|booking|order|pickup|and\\s*pay|payment)|"
                    + "book\\s*(now|ride|uber|ola|auto|cab|moto|mini|sedan|prime|go|xl)|request\\s*(uber|ola|ride|auto|go|moto|premier)|slide\\s*to|swipe\\s*to|^send$|^send\\s|"
                    + "place\\s*request|^confirm$|^buy\\s*now|order\\s*now|^book$)", Pattern.CASE_INSENSITIVE);

    @Override public void onServiceConnected() {
        I = this;
        ui = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        event("ready", "Agent switched on", null);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { lastEvent = SystemClock.uptimeMillis(); }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(Intent i) { I = null; hideOverlay(); return super.onUnbind(i); }

    static boolean on() { return I != null; }
    static boolean isBusy() { return busy; }
    static void requestStop() { stop = true; AgentService s = I; if (s != null) s.answer("stop"); }
    static String log() { synchronized (LOG) { return LOG.toString(); } }

    private static void event(String type, String text, JSONObject extra) {
        try {
            JSONObject o = extra == null ? new JSONObject() : extra;
            o.put("type", type).put("text", text).put("t", System.currentTimeMillis());
            synchronized (LOG) { LOG.put(o); while (LOG.length() > 400) LOG.remove(0); }
            MainActivity.agentEvent(o);
        } catch (Exception ignored) { }
    }

    // ============================================================ tasks
    /** tasks: [{app, goal, allow?, nolaunch?}] — run one after another. */
    static boolean run(JSONArray tasks) {
        AgentService s = I;
        if (s == null || busy) return false;
        stop = false; busy = true;
        new Thread(() -> {
            try {
                for (int i = 0; i < tasks.length() && !stop; i++) {
                    JSONObject t = tasks.optJSONObject(i);
                    if (t == null) continue;
                    String r = s.runTask(t, i, tasks.length());
                    if ("cancel".equals(r) && i < tasks.length() - 1) {
                        // a cancelled step does not stop the remaining tasks unless the user tapped Stop
                        if (stop) break;
                    }
                }
            } catch (Exception e) {
                event("error", "Agent stopped: " + e.getMessage(), null);
            } finally {
                busy = false;
                event("idle", stop ? "Stopped" : "All tasks finished", null);
                s.finishOverlay(stop ? "Stopped." : "Done — tap to go back to Atlas.");
            }
        }, "atlas-agent").start();
        return true;
    }

    private String runTask(JSONObject t, int idx, int count) throws Exception {
        String goal = t.optString("goal"), app = t.optString("app", "");
        String allow = t.optString("allow", "");
        String label = count > 1 ? "Task " + (idx + 1) + " of " + count : "Atlas";
        JSONObject ex = new JSONObject().put("task", idx);
        event("task", goal, ex);
        showOverlay(label, "Starting: " + goal);
        String pkg = app.isEmpty() ? null : Apps.resolve(this, app);
        if (!app.isEmpty() && pkg == null && !t.optBoolean("nolaunch")) {
            event("fail", app + " isn't installed on this phone.", new JSONObject().put("task", idx));
            status(label, app + " isn't installed — skipped.");
            sleep(2500);
            return "fail";
        }
        if (pkg != null && !t.optBoolean("nolaunch")) { Apps.launch(this, pkg); sleep(3000); }
        List<String> history = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        int maxSteps = Ai.prefs(this).getInt("max_steps", 45);
        boolean useShot = Ai.prefs(this).getBoolean("screenshots", true);
        String lastSig = ""; int same = 0;
        for (int step = 1; step <= maxSteps; step++) {
            if (stop) return "stop";
            waitIdle(800, 5000);
            Screen sc = capture(useShot);
            if (sc == null) { history.add("(screen unreadable, waited)"); sleep(1500); continue; }
            if (sc.text.equals(lastSig)) same++; else { same = 0; lastSig = sc.text; }
            status(label + " · step " + step, "Looking at " + Apps.label(this, sc.pkg) + "…");
            JSONObject req = new JSONObject().put("system", SYSTEM).put("json", true).put("maxTokens", 1200);
            StringBuilder u = new StringBuilder();
            u.append("GOAL: ").append(goal).append('\n');
            if (!answers.isEmpty()) u.append("USER ANSWERS: ").append(String.join(" | ", answers)).append('\n');
            u.append("STEP ").append(step).append(" of max ").append(maxSteps).append('\n');
            if (!history.isEmpty()) {
                u.append("RECENT ACTIONS:\n");
                for (int i = Math.max(0, history.size() - 10); i < history.size(); i++) u.append(i + 1).append(". ").append(history.get(i)).append('\n');
            }
            if (same >= 2) u.append("WARNING: the screen has not changed for ").append(same).append(" steps — your last action had no effect. Try something different.\n");
            u.append("CURRENT APP: ").append(Apps.label(this, sc.pkg)).append(" (").append(sc.pkg).append(")\n");
            u.append("SCREEN ").append(sc.w).append('x').append(sc.h).append(" ELEMENTS:\n").append(sc.text);
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", u.toString()));
            if (sc.shot != null) parts.put(new JSONObject().put("mime", "image/jpeg").put("data", sc.shot));
            req.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
            JSONObject a;
            try {
                a = Ai.parseJson(Ai.call(this, req));
            } catch (Exception e) {
                event("error", "AI error: " + e.getMessage(), new JSONObject().put("task", idx));
                status(label, "AI error — retrying…");
                sleep(4000);
                try { a = Ai.parseJson(Ai.call(this, req)); }
                catch (Exception e2) { event("fail", "Couldn't reach the AI: " + e2.getMessage(), new JSONObject().put("task", idx)); return "fail"; }
            }
            String act = a.optString("action", "wait").toLowerCase(Locale.ROOT);
            String thought = a.optString("thought", "");
            int id = a.optInt("id", -1);
            Node n = id >= 0 && id < sc.nodes.size() ? sc.nodes.get(id) : null;
            event("step", thought.isEmpty() ? act : thought, new JSONObject().put("task", idx).put("action", act).put("step", step));
            switch (act) {
                case "tap": case "click": case "long_press": {
                    if (n == null) { history.add("tap failed: no element [" + id + "]"); break; }
                    boolean sensitive = SENSITIVE.matcher(n.label.trim()).find() && !(allow.equals("send") && n.label.trim().toLowerCase(Locale.ROOT).startsWith("send"));
                    if (sensitive) {
                        String r = confirm(label, a.optString("summary", "Atlas is about to tap “" + n.label + "”."), n, sc);
                        if (!"ok".equals(r)) return r;
                        history.add("user CONFIRMED and tapped [" + id + "] " + q(n.label));
                        allow = "send".equals(allow) ? "" : allow;
                        break;
                    }
                    status(label + " · step " + step, short_(thought, "Tapping " + q(n.label)));
                    boolean ok = "long_press".equals(act) ? longPress(n) : tap(n);
                    history.add((ok ? "tapped" : "tap FAILED on") + " [" + id + "] " + q(n.label));
                    break;
                }
                case "type": {
                    String text = a.optString("text", "");
                    status(label + " · step " + step, "Typing " + q(text));
                    boolean ok = type(n, text);
                    if (ok && a.optBoolean("enter")) { sleep(300); pressEnter(); }
                    history.add((ok ? "typed " : "typing FAILED: ") + q(text) + (n != null ? " into [" + id + "] " + q(n.label) : ""));
                    break;
                }
                case "scroll": {
                    String dir = a.optString("direction", "down");
                    status(label + " · step " + step, short_(thought, "Scrolling " + dir));
                    scroll(n, dir, sc);
                    history.add("scrolled " + dir + (n != null ? " in [" + id + "]" : ""));
                    break;
                }
                case "back": performGlobalAction(GLOBAL_ACTION_BACK); history.add("pressed Back"); break;
                case "home": performGlobalAction(GLOBAL_ACTION_HOME); history.add("pressed Home"); break;
                case "wait": status(label + " · step " + step, short_(thought, "Waiting for the app…")); sleep(2500); history.add("waited"); break;
                case "open_app": {
                    String p2 = Apps.resolve(this, a.optString("app"));
                    if (p2 != null) { Apps.launch(this, p2); sleep(2500); history.add("opened " + Apps.label(this, p2)); }
                    else history.add("open_app FAILED: " + a.optString("app") + " not installed");
                    break;
                }
                case "ask_user": {
                    String qn = a.optString("question", "What should I do?");
                    event("ask", qn, new JSONObject().put("task", idx));
                    String ans = ask(label, qn);
                    if (ans == null || "stop".equals(ans)) return "stop";
                    answers.add(qn + " → " + ans);
                    history.add("asked user: " + q(qn) + " → " + q(ans));
                    break;
                }
                case "confirm": {
                    String r = confirm(label, a.optString("summary", "Ready for the final step."), n, sc);
                    if (!"ok".equals(r)) return r;
                    history.add(n != null ? "user CONFIRMED and tapped [" + id + "] " + q(n.label) : "user confirmed");
                    break;
                }
                case "done": {
                    String sum = a.optString("summary", "Done.");
                    event("done", sum, new JSONObject().put("task", idx));
                    status(label, "✓ " + sum);
                    vibrate(40);
                    sleep(1800);
                    return "done";
                }
                case "fail": {
                    String sum = a.optString("summary", "I couldn't finish this.");
                    event("fail", sum, new JSONObject().put("task", idx));
                    status(label, "✕ " + sum);
                    sleep(2500);
                    return "fail";
                }
                default: history.add("unknown action " + act); sleep(800);
            }
            sleep(700);
        }
        event("fail", "Stopped after the step limit without finishing.", new JSONObject().put("task", idx));
        return "fail";
    }

    private static String q(String s) { if (s == null) s = ""; if (s.length() > 60) s = s.substring(0, 57) + "…"; return "“" + s + "”"; }
    private static String short_(String t, String fb) { if (t == null || t.isEmpty()) return fb; return t.length() > 110 ? t.substring(0, 107) + "…" : t; }

    private String confirm(String label, String summary, Node n, Screen sc) throws Exception {
        event("confirm", summary, null);
        vibrate(70);
        String r = waitFor(() -> showConfirm(label, summary), 10 * 60);
        if ("confirm".equals(r)) {
            if (n != null) {
                status(label, "Confirmed — tapping " + q(n.label));
                Node fresh = refind(n);
                tap(fresh != null ? fresh : n);
                sleep(1500);
            }
            event("confirmed", summary, null);
            return "ok";
        }
        event("cancelled", "You cancelled: " + summary, null);
        status(label, "Cancelled — nothing was booked, ordered or sent.");
        sleep(1500);
        return "stop".equals(r) ? "stop" : "cancel";
    }

    private String ask(String label, String question) {
        vibrate(40);
        return waitFor(() -> showAsk(label, question), 10 * 60);
    }

    private String waitFor(Runnable show, int seconds) {
        reply.set(null);
        waitReply = new CountDownLatch(1);
        ui.post(show);
        try { if (!waitReply.await(seconds, TimeUnit.SECONDS)) return "stop"; } catch (InterruptedException e) { return "stop"; }
        String r = reply.get();
        ui.post(this::compact);
        return r == null ? "stop" : r;
    }

    private void answer(String r) { reply.set(r); CountDownLatch l = waitReply; if (l != null) l.countDown(); }

    // ============================================================ screen reading
    static final class Node { AccessibilityNodeInfo n; String label; String cls; Rect b; boolean click, edit, scroll; }
    static final class Screen { List<Node> nodes = new ArrayList<>(); String text = ""; String pkg = ""; String shot; int w, h; }

    private AccessibilityNodeInfo appRoot() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r != null && !getPackageName().contentEquals(r.getPackageName() == null ? "" : r.getPackageName())) return r;
        // the active window may be our own overlay or Atlas itself — find the app window underneath
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo x = w.getRoot();
                if (x != null && x.getPackageName() != null && !getPackageName().contentEquals(x.getPackageName())) return x;
            }
        } catch (Exception ignored) { }
        return r;
    }

    private Screen capture(boolean withShot) {
        AccessibilityNodeInfo root = appRoot();
        if (root == null) return null;
        Screen s = new Screen();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        s.w = dm.widthPixels; s.h = dm.heightPixels;
        s.pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        StringBuilder sb = new StringBuilder();
        walk(root, s, sb, 0);
        if (s.nodes.isEmpty()) sb.append("(no readable elements — rely on the screenshot)\n");
        s.text = sb.toString();
        if (withShot) s.shot = screenshot();
        return s;
    }

    private void walk(AccessibilityNodeInfo n, Screen s, StringBuilder sb, int depth) {
        if (n == null || depth > 60 || s.nodes.size() >= 220) return;
        if (!n.isVisibleToUser()) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        String text = n.getText() == null ? "" : n.getText().toString().trim();
        String desc = n.getContentDescription() == null ? "" : n.getContentDescription().toString().trim();
        String hint = n.getHintText() == null ? "" : n.getHintText().toString().trim();
        String label = !text.isEmpty() ? text : !desc.isEmpty() ? desc : hint;
        if (!desc.isEmpty() && !text.isEmpty() && !desc.equals(text)) label = text + " — " + desc;
        if (label.isEmpty() && n.getViewIdResourceName() != null) {
            String id = n.getViewIdResourceName(); int k = id.indexOf('/'); if (k >= 0) id = id.substring(k + 1);
            label = "#" + id;
        }
        label = label.replace('\n', ' ');
        if (label.length() > 120) label = label.substring(0, 117) + "…";
        boolean click = n.isClickable() || n.isLongClickable(), edit = n.isEditable(), scroll = n.isScrollable(), check = n.isCheckable();
        boolean useful = b.width() > 2 && b.height() > 2 && (click || edit || scroll || check || !text.isEmpty() || !desc.isEmpty());
        if (useful) {
            Node x = new Node();
            x.n = n; x.label = label; x.b = b; x.click = click; x.edit = edit; x.scroll = scroll;
            CharSequence cn = n.getClassName();
            String cls = cn == null ? "View" : cn.toString(); int dot = cls.lastIndexOf('.'); if (dot >= 0) cls = cls.substring(dot + 1);
            x.cls = cls;
            int idx = s.nodes.size();
            s.nodes.add(x);
            sb.append('[').append(idx).append("] ").append(cls).append(' ').append('"').append(label).append('"');
            List<String> fl = new ArrayList<>();
            if (click) fl.add("tap");
            if (edit) fl.add("edit" + (n.isFocused() ? ",focused" : ""));
            if (scroll) fl.add("scroll");
            if (check) fl.add(n.isChecked() ? "checked" : "unchecked");
            if (n.isSelected()) fl.add("selected");
            if (!n.isEnabled()) fl.add("disabled");
            if (!fl.isEmpty()) sb.append(" {").append(String.join(",", fl)).append('}');
            sb.append(" @").append(b.centerX()).append(',').append(b.centerY()).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), s, sb, depth + 1);
    }

    private Node refind(Node old) {
        try { if (old.n.refresh() && old.n.isVisibleToUser()) return old; } catch (Exception ignored) { }
        Screen s = capture(false);
        if (s == null) return null;
        for (Node x : s.nodes) if (x.label.equals(old.label)) return x;
        return null;
    }

    private String screenshot() {
        final AtomicReference<String> out = new AtomicReference<>();
        final CountDownLatch l = new CountDownLatch(1);
        setOverlayVisible(false);
        sleep(120);
        try {
            Executor ex = r -> new Thread(r).start();
            takeScreenshot(Display.DEFAULT_DISPLAY, ex, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult res) {
                    try {
                        HardwareBuffer hb = res.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                        if (hw != null) {
                            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                            out.set(Media.b64(Media.jpeg(Media.fit(sw, 1000), 60)));
                        }
                        hb.close();
                    } catch (Exception ignored) { }
                    l.countDown();
                }
                @Override public void onFailure(int code) { l.countDown(); }
            });
            l.await(3, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
        setOverlayVisible(true);
        return out.get();
    }

    private void waitIdle(long quietMs, long maxMs) {
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (SystemClock.uptimeMillis() - lastEvent >= quietMs) return;
            sleep(150);
        }
    }

    // ============================================================ actions
    private boolean tap(Node x) {
        AccessibilityNodeInfo c = x.n;
        for (int i = 0; i < 6 && c != null; i++) {
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            c = c.getParent();
        }
        return gestureTap(x.b.centerX(), x.b.centerY(), 60);
    }

    private boolean longPress(Node x) {
        AccessibilityNodeInfo c = x.n;
        for (int i = 0; i < 6 && c != null; i++) {
            if (c.isLongClickable() && c.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true;
            c = c.getParent();
        }
        return gestureTap(x.b.centerX(), x.b.centerY(), 800);
    }

    private boolean gestureTap(int x, int y, long dur) {
        Path p = new Path(); p.moveTo(x, y);
        return gesture(new GestureDescription.StrokeDescription(p, 0, dur));
    }

    private boolean gesture(GestureDescription.StrokeDescription stroke) {
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {false};
        setOverlayVisible(false);
        sleep(80);
        boolean sent = dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { ok[0] = true; l.countDown(); }
            @Override public void onCancelled(GestureDescription g) { l.countDown(); }
        }, ui);
        if (sent) try { l.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        sleep(60);
        setOverlayVisible(true);
        return sent && ok[0];
    }

    private boolean type(Node x, String text) {
        AccessibilityNodeInfo target = null;
        if (x != null) {
            if (x.n.isEditable()) target = x.n;
            else { tap(x); sleep(700); }
        }
        if (target == null) {
            AccessibilityNodeInfo r = appRoot();
            if (r != null) target = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null && r != null) target = firstEditable(r, 0);
        }
        if (target == null) return false;
        if (!target.isFocused()) { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS); target.performAction(AccessibilityNodeInfo.ACTION_CLICK); sleep(300); }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true;
        // fall back to paste
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            final AccessibilityNodeInfo t2 = target;
            final CountDownLatch l = new CountDownLatch(1);
            ui.post(() -> { cm.setPrimaryClip(ClipData.newPlainText("atlas", text)); l.countDown(); });
            l.await(1, TimeUnit.SECONDS);
            return t2.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Exception e) { return false; }
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo n, int d) {
        if (n == null || d > 50) return null;
        if (n.isEditable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) { AccessibilityNodeInfo r = firstEditable(n.getChild(i), d + 1); if (r != null) return r; }
        return null;
    }

    private void pressEnter() {
        AccessibilityNodeInfo r = appRoot();
        AccessibilityNodeInfo f = r == null ? null : r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f != null) f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    private void scroll(Node x, String dir, Screen sc) {
        boolean fwd = !"up".equals(dir) && !"left".equals(dir);
        AccessibilityNodeInfo c = x == null ? null : x.n;
        for (int i = 0; i < 5 && c != null; i++) {
            if (c.isScrollable() && c.performAction(fwd ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return;
            c = c.getParent();
        }
        int w = sc.w, h = sc.h;
        Path p = new Path();
        if ("left".equals(dir) || "right".equals(dir)) {
            int y = x != null ? x.b.centerY() : h / 2;
            p.moveTo(fwd ? w * 0.8f : w * 0.2f, y); p.lineTo(fwd ? w * 0.2f : w * 0.8f, y);
        } else {
            p.moveTo(w / 2f, fwd ? h * 0.72f : h * 0.3f); p.lineTo(w / 2f, fwd ? h * 0.3f : h * 0.72f);
        }
        gesture(new GestureDescription.StrokeDescription(p, 0, 380));
    }

    private void vibrate(int ms) {
        try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); } catch (Exception ignored) { }
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    // ============================================================ overlay
    private int dp(float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private TextView pill(String text, int bg, int fg) {
        TextView b = new TextView(this);
        b.setText(text); b.setTextColor(fg); b.setTextSize(14); b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable g = new GradientDrawable(); g.setColor(bg); g.setCornerRadius(dp(999)); b.setBackground(g);
        return b;
    }

    private void buildOverlay() {
        if (card != null) return;
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#F21B1D26")); g.setCornerRadius(dp(26));
        card.setBackground(g);
        card.setElevation(dp(8));
        tTitle = new TextView(this); tTitle.setTextColor(Color.parseColor("#B4C5FF")); tTitle.setTextSize(12);
        tTitle.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tBody = new TextView(this); tBody.setTextColor(Color.parseColor("#E3E2EC")); tBody.setTextSize(14.5f); tBody.setPadding(0, dp(4), 0, 0);
        tBody.setMaxLines(9);
        input = new EditText(this);
        input.setTextColor(Color.WHITE); input.setHintTextColor(Color.parseColor("#8F909A")); input.setHint("Type your answer");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        GradientDrawable ig = new GradientDrawable(); ig.setColor(Color.parseColor("#33343F")); ig.setCornerRadius(dp(16)); input.setBackground(ig);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(10);
        input.setLayoutParams(ilp);
        input.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        row.setLayoutParams(rlp);
        bB = pill("Stop", Color.parseColor("#33343F"), Color.parseColor("#E3E2EC"));
        bA = pill("Confirm", Color.parseColor("#B4C5FF"), Color.parseColor("#1A2C60"));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        row.addView(bB, blp);
        row.addView(bA, new LinearLayout.LayoutParams(blp));
        card.addView(tTitle); card.addView(tBody); card.addView(input); card.addView(row);
        bB.setOnClickListener(v -> { stop = true; answer("stop"); });
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        lp.horizontalMargin = 0;
        lp.y = dp(36);
        card.setOnClickListener(v -> { if (!busy) { openAtlas(); hideOverlay(); } });
    }

    private void addCard() {
        if (shown) { updateLp(); return; }
        try {
            // margins on the sides
            LinearLayout wrap = (LinearLayout) card.getTag();
            if (wrap == null) {
                wrap = new LinearLayout(this);
                wrap.setPadding(dp(12), 0, dp(12), 0);
                wrap.addView(card, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                card.setTag(wrap);
            }
            wm.addView(wrap, lp);
            shown = true;
        } catch (Exception e) { shown = false; }
    }

    private void updateLp() {
        LinearLayout wrap = card == null ? null : (LinearLayout) card.getTag();
        if (wrap != null && shown) try { wm.updateViewLayout(wrap, lp); } catch (Exception ignored) { }
    }

    private void showOverlay(String title, String body) { ui.post(() -> { buildOverlay(); compact(); tTitle.setText(title); tBody.setText(body); addCard(); }); }
    private void status(String title, String body) { ui.post(() -> { if (card == null) return; tTitle.setText(title); tBody.setText(body); }); }

    private void compact() {
        if (card == null) return;
        input.setVisibility(View.GONE);
        bA.setVisibility(View.GONE);
        bB.setText("Stop");
        bB.setOnClickListener(v -> { stop = true; answer("stop"); });
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        updateLp();
        hideKeyboard();
    }

    private void showConfirm(String title, String summary) {
        buildOverlay(); addCard();
        tTitle.setText(title + " · needs your OK");
        tBody.setText(summary);
        input.setVisibility(View.GONE);
        bA.setVisibility(View.VISIBLE); bA.setText("Confirm");
        bA.setOnClickListener(v -> answer("confirm"));
        bB.setText("Cancel");
        bB.setOnClickListener(v -> answer("cancel"));
    }

    private void showAsk(String title, String question) {
        buildOverlay(); addCard();
        tTitle.setText(title + " · question");
        tBody.setText(question);
        input.setText("");
        input.setVisibility(View.VISIBLE);
        bA.setVisibility(View.VISIBLE); bA.setText("Reply");
        bA.setOnClickListener(v -> { String t = input.getText().toString().trim(); if (!t.isEmpty()) answer(t); });
        bB.setText("Stop");
        bB.setOnClickListener(v -> { stop = true; answer("stop"); });
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN; // focusable so the keyboard can type here
        updateLp();
        input.requestFocus();
        ui.postDelayed(() -> {
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private void hideKeyboard() {
        try { InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (im != null && input != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0); } catch (Exception ignored) { }
    }

    private void setOverlayVisible(boolean v) {
        final CountDownLatch l = new CountDownLatch(1);
        ui.post(() -> { LinearLayout w = card == null ? null : (LinearLayout) card.getTag(); if (w != null) w.setVisibility(v ? View.VISIBLE : View.INVISIBLE); l.countDown(); });
        try { l.await(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
    }

    private void finishOverlay(String msg) {
        ui.post(() -> {
            if (card == null) return;
            compact();
            tTitle.setText("Atlas");
            tBody.setText(msg);
            bA.setVisibility(View.VISIBLE); bA.setText("Open Atlas");
            bA.setOnClickListener(v -> { openAtlas(); hideOverlay(); });
            bB.setText("Close");
            bB.setOnClickListener(v -> hideOverlay());
        });
        ui.postDelayed(() -> { if (!busy) hideOverlay(); }, 12000);
    }

    private void hideOverlay() {
        if (ui == null) return;
        ui.post(() -> {
            LinearLayout w = card == null ? null : (LinearLayout) card.getTag();
            if (w != null && shown) try { wm.removeView(w); } catch (Exception ignored) { }
            shown = false;
        });
    }

    private void openAtlas() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    // ============================================================ prompt
    static final String SYSTEM = "You are Atlas, an AI agent that operates the user's Android phone (India) through the accessibility API to complete a GOAL. "
            + "Every turn you get the visible UI elements as lines like `[id] Class \"label\" {flags} @x,y`, usually with a screenshot. "
            + "Reply with ONE JSON object: {\"thought\": \"<=20 words on what you see and why\", \"action\": \"tap|long_press|type|scroll|back|wait|open_app|ask_user|confirm|done|fail\", "
            + "\"id\": <element id for tap/type/scroll/confirm>, \"text\": \"for type\", \"enter\": true|false, \"direction\": \"up|down|left|right\", \"app\": \"for open_app\", "
            + "\"question\": \"for ask_user\", \"summary\": \"for confirm/done/fail\"}.\n"
            + "HARD RULES:\n"
            + "1. Never complete a booking, order, payment or message yourself. When the next tap would do that (Place order, Confirm ride/pickup, Book, Request, Pay, Proceed to pay, Swipe/Slide to pay, Send, Checkout, Buy now), use action \"confirm\" with that button's id and a summary the user can check: "
            + "what is being booked/ordered (items × qty), price/total, pickup → drop or delivery address, ETA, payment method, recipient. The user taps Confirm; then continue and finish with done.\n"
            + "2. Never enter or change passwords, UPI PINs, OTPs, card numbers or bank details; never change the payment method unless the GOAL says so. If a login, OTP or PIN screen appears, use ask_user to ask the user to do it on the phone and reply 'done'.\n"
            + "3. If something essential is missing or ambiguous (drop location, which restaurant/dish, size, quantity, which saved address, which ride type when prices differ a lot), use ask_user with a short, specific question offering the options you see.\n"
            + "GUIDANCE: Use the saved/default address and default payment. Close popups, ads, rating prompts and tutorials (Close/X/Not now/Skip/Later). To fill a search field, use type on the field (it focuses it first), then tap the best suggestion — don't use enter unless there are no suggestions. "
            + "For rides: set pickup (current location is fine) and drop, then show the ride options; pick the cheapest sensible option (e.g. Uber Go / Ola Mini / Auto) unless the GOAL names one, then confirm before booking. "
            + "For food: search the restaurant or dish, prefer well-rated, open, nearby places; add items, customise with defaults, open the cart, review, then confirm before placing the order. "
            + "For groceries (Instamart/Blinkit/Zepto): add every item on the list one by one (search each, pick a popular pack of a normal size), then open the cart, check all items are there, then confirm before paying. "
            + "For shopping (Amazon, Flipkart, Myntra, Meesho, Ajio, Nykaa, JioMart, BigBasket, Croma…): search the product, open a well-rated listing that matches the request (brand, size, colour, budget), pick the variant, Add to cart (or Buy now), go to the cart/checkout, keep the default address and payment, then confirm before the final Place order / Pay button. Never apply for EMI, credit or Pay Later, and skip add-ons like protection plans unless asked. "
            + "For tickets (BookMyShow, IRCTC, redBus, MakeMyTrip): find the show/train/bus, ask_user to choose when there are several sensible options, fill passenger details from saved profiles only, then confirm before paying. "
            + "Any other app works the same way: explore its screens, ask when unsure, confirm before anything final. "
            + "For email in Gmail: the compose screen may already be filled; check To/Subject/Body and use confirm on Send. "
            + "If an action had no effect, try a different element, scroll, or wait. Use done with a one-line summary when the goal is achieved (e.g. ride booked, order placed, or everything is ready), or fail with the reason if it truly can't be done. Element ids change every turn — only use ids from the current list.";
}
