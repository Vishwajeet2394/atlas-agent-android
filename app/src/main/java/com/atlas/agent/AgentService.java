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
    /** Screens Atlas must never touch: the user does these themselves. */
    static final Pattern SECRET_SCREEN = Pattern.compile(
            "(upi\\s*pin|enter\\s*(your\\s*)?(4|6)[- ]digit|\\bm-?pin\\b|\\bpasscode\\b|\\botp\\b|one[- ]time\\s*password|verification\\s*code|enter\\s*(your\\s*)?password|\\bcvv\\b|card\\s*number|net\\s*banking\\s*password)", Pattern.CASE_INSENSITIVE);

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

    static final java.util.Set<String> SHOP_APPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "in.swiggy.android", "in.swiggy.android.instamart", "com.application.zomato", "com.grofers.customer", "com.zeptoconsumerapp",
            "in.amazon.mShop.android.shopping", "com.flipkart.android", "com.myntra.android", "com.meesho.supply", "com.ril.ajio", "com.fsn.nykaa",
            "com.jpl.jiomart", "com.bigbasket.mobileapp", "com.ubercab", "com.olacabs.customer", "com.rapido.passenger",
            "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "com.bt.bms", "cris.org.in.prs.ima", "in.redbus.android"));

    // ---- step-by-step log of the last errand run, shareable from the Tasks tab
    static java.io.File traceFile(Context c) { return new java.io.File(c.getFilesDir(), "agent-trace.txt"); }
    private void trace(String line) {
        try (java.io.FileWriter w = new java.io.FileWriter(traceFile(this), true)) { w.write(line); w.write('\n'); } catch (Exception ignored) { }
    }

    boolean global(int action, long delayMs) {
        if (delayMs > 0) sleep(delayMs);
        return performGlobalAction(action);
    }
    static boolean isBusy() { return busy; }
    /** Called when Atlas comes to the front: the "Done" card is no longer needed. */
    static void hideIfIdle() { AgentService s = I; if (s != null && !busy) s.hideOverlay(); }
    static void requestStop() { stop = true; AgentService s = I; if (s != null) s.answer("stop"); }
    static String log() { synchronized (LOG) { return LOG.toString(); } }

    private static void event(String type, String text, JSONObject extra) {
        try {
            JSONObject o = extra == null ? new JSONObject() : extra;
            o.put("type", type).put("text", text).put("t", System.currentTimeMillis());
            synchronized (LOG) { LOG.put(o); while (LOG.length() > 400) LOG.remove(0); }
            android.util.Log.i("AtlasAgent", type + ": " + text);
            MainActivity.agentEvent(o);
        } catch (Exception ignored) { }
    }

    // ============================================================ tasks
    /** tasks: [{app, goal, allow?, nolaunch?}] — run one after another. */
    static boolean run(JSONArray tasks) {
        AgentService s = I;
        if (s == null || busy) return false;
        stop = false; busy = true;
        try (java.io.FileWriter w = new java.io.FileWriter(traceFile(s), false)) { w.write("Atlas errand log · " + new java.util.Date() + " · provider " + Ai.provider(s) + "\n"); } catch (Exception ignored) { }
        new Thread(() -> {
            Ai.READ_TIMEOUT.set(45000);
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
        String la = app.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
        if (la.equals("quicksettings") || la.equals("notifications") || la.equals("statusbar")) {
            performGlobalAction(la.equals("quicksettings") ? GLOBAL_ACTION_QUICK_SETTINGS : GLOBAL_ACTION_NOTIFICATIONS);
            sleep(1200);
            app = ""; t.put("nolaunch", true);
        }
        String pkg = app.isEmpty() ? null : Apps.resolve(this, app);
        if (!app.isEmpty() && pkg == null && !t.optBoolean("nolaunch")) {
            event("fail", app + " isn't installed on this phone.", new JSONObject().put("task", idx));
            status(label, app + " isn't installed — skipped.");
            sleep(2500);
            return "fail";
        }
        if (pkg != null && app.toLowerCase(Locale.ROOT).contains("instamart") && pkg.equals("in.swiggy.android"))
            goal = "Open Instamart inside the Swiggy app first (the Instamart tile/tab), then: " + goal;
        boolean shopping = pkg != null && SHOP_APPS.contains(pkg);
        trace("\n==== TASK " + (idx + 1) + ": " + goal + "  [" + (pkg == null ? app : pkg) + "]");
        if (pkg != null && !t.optBoolean("nolaunch")) {
            if (!Apps.launch(this, pkg)) {
                event("fail", "Couldn't open " + label(pkg) + ".", new JSONObject().put("task", idx));
                status(label, "Couldn't open " + label(pkg) + " — skipped.");
                sleep(2000);
                return "fail";
            }
            waitForPackage(pkg, 8000);
        }
        else sleep(600);
        List<String> history = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        int maxSteps = Ai.prefs(this).getInt("max_steps", 70);
        boolean useShot = Ai.prefs(this).getBoolean("screenshots", true);
        String lastSig = ""; int same = 0, badJson = 0, unreadable = 0; boolean askedSecret = false;
        boolean wantShot = true, lastFailed = false;
        int approvedUntil = -1;
        String notes = "";
        for (int step = 1; step <= maxSteps; step++) {
            if (stop) return "stop";
            waitIdle(450, 3000);
            if (!screenUsable()) { // screen off or locked: pause without using up steps
                status(label, "Screen is off or locked — unlock your phone to continue.");
                vibrate(60);
                long t0 = SystemClock.uptimeMillis();
                while (!screenUsable() && !stop && SystemClock.uptimeMillis() - t0 < 5 * 60_000) sleep(1000);
                if (!screenUsable()) { event("fail", "Paused too long — the phone stayed locked.", new JSONObject().put("task", idx)); return "fail"; }
                step--; continue;
            }
            Screen sc = capture(false);
            if (sc == null) {
                if (++unreadable >= 8) { event("fail", "Couldn't read the screen.", new JSONObject().put("task", idx)); return "fail"; }
                history.add("(screen unreadable, waited)"); sleep(1200); step--; continue;
            }
            unreadable = 0;
            boolean secret = SECRET_SCREEN.matcher(sc.text).find();
            if (sc.text.equals(lastSig)) same++; else { same = 0; lastSig = sc.text; }
            // screenshots cost time: send one when the element list is thin, the screen is new to us, or we're stuck
            boolean shot = useShot && (shopping || wantShot || lastFailed || same >= 1 || sc.labeled < 12 || step == 1);
            if (shot) sc.shot = screenshot();
            wantShot = false; lastFailed = false;
            status(label + " · step " + step, "Looking at " + label(sc.pkg) + "…");
            JSONObject req = new JSONObject().put("system", SYSTEM).put("json", true).put("maxTokens", 1200);
            if (shopping && Ai.prefs(this).getBoolean("careful", true)) req.put("think", "medium");
            StringBuilder u = new StringBuilder();
            u.append("GOAL: ").append(goal).append('\n');
            if (!answers.isEmpty()) u.append("USER ANSWERS: ").append(String.join(" | ", answers)).append('\n');
            if (!notes.isEmpty()) u.append("YOUR NOTES SO FAR: ").append(notes).append('\n');
            u.append("STEP ").append(step).append(" of max ").append(maxSteps).append('\n');
            if (!history.isEmpty()) {
                u.append("RECENT ACTIONS:\n");
                for (int i = Math.max(0, history.size() - 10); i < history.size(); i++) u.append(i + 1).append(". ").append(history.get(i)).append('\n');
            }
            if (same >= 2) u.append("WARNING: the screen has not changed for ").append(same).append(" steps — your last action had no effect. Try something different.\n");
            u.append("CURRENT APP: ").append(label(sc.pkg)).append(" (").append(sc.pkg).append(")\n");
            if (sc.shot != null) u.append("A SCREENSHOT OF THIS SCREEN IS ATTACHED.").append(sc.nodes.isEmpty() ? " There are no readable elements — use tap_xy with positions from the screenshot.\n" : "\n");
            if (secret) u.append("THIS IS A PIN/OTP/PASSWORD SCREEN: you must not tap, type or scroll here. Use ask_user (once) to ask the user to complete it, then wait, or done if the payment/flow has moved on.\n");
            if (step <= approvedUntil) u.append("THE USER HAS ALREADY APPROVED THE FINAL STEP — complete the payment/booking now with the selected or default method; do not ask again.\n");
            u.append("SCREEN ").append(sc.w).append('x').append(sc.h).append(" ELEMENTS:\n").append(sc.text);
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", u.toString()));
            if (sc.shot != null) parts.put(new JSONObject().put("mime", "image/jpeg").put("data", sc.shot));
            req.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)));
            String raw = null;
            for (int attempt = 0; attempt < 4 && raw == null && !stop; attempt++) {
                long tc = SystemClock.uptimeMillis();
                try { raw = Ai.call(this, req); trace("   ai " + (SystemClock.uptimeMillis() - tc) + " ms · " + Ai.prefs(this).getString("gemini_last_model", "")); }
                catch (Exception e) {
                    String m = String.valueOf(e.getMessage());
                    trace("   ai FAILED after " + (SystemClock.uptimeMillis() - tc) + " ms: " + m);
                    android.util.Log.i("AtlasAgent", "ai-error: " + m);
                    boolean rate = m.toLowerCase(Locale.ROOT).contains("rate") || m.contains("429") || m.toLowerCase(Locale.ROOT).contains("overloaded") || m.contains("503");
                    if (m.contains("API key") || m.contains("Settings first") || attempt == 3) { event("fail", "Couldn't reach the AI: " + m, new JSONObject().put("task", idx)); return "fail"; }
                    int wait = rate ? new int[]{8, 20, 40}[attempt] : 3;
                    status(label, (rate ? "AI is busy — waiting " : "Network hiccup — retrying in ") + wait + " s…");
                    sleep(wait * 1000L);
                }
            }
            if (stop || raw == null) return "stop";
            JSONObject a;
            try { a = Ai.parseJson(raw); badJson = 0; }
            catch (Exception e) {
                if (++badJson >= 3) { event("fail", "The AI kept replying in the wrong format.", new JSONObject().put("task", idx)); return "fail"; }
                history.add("(your last reply was not valid JSON — reply with one JSON object only)");
                continue;
            }
            if (a.has("notes") && !a.optString("notes").trim().isEmpty()) notes = a.optString("notes").trim();
            String act = a.optString("action", "wait").toLowerCase(Locale.ROOT);
            trace("-- step " + step + " · " + sc.pkg + (sc.shot != null ? " · screenshot" : "") + "\n" + (sc.text.length() > 3500 ? sc.text.substring(0, 3500) + "…\n" : sc.text) + ">> " + raw.trim());
            String thought = a.optString("thought", "");
            int id = a.optInt("id", -1);
            Node n = id >= 0 && id < sc.nodes.size() ? sc.nodes.get(id) : null;
            if (secret && ("tap".equals(act) || "tap_xy".equals(act) || "type".equals(act) || "long_press".equals(act) || "scroll".equals(act) || "click".equals(act))) {
                // hard rule, whatever the AI says: never touch PIN / OTP / password screens
                trace("   BLOCKED " + act + " on a PIN/OTP/password screen");
                if (!askedSecret) {
                    askedSecret = true;
                    String ans = ask(label, "Please complete this step yourself (PIN / OTP / password) — Atlas never touches it. Reply 'done' when finished.");
                    if (ans == null || "stop".equals(ans)) return "stop";
                    history.add("user handled the PIN/OTP/password screen: " + q(ans));
                } else { status(label, "Waiting for you to finish the PIN / OTP screen…"); sleep(4000); history.add("BLOCKED: Atlas never taps on PIN/OTP/password screens — waiting for the user"); }
                if (step >= 6 && history.size() > 0 && same >= 3) { event("done", "Waiting for you to finish on the PIN/OTP screen.", new JSONObject().put("task", idx)); return "done"; }
                continue;
            }
            event("step", thought.isEmpty() ? act : thought, new JSONObject().put("task", idx).put("action", act).put("step", step));
            switch (act) {
                case "tap": case "click": case "long_press": {
                    if (n == null) { history.add("tap failed: no element [" + id + "]"); break; }
                    boolean sensitive = SENSITIVE.matcher(n.label.trim()).find() && !(allow.equals("send") && n.label.trim().toLowerCase(Locale.ROOT).startsWith("send"));
                    if (sensitive && step <= approvedUntil) { // the user already said yes a moment ago (e.g. "Proceed to pay" then "Pay ₹249")
                        approvedUntil = -1;
                        status(label + " · step " + step, "Tapping " + q(n.label));
                        boolean ok = tap(n);
                        history.add((ok ? "tapped (approved)" : "tap FAILED on") + " [" + id + "] " + q(n.label));
                        lastFailed = !ok;
                        break;
                    }
                    if (sensitive) {
                        String r = confirm(label, a.optString("summary", "Atlas is about to tap “" + n.label + "”."), n, sc);
                        if (!"ok".equals(r)) return r;
                        history.add("user CONFIRMED and tapped [" + id + "] " + q(n.label));
                        allow = "send".equals(allow) ? "" : allow;
                        approvedUntil = step + 4;
                        break;
                    }
                    status(label + " · step " + step, short_(thought, "Tapping " + q(n.label)));
                    boolean ok = "long_press".equals(act) ? longPress(n) : tap(n);
                    history.add((ok ? "tapped" : "tap FAILED on") + " [" + id + "] " + q(n.label));
                    lastFailed = !ok;
                    break;
                }
                case "type": {
                    String text = a.optString("text", "");
                    status(label + " · step " + step, "Typing " + q(text));
                    boolean ok = type(n, text);
                    lastFailed = !ok;
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
                case "tap_xy": {
                    double fx = a.optDouble("x", -1), fy = a.optDouble("y", -1);
                    if (fx < 0 || fy < 0 || fx > 1 || fy > 1) { history.add("tap_xy needs x,y between 0 and 1"); break; }
                    int px = (int) Math.round(fx * sc.w), py = (int) Math.round(fy * sc.h);
                    Node at = null; for (Node c : sc.nodes) if (c.b.contains(px, py) && (c.click || c.check) && (at == null || c.b.width() * c.b.height() < at.b.width() * at.b.height())) at = c;
                    if (at != null && SENSITIVE.matcher(at.label.trim()).find() && step > approvedUntil) {
                        String r = confirm(label, a.optString("summary", "Atlas is about to tap \u201c" + at.label + "\u201d."), at, sc);
                        if (!"ok".equals(r)) return r;
                        approvedUntil = step + 4;
                        history.add("user CONFIRMED and tapped " + q(at.label));
                        break;
                    }
                    status(label + " · step " + step, short_(thought, "Tapping the screen"));
                    boolean ok = gestureTap(px, py, 60);
                    history.add((ok ? "tapped at " : "tap FAILED at ") + String.format(Locale.US, "(%.2f, %.2f)", fx, fy) + (at != null ? " on " + q(at.label) : ""));
                    lastFailed = !ok;
                    break;
                }
                case "back": performGlobalAction(GLOBAL_ACTION_BACK); history.add("pressed Back"); break;
                case "home": performGlobalAction(GLOBAL_ACTION_HOME); history.add("pressed Home"); break;
                case "wait": status(label + " · step " + step, short_(thought, "Waiting for the app…")); sleep(1800); history.add("waited"); break;
                case "look": wantShot = true; history.add("asked for a screenshot"); break;
                case "open_app": {
                    String p2 = Apps.resolve(this, a.optString("app"));
                    if (p2 != null) { Apps.launch(this, p2); waitForPackage(p2, 8000); history.add("opened " + label(p2)); }
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
                    if (step <= approvedUntil && n != null) { // already approved a moment ago — just press it
                        boolean ok = tap(n);
                        history.add((ok ? "tapped (approved)" : "tap FAILED on") + " [" + id + "] " + q(n.label));
                        lastFailed = !ok;
                        break;
                    }
                    String r = confirm(label, a.optString("summary", "Ready for the final step."), n, sc);
                    if (!"ok".equals(r)) return r;
                    approvedUntil = step + 4; // one OK covers the next payment screen too
                    history.add(n != null ? "user CONFIRMED and tapped [" + id + "] " + q(n.label) : "user CONFIRMED — now tap the final button");
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
                default: history.add("unknown action " + act); sleep(400);
            }
            // follow-up actions planned in the same reply — saves a full AI round trip each
            JSONArray then = a.optJSONArray("then");
            if (then != null && !stop && !lastFailed && ("tap".equals(act) || "type".equals(act) || "scroll".equals(act) || "back".equals(act))) {
                for (int k = 0; k < Math.min(3, then.length()) && !stop; k++) {
                    JSONObject f = then.optJSONObject(k);
                    if (f == null) break;
                    String fa = f.optString("action"), want = f.optString("label", "").trim();
                    if (!("tap".equals(fa) || "type".equals(fa)) || want.isEmpty()) break;
                    waitIdle(350, 2200);
                    Screen s2 = capture(false);
                    Node hit = s2 == null ? null : findByLabel(s2, want);
                    if (hit == null) { history.add("follow-up skipped: " + q(want) + " not on screen"); break; }
                    if (SENSITIVE.matcher(hit.label.trim()).find() && step > approvedUntil) { history.add("follow-up stopped before final step " + q(hit.label)); break; }
                    boolean ok;
                    if ("type".equals(fa)) { ok = type(hit, f.optString("text", "")); if (ok && f.optBoolean("enter")) { sleep(250); pressEnter(); } }
                    else ok = tap(hit);
                    event("step", ("type".equals(fa) ? "Typing " + q(f.optString("text")) : "Tapping " + q(hit.label)), new JSONObject().put("task", idx).put("action", fa).put("step", step));
                    status(label + " · step " + step, ("type".equals(fa) ? "Typing " + q(f.optString("text")) : "Tapping " + q(hit.label)));
                    history.add((ok ? "then " + ("type".equals(fa) ? "typed " + q(f.optString("text")) + " into " : "tapped ") : "then FAILED on ") + q(hit.label));
                    trace("   then " + fa + " " + q(want) + (ok ? " ✓" : " ✗"));
                    if (!ok) { lastFailed = true; break; }
                    sleep(250);
                }
            }
            sleep(250);
        }
        event("fail", "Stopped after the step limit without finishing.", new JSONObject().put("task", idx));
        return "fail";
    }

    private static final java.util.Map<String, String> LABELS = new java.util.concurrent.ConcurrentHashMap<>();
    private String label(String pkg) { if (pkg == null || pkg.isEmpty()) return "the phone"; return LABELS.computeIfAbsent(pkg, k -> Apps.label(this, k)); }

    private static String q(String s) { if (s == null) s = ""; if (s.length() > 60) s = s.substring(0, 57) + "…"; return "“" + s + "”"; }
    private static String short_(String t, String fb) { if (t == null || t.isEmpty()) return fb; return t.length() > 110 ? t.substring(0, 107) + "…" : t; }

    private String confirm(String label, String summary, Node n, Screen sc) throws Exception {
        event("confirm", summary, null);
        vibrate(70);
        String r = waitFor(() -> showConfirm(label, summary), 10 * 60, "confirm");
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
        return waitFor(() -> showAsk(label, question), 10 * 60, "ask");
    }

    private String waitFor(Runnable show, int seconds, String kind) {
        reply.set(null);
        waitReply = new CountDownLatch(1);
        ui.post(show);
        if (BuildConfig.SIM) { // scenario simulations: answer like a user would, after a moment
            android.content.SharedPreferences p = Ai.prefs(this);
            String ans;
            if ("confirm".equals(kind)) ans = p.getString("sim_confirm", "confirm");
            else {
                String q = p.getString("sim_answers", "");
                String[] parts = q.isEmpty() ? new String[0] : q.split("\\|", 2);
                ans = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : "Use your best judgement and continue.";
                p.edit().putString("sim_answers", parts.length > 1 ? parts[1] : "").apply();
            }
            android.util.Log.i("AtlasAgent", "sim-answer: " + kind + " -> " + ans);
            final String a2 = ans;
            ui.postDelayed(() -> answer(a2), 1200);
        }
        try { if (!waitReply.await(seconds, TimeUnit.SECONDS)) return "stop"; } catch (InterruptedException e) { return "stop"; }
        String r = reply.get();
        ui.post(this::compact);
        return r == null ? "stop" : r;
    }

    private void answer(String r) { reply.set(r); CountDownLatch l = waitReply; if (l != null) l.countDown(); }

    // ============================================================ screen reading
    static final class Node { AccessibilityNodeInfo n; String label = "", cls = "View", flags = "", ctx; Rect b; boolean click, edit, scroll, check, own, covered; }
    static final class Screen { List<Node> nodes = new ArrayList<>(); String text = ""; String pkg = ""; String shot; int w, h, labeled; }

    private boolean ours(AccessibilityNodeInfo r) { return r != null && r.getPackageName() != null && getPackageName().contentEquals(r.getPackageName()); }

    /** The window the user is looking at — an app, or the notification shade / Quick Settings — never our own overlay. */
    private AccessibilityNodeInfo appRoot() {
        AccessibilityNodeInfo active = getRootInActiveWindow();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            // 1) the active window if it isn't ours, 2) the top-most app/system window that isn't ours
            for (AccessibilityWindowInfo w : ws) {
                if (!w.isActive()) continue;
                int ty = w.getType();
                if (ty != AccessibilityWindowInfo.TYPE_APPLICATION && ty != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                AccessibilityNodeInfo x = w.getRoot();
                if (x != null && !ours(x)) return x;
            }
            AccessibilityNodeInfo best = null; int bestLayer = Integer.MIN_VALUE;
            for (AccessibilityWindowInfo w : ws) {
                int ty = w.getType();
                if (ty != AccessibilityWindowInfo.TYPE_APPLICATION && ty != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                Rect b = new Rect(); w.getBoundsInScreen(b);
                if (ty == AccessibilityWindowInfo.TYPE_SYSTEM && b.height() < getResources().getDisplayMetrics().heightPixels / 3) continue; // status/nav bars
                AccessibilityNodeInfo x = w.getRoot();
                if (x == null || ours(x)) continue;
                if (w.getLayer() > bestLayer) { bestLayer = w.getLayer(); best = x; }
            }
            if (best != null) return best;
        } catch (Exception ignored) { }
        return active;
    }

    private boolean screenUsable() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return pm.isInteractive() && !km.isKeyguardLocked();
        } catch (Exception e) { return true; }
    }

    private void waitForPackage(String pkg, long maxMs) {
        long start = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - start < maxMs && !stop) {
            AccessibilityNodeInfo r = appRoot();
            if (r != null && r.getPackageName() != null && pkg.contentEquals(r.getPackageName()) && r.getChildCount() > 0) { sleep(500); return; }
            sleep(200);
        }
    }

    private Screen capture(boolean withShot) {
        AccessibilityNodeInfo root = appRoot();
        if (root == null) return null;
        Screen s = new Screen();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        s.w = dm.widthPixels; s.h = dm.heightPixels;
        s.pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
        List<Node> all = new ArrayList<>();
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        roots.add(root);
        try {
            List<AccessibilityWindowInfo> ws = new ArrayList<>(getWindows());
            ws.sort((a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo x = w.getRoot();
                if (x == null || x.equals(root) || x.getPackageName() == null || !s.pkg.contentEquals(x.getPackageName())) continue;
                roots.add(x); // dialogs, bottom sheets and the screen under them
            }
        } catch (Exception ignored) { }
        // the top-most window of the app goes first so popups/sheets get the low ids
        int[] seen = {0};
        for (AccessibilityNodeInfo r : roots) walk(r, all, 0, seen);
        if (all.isEmpty()) trace("   (empty tree) windows: " + describeWindows());
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();
        for (Node x : all) freq.merge(x.label, 1, Integer::sum);
        for (Node x : all) if (x.ctx != null && freq.getOrDefault(x.label, 0) > 1) x.label = x.label + " (for: " + x.ctx + ")";
        // Keep what matters: inputs and buttons first, then plain text — then show them in reading order.
        List<Node> pick = new ArrayList<>();
        int interactive = 0;
        for (Node x : all) if (x.edit || x.click || x.scroll || x.check) { if (interactive++ < 170) pick.add(x); }
        for (Node x : all) { if (pick.size() >= 240) break; if (!(x.edit || x.click || x.scroll || x.check) && !x.covered) pick.add(x); }
        pick.sort((a, b) -> { int ra = a.b.top / 24, rb = b.b.top / 24; return ra != rb ? Integer.compare(ra, rb) : Integer.compare(a.b.left, b.b.left); });
        StringBuilder sb = new StringBuilder();
        for (Node x : pick) {
            int idx = s.nodes.size();
            s.nodes.add(x);
            if (x.own) s.labeled++;
            sb.append('[').append(idx).append("] ").append(x.cls).append(' ').append('"').append(x.label).append('"');
            if (!x.flags.isEmpty()) sb.append(" {").append(x.flags).append('}');
            sb.append(" @").append(x.b.centerX()).append(',').append(x.b.centerY()).append('\n');
        }
        if (all.size() > pick.size()) sb.append("(").append(all.size() - pick.size()).append(" less important elements hidden — scroll or look if needed)\n");
        if (s.nodes.isEmpty()) sb.append("(no readable elements — rely on the screenshot)\n");
        s.text = sb.toString();
        if (withShot) s.shot = screenshot();
        return s;
    }

    private String describeWindows() {
        StringBuilder sb = new StringBuilder();
        try { for (AccessibilityWindowInfo w : getWindows()) { AccessibilityNodeInfo r = w.getRoot(); sb.append("[type ").append(w.getType()).append(" layer ").append(w.getLayer()).append(w.isActive() ? " active" : "").append(" ").append(r == null ? "no-root" : r.getPackageName() + " kids " + r.getChildCount()).append("] "); } } catch (Exception ignored) { }
        return sb.toString();
    }

    private static String clean(CharSequence c) { return c == null ? "" : c.toString().replace('\n', ' ').replaceAll("\\s+", " ").trim(); }

    /** Text found inside a node (for buttons that have no label of their own), skipping one child. */
    private static void texts(AccessibilityNodeInfo n, AccessibilityNodeInfo skip, StringBuilder sb, int[] budget, int depth) {
        if (n == null || budget[0] <= 0 || depth > 8 || sb.length() > 110) return;
        if (skip != null && n.equals(skip)) return;
        budget[0]--;
        String t = clean(n.getText());
        if (t.isEmpty()) t = clean(n.getContentDescription());
        if (!t.isEmpty() && n.isVisibleToUser() && sb.indexOf(t) < 0) { if (sb.length() > 0) sb.append(" · "); sb.append(t); }
        for (int i = 0; i < n.getChildCount(); i++) texts(n.getChild(i), skip, sb, budget, depth + 1);
    }

    private void walk(AccessibilityNodeInfo n, List<Node> out, int depth, int[] seen) {
        if (n == null || depth > 70 || seen[0]++ > 2500) return;
        if (!n.isVisibleToUser()) return;
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        String text = clean(n.getText()), desc = clean(n.getContentDescription()), hint = clean(n.getHintText());
        String label = !text.isEmpty() ? text : !desc.isEmpty() ? desc : hint;
        if (!desc.isEmpty() && !text.isEmpty() && !desc.equals(text)) label = text + " — " + desc;
        boolean own = !label.isEmpty();
        boolean click = n.isClickable() || n.isLongClickable(), edit = n.isEditable(), scroll = n.isScrollable(), check = n.isCheckable();
        if (b.width() > 2 && b.height() > 2 && (click || edit || scroll || check || own)) {
            Node x = new Node();
            x.n = n; x.b = b; x.click = click; x.edit = edit; x.scroll = scroll; x.check = check; x.own = own;
            if ((click || check) && label.isEmpty()) {
                // an unlabeled button/card: describe it by the text inside it ("Chicken Biryani · ₹249 · ★4.3")
                StringBuilder sb = new StringBuilder(); texts(n, null, sb, new int[]{40}, 0);
                label = sb.toString();
                x.covered = false;
            }
            if ((click || check) && label.length() > 0 && label.length() <= 14 && !edit) {
                // short repeated buttons like "ADD", "+", "−": say which item they belong to
                AccessibilityNodeInfo p = n.getParent();
                for (int up = 0; up < 3 && p != null; up++) {
                    StringBuilder sb = new StringBuilder(); texts(p, n, sb, new int[]{30}, 0);
                    String ctx = sb.toString().replace(label, "").replaceAll("^[ ·]+|[ ·]+$", "");
                    if (ctx.length() >= 3) { x.ctx = ctx.length() > 80 ? ctx.substring(0, 77) + "…" : ctx; break; }
                    p = p.getParent();
                }
            }
            if (label.isEmpty() && n.getViewIdResourceName() != null) {
                String id = n.getViewIdResourceName(); int k = id.indexOf('/'); if (k >= 0) id = id.substring(k + 1);
                label = "#" + id;
            }
            if (label.isEmpty() && (click || check)) label = "(unlabeled " + (b.width() < 200 && b.height() < 200 ? "icon" : "area") + ")";
            if (label.length() > 140) label = label.substring(0, 137) + "…";
            x.label = label;
            CharSequence cn = n.getClassName();
            String cls = cn == null ? "View" : cn.toString(); int dot = cls.lastIndexOf('.'); if (dot >= 0) cls = cls.substring(dot + 1);
            x.cls = cls;
            List<String> fl = new ArrayList<>();
            if (click) fl.add("tap");
            if (edit) { String cur = text.equals(hint) ? "" : text; fl.add("edit" + (n.isFocused() ? ",focused" : "") + (cur.isEmpty() ? ",empty" : "")); }
            if (scroll) {
                boolean h = false, v = false;
                for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
                    int id = a.getId();
                    if (id == android.R.id.accessibilityActionScrollLeft || id == android.R.id.accessibilityActionScrollRight) h = true;
                    if (id == android.R.id.accessibilityActionScrollDown || id == android.R.id.accessibilityActionScrollUp) v = true;
                }
                fl.add(h && !v ? "scroll-horizontal" : "scroll");
            }
            if (check) fl.add(n.isChecked() ? "checked" : "unchecked");
            if (n.isSelected()) fl.add("selected");
            if (!n.isEnabled()) fl.add("disabled");
            x.flags = String.join(",", fl);
            // plain text that already shows up inside a button's label is redundant
            if (!click && !edit && !check && !scroll) {
                AccessibilityNodeInfo p = n.getParent();
                for (int up = 0; up < 3 && p != null; up++) { if (p.isClickable()) { x.covered = true; break; } p = p.getParent(); }
            }
            out.add(x);
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1, seen);
    }

    /** The element whose label best matches: exact, then starts-with, then contains (case-insensitive). */
    private static Node findByLabel(Screen s, String want) {
        String w = want.toLowerCase(Locale.ROOT);
        Node starts = null, contains = null;
        for (Node x : s.nodes) {
            String l = x.label.toLowerCase(Locale.ROOT);
            String core = l.contains(" (for: ") ? l.substring(0, l.indexOf(" (for: ")) : l;
            if (l.equals(w) || core.equals(w)) return x;
            if (starts == null && l.startsWith(w)) starts = x;
            if (contains == null && l.contains(w)) contains = x;
        }
        return starts != null ? starts : contains;
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
        if (x != null && x.n.isEditable()) target = x.n;
        else if (x != null) tap(x);
        // search bars in food/grocery apps are often fake buttons that open a new screen with the real field
        for (int i = 0; target == null && i < 12; i++) {
            sleep(200);
            AccessibilityNodeInfo r = appRoot();
            if (r == null) continue;
            target = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null || !target.isEditable()) target = firstEditable(r, 0);
        }
        if (target == null) return false;
        if (!target.isFocused()) { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS); target.performAction(AccessibilityNodeInfo.ACTION_CLICK); sleep(250); }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        sleep(250);
        target.refresh();
        String now = clean(target.getText());
        if (ok && now.contains(text.trim())) return true;
        // fall back to paste
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            final CountDownLatch l = new CountDownLatch(1);
            ui.post(() -> { cm.setPrimaryClip(ClipData.newPlainText("atlas", text)); l.countDown(); });
            l.await(1, TimeUnit.SECONDS);
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, now.length());
            target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
            boolean p = target.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            sleep(250); target.refresh();
            return p || clean(target.getText()).contains(text.trim());
        } catch (Exception e) { return ok; }
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
        boolean vert = !"left".equals(dir) && !"right".equals(dir);
        int want = "down".equals(dir) ? android.R.id.accessibilityActionScrollDown : "up".equals(dir) ? android.R.id.accessibilityActionScrollUp
                : "right".equals(dir) ? android.R.id.accessibilityActionScrollRight : android.R.id.accessibilityActionScrollLeft;
        AccessibilityNodeInfo c = x == null ? null : x.n;
        for (int i = 0; i < 5 && c != null; i++) {
            if (c.isScrollable()) {
                for (AccessibilityNodeInfo.AccessibilityAction a : c.getActionList())
                    if (a.getId() == want && c.performAction(want)) return;
            }
            c = c.getParent();
        }
        // swipe like a finger, inside the element if one was given (so a carousel scrolls, not the page)
        Rect r = x != null && x.b.width() > 100 && x.b.height() > 100 ? x.b : new Rect(0, sc.h / 8, sc.w, sc.h * 7 / 8);
        Path p = new Path();
        if (!vert) {
            float y = r.centerY(), a = r.left + r.width() * 0.85f, b = r.left + r.width() * 0.15f;
            boolean fwd = "right".equals(dir);
            p.moveTo(fwd ? a : b, y); p.lineTo(fwd ? b : a, y);
        } else {
            float xx = r.centerX(), a = r.top + r.height() * 0.78f, b = r.top + r.height() * 0.25f;
            boolean fwd = "down".equals(dir);
            p.moveTo(xx, fwd ? a : b); p.lineTo(xx, fwd ? b : a);
        }
        gesture(new GestureDescription.StrokeDescription(p, 0, 420));
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
            + "Reply with ONE JSON object: {\"thought\": \"<=20 words on what you see and why\", \"action\": \"tap|tap_xy|long_press|type|scroll|back|wait|look|open_app|ask_user|confirm|done|fail\", \"x\": 0-1, \"y\": 0-1 (tap_xy only: position as a fraction of screen width/height, read from the screenshot), "
            + "\"id\": <element id for tap/type/scroll/confirm>, \"text\": \"for type\", \"enter\": true|false, \"direction\": \"up|down|left|right\", \"app\": \"for open_app\", "
            + "\"question\": \"for ask_user\", \"summary\": \"for confirm/done/fail\", \"then\": [optional, up to 3 follow-up actions to do right after this one without asking you again: {\"action\": \"tap|type\", \"label\": \"the label the element will have\", \"text\": \"for type\"}], \"notes\": \"optional: your running progress notes, e.g. 'added: milk 1L, eggs 6 | left: bread, onions 1kg' — they are shown back to you every step\"}.\n"
            + "HARD RULES:\n"
            + "1. Never complete a booking, order, payment or message yourself. When the next tap would do that (Place order, Confirm ride/pickup, Book, Request, Pay, Proceed to pay, Swipe/Slide to pay, Send, Checkout, Buy now), use action \"confirm\" with that button's id and a summary the user can check: "
            + "what is being booked/ordered (items × qty), price/total, pickup → drop or delivery address, ETA, payment method, recipient. The user taps Confirm; then continue and finish with done.\n"
            + "2. Never enter or change passwords, UPI PINs, OTPs, card numbers or bank details; never change the payment method unless the GOAL says so. If a login, OTP or PIN screen appears, use ask_user to ask the user to do it on the phone and reply 'done'.\n"
            + "3. If something essential is missing or ambiguous (drop location, which restaurant/dish, size, quantity, which saved address, which ride type when prices differ a lot), use ask_user with a short, specific question offering the options you see.\n"
            + "GUIDANCE: Use the saved/default address and default payment. Close popups, ads, rating prompts and tutorials (Close/X/Not now/Skip/Later). To fill a search field, use type on the field (it focuses it first), then tap the best suggestion — don't use enter unless there are no suggestions. "
            + "For rides: set pickup (current location is fine) and drop, then show the ride options; pick the cheapest sensible option (e.g. Uber Go / Ola Mini / Auto) unless the GOAL names one, then confirm before booking. "
            + "Labels like \"ADD (for: Chicken Biryani · ₹249)\" tell you which item a repeated button belongs to — always pick the ADD/+ whose 'for:' matches the item you want. "
            + "In food and grocery apps the search bar on the home screen is usually a button: use type on it (Atlas taps it and types into the real field that opens). After typing, results/suggestions load — pick the matching one; use enter only if nothing appears. "
            + "If an item has a customisation sheet (size, add-ons, 'Repeat last'), choose the default/smallest sensible option and tap its Add/Confirm button inside the sheet (that is not a payment). "
            + "After adding items, the cart is reached via a bottom bar like 'View Cart', 'Next', or 'items added'. Before the final step check the cart matches the request (items and quantities; use + / − to fix). "
            + "If an item is out of stock or unavailable, pick the closest equivalent only if it is obviously the same thing (same product, different pack is fine); otherwise ask_user. Keep notes of what is done and what is left on multi-item orders. "
            + "For food: search the restaurant or dish, prefer well-rated, open, nearby places; add items, customise with defaults, open the cart, review, then confirm before placing the order. "
            + "For groceries (Instamart/Blinkit/Zepto): add every item on the list one by one (search each, pick a popular pack of a normal size), then open the cart, check all items are there, then confirm before paying. "
            + "For shopping (Amazon, Flipkart, Myntra, Meesho, Ajio, Nykaa, JioMart, BigBasket, Croma…): search the product, open a well-rated listing that matches the request (brand, size, colour, budget), pick the variant, Add to cart (or Buy now), go to the cart/checkout, keep the default address and payment, then confirm before the final Place order / Pay button. Never apply for EMI, credit or Pay Later, and skip add-ons like protection plans unless asked. "
            + "For tickets (BookMyShow, IRCTC, redBus, MakeMyTrip): find the show/train/bus, ask_user to choose when there are several sensible options, fill passenger details from saved profiles only, then confirm before paying. "
            + "Any other app works the same way: explore its screens, ask when unsure, confirm before anything final. "
            + "For bills and recharges (PhonePe, Paytm, Google Pay, Amazon Pay): open Recharge / Bill payments, pick the operator or biller, use the saved number/consumer ID or the one in the GOAL, choose the plan the GOAL names (ask_user listing 2-4 plans with prices if unclear), then confirm before Pay. The user enters the UPI PIN themselves — when the PIN screen appears, use ask_user to tell them to enter it and reply 'done'. "
            + "For phone settings (Quick Settings is already pulled down when the app is quick settings): tap the tile by its label (Wi-Fi, Bluetooth, Do not disturb, Airplane mode, Auto-rotate, Hotspot, Mobile data, Location, Battery saver); swipe down/scroll to expand if not visible; check the tile state changed, then done. Turning on Airplane mode, or turning off Mobile data/Wi-Fi, needs confirm first. "
            + "For messages in WhatsApp/Telegram: the chat may already be open with the text typed; check the contact name, then confirm on Send. "
            + "For email in Gmail: the compose screen may already be filled; check To/Subject/Body and use confirm on Send. "
            + "SPEED: use then whenever the next steps are predictable, e.g. type 'biryani' then tap the suggestion labelled 'Biryani'; tap a tab then tap a known button. Atlas finds each follow-up by its label on the fresh screen and stops if it isn't there; it never runs a final payment/send step as a follow-up. "
            + "Use tap_xy only for things that have no element in the list (maps, drawn buttons, popups without labels); prefer ids. Don't use look when a screenshot is already attached. "
            + "Once the user has confirmed, finish the payment screen yourself: keep the method that is already selected (or the first UPI/default option) — never ask which payment method to use unless the GOAL says to. "
            + "Use look when you need to see the screen (no screenshot this turn and the elements are unclear). Prefer the fewest steps; don't wait unless something is loading. "
            + "If an action had no effect, try a different element, scroll, or wait. Use done with a one-line summary when the goal is achieved (e.g. ride booked, order placed, or everything is ready), or fail with the reason if it truly can't be done. Element ids change every turn — only use ids from the current list.";
}
