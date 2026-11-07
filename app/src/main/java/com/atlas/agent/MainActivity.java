package com.atlas.agent;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.database.Cursor;
import android.util.Base64;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int VOICE = 51, PERM = 52;
    private String permId = null;
    static volatile MainActivity instance;
    private WebView web;
    private boolean pageReady = false;
    private String pendingShared = null;
    private String voiceId = null;
    private Object backCb = null;
    private boolean backOn = false;

    void runJs(String js) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); }); }

    static void agentEvent(JSONObject o) {
        MainActivity a = instance;
        if (a != null && a.pageReady) a.runJs("window.atlasAgentEvent&&atlasAgentEvent(" + o.toString() + ")");
    }

    private void cb(String id, boolean ok, String payload) {
        runJs("window.atlasCb&&atlasCb(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload == null ? "" : payload) + ")");
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        instance = this;
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder().setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/t/", p -> media(p, 360))
                .addPathHandler("/v/", p -> media(p, 1800))
                .build();
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false);
        s.setSafeBrowsingEnabled(true);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return loader.shouldInterceptRequest(r.getUrl()); }
            @Override public void onPageFinished(WebView v, String url) { pageReady = true; deliverShared(); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                return true;
            }
        });
        handleIntent(getIntent());
        web.loadUrl("https://" + HOST + "/assets/index.html");
        Routines.reschedule(this);
        if (hasFiles()) FileIndex.get(this).scan(() -> runJs("window.atlasIndexed&&atlasIndexed()"));
    }

    /** /t/<base64url path> → thumbnail, /v/… → large preview. Runs off the UI thread. */
    private WebResourceResponse media(String p, int size) {
        try {
            String enc = p.endsWith(".jpg") ? p.substring(0, p.length() - 4) : p;
            String path = new String(Base64.decode(enc, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
            File f = new File(path);
            String kind = FileIndex.kindOf(FileIndex.ext(f.getName()));
            byte[] d = Media.thumb(this, f, kind, size);
            if (d == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new java.util.HashMap<>(), new ByteArrayInputStream(new byte[0]));
            WebResourceResponse r = new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(d));
            java.util.HashMap<String, String> h = new java.util.HashMap<>();
            h.put("Cache-Control", "max-age=86400");
            r.setResponseHeaders(h);
            return r;
        } catch (Exception e) { return null; }
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String goTab = i.getStringExtra("go");
        if (goTab != null) {
            try { pendingShared = new JSONObject().put("tab", goTab).toString(); } catch (Exception ignored) { }
            i.removeExtra("go");
            deliverShared();
        }
        // Emulator self-test (CI only): works only while no AI key has been saved, so it can't touch a real setup.
        String st = i.getStringExtra("selftest");
        if (st != null) {
            i.removeExtra("selftest");
            android.content.SharedPreferences p = Ai.prefs(this);
            if (p.getString("gemini_key", "").isEmpty() && p.getString("claude_key", "").isEmpty()) {
                p.edit().putString("provider", "selftest").apply();
                try {
                    JSONArray tasks = new JSONArray().put(new JSONObject().put("app", i.getStringExtra("app") == null ? "settings" : i.getStringExtra("app")).put("goal", st));
                    new Handler(Looper.getMainLooper()).postDelayed(() -> AgentService.run(tasks), 1500);
                } catch (Exception ignored) { }
            }
        }
        String rp = i.getStringExtra("run_prompt");
        if (rp != null) {
            try { pendingShared = new JSONObject().put("run", rp).put("routine", i.getStringExtra("routine")).toString(); } catch (Exception ignored) { }
            i.removeExtra("run_prompt");
            deliverShared();
            return;
        }
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            String text = i.getStringExtra(Intent.EXTRA_TEXT);
            try {
                JSONObject o = new JSONObject();
                if (u != null) { String path = copyIn(u); if (path != null) o.put("path", path); }
                if (text != null) o.put("text", text);
                pendingShared = o.toString();
            } catch (Exception ignored) { }
            deliverShared();
        } else if (Intent.ACTION_ASSIST.equals(i.getAction())) {
            pendingShared = "{\"assist\":true}";
            deliverShared();
        }
    }

    private String copyIn(Uri u) {
        try {
            String name = "shared";
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) name = c.getString(0);
            }
            File d = new File(getCacheDir(), "shared"); //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            File f = new File(d, name.replaceAll("[\\\\/]", "_"));
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(f)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            return f.getAbsolutePath();
        } catch (Exception e) { return null; }
    }

    private void deliverShared() {
        if (!pageReady || pendingShared == null) return;
        String s = pendingShared; pendingShared = null;
        runJs("window.atlasShared&&atlasShared(" + s + ")");
    }

    private boolean hasFiles() { return Environment.isExternalStorageManager(); }

    @Override protected void onResume() { super.onResume(); AgentService.hideIfIdle(); if (pageReady) runJs("window.atlasResume&&atlasResume()"); }

    @Override protected void onDestroy() { if (instance == this) instance = null; super.onDestroy(); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == VOICE) {
            String id = voiceId; voiceId = null; if (id == null) return;
            String text = "";
            if (res == RESULT_OK && data != null) { ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS); if (r != null && !r.isEmpty()) text = r.get(0); }
            cb(id, !text.isEmpty(), text);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != PERM || permId == null) return;
        boolean ok = res.length > 0;
        for (int r : res) if (r != android.content.pm.PackageManager.PERMISSION_GRANTED) ok = false;
        String id = permId; permId = null;
        cb(id, ok, ok ? "granted" : "denied");
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.atlasBack?String(atlasBack()):'false'", v -> {
            if (!"\"true\"".equals(v) && !"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    private void setBack(boolean on) {
        if (Build.VERSION.SDK_INT < 33 || on == backOn) return;
        backOn = on;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (backCb == null) backCb = (OnBackInvokedCallback) () -> runJs("window.atlasBack&&atlasBack()");
        if (on) d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCb);
        else d.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCb);
    }

    private static String permName(String n) {
        switch (n) {
            case "contacts": return android.Manifest.permission.READ_CONTACTS;
            case "call": return android.Manifest.permission.CALL_PHONE;
            case "sms": return android.Manifest.permission.SEND_SMS;
            case "notify": return Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.POST_NOTIFICATIONS : null;
            default: return null;
        }
    }

    private Uri uriFor(File f) {
        if (f.getAbsolutePath().startsWith(getCacheDir().getAbsolutePath()) || f.getAbsolutePath().startsWith(FileIndex.root().getAbsolutePath()))
            return FileProvider.getUriForFile(this, "com.atlas.agent.files", f);
        return Uri.fromFile(f);
    }

    private void bg(String id, Job j) {
        new Thread(() -> {
            try { cb(id, true, j.run()); }
            catch (Exception e) { cb(id, false, e.getMessage() == null ? e.toString() : e.getMessage()); }
        }).start();
    }
    interface Job { String run() throws Exception; }

    class Bridge {
        // ---------- settings ----------
        @JavascriptInterface public String prefs() {
            try {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, ?> e : Ai.prefs(MainActivity.this).getAll().entrySet()) o.put(e.getKey(), e.getValue());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void setPref(String k, String v) { Ai.prefs(MainActivity.this).edit().putString(k, v).apply(); }
        @JavascriptInterface public void setBool(String k, boolean v) { Ai.prefs(MainActivity.this).edit().putBoolean(k, v).apply(); }
        @JavascriptInterface public void setInt(String k, int v) { Ai.prefs(MainActivity.this).edit().putInt(k, v).apply(); }
        @JavascriptInterface public String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; } }

        // ---------- permissions ----------
        @JavascriptInterface public boolean hasFiles() { return MainActivity.this.hasFiles(); }
        @JavascriptInterface public void askFiles() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
            });
        }
        @JavascriptInterface public boolean agentOn() { return AgentService.on(); }
        @JavascriptInterface public void openAccessibility() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                Bundle b = new Bundle();
                String comp = new ComponentName(getPackageName(), AgentService.class.getName()).flattenToString();
                b.putString(":settings:fragment_args_key", comp);
                i.putExtra(":settings:fragment_args_key", comp);
                i.putExtra(":settings:show_fragment_args", b);
                startActivity(i);
            });
        }
        @JavascriptInterface public void openAppInfo() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        }
        // ---------- everyday phone actions ----------
        @JavascriptInterface public String device(String json) { try { return Device.run(MainActivity.this, new JSONObject(json)).toString(); } catch (Exception e) { return "{\"ok\":false,\"text\":\"bad request\"}"; } }
        @JavascriptInterface public String contacts(String q) { try { return Device.findContacts(MainActivity.this, q); } catch (Exception e) { return "{\"perm\":true,\"items\":[]}"; } }
        @JavascriptInterface public boolean hasPerm(String name) { String p = permName(name); return p == null || Device.has(MainActivity.this, p); }
        @JavascriptInterface public void askPerm(String name, String id) {
            String p = permName(name);
            if (p == null || Device.has(MainActivity.this, p)) { cb(id, true, "granted"); return; }
            runOnUiThread(() -> { permId = id; requestPermissions(new String[]{p}, PERM); });
        }
        @JavascriptInterface public boolean notifAccess() { return NotifListener.allowed(MainActivity.this); }
        @JavascriptInterface public void openNotifAccess() { runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))); }
        @JavascriptInterface public String notifications(int hours) { return NotifListener.recent(System.currentTimeMillis() - hours * 3600000L); }
        @JavascriptInterface public String routines() { return Routines.all(MainActivity.this).toString(); }
        @JavascriptInterface public String saveRoutine(String json) { try { return Routines.upsert(MainActivity.this, new JSONObject(json)); } catch (Exception e) { return "{}"; } }
        @JavascriptInterface public void deleteRoutine(String id) { Routines.remove(MainActivity.this, id); }
        @JavascriptInterface public String apps() { return Apps.installedJson(MainActivity.this); }
        @JavascriptInterface public String allApps() { return Apps.allLabels(MainActivity.this); }

        // ---------- look ----------
        @JavascriptInterface public void setBars(String color, boolean dark) {
            runOnUiThread(() -> {
                try {
                    Window w = getWindow(); int c = Color.parseColor(color);
                    w.setStatusBarColor(c); w.setNavigationBarColor(c);
                    View d = w.getDecorView(); int f = d.getSystemUiVisibility();
                    if (dark) f &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                    else f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    d.setSystemUiVisibility(f);
                } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void setCanGoBack(boolean on) { runOnUiThread(() -> setBack(on)); }
        @JavascriptInterface public void haptic(int ms) {
            try { Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE); if (v != null) v.vibrate(VibrationEffect.createOneShot(Math.max(5, ms), VibrationEffect.DEFAULT_AMPLITUDE)); } catch (Exception ignored) { }
        }
        @JavascriptInterface public String dynamicColors() {
            try {
                JSONObject o = new JSONObject();
                String[][] pals = {{"a1", "system_accent1_"}, {"a2", "system_accent2_"}, {"a3", "system_accent3_"}, {"n1", "system_neutral1_"}, {"n2", "system_neutral2_"}};
                int[] steps = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
                for (String[] p : pals) {
                    JSONObject t = new JSONObject();
                    for (int s : steps) {
                        int id = getResources().getIdentifier(p[1] + s, "color", "android");
                        if (id == 0) return null;
                        t.put(String.valueOf(s), String.format("#%06X", 0xFFFFFF & getColor(id)));
                    }
                    o.put(p[0], t);
                }
                return o.toString();
            } catch (Exception e) { return null; }
        }
        @JavascriptInterface public void voice(String id) {
            runOnUiThread(() -> {
                voiceId = id;
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "What should Atlas do?");
                try { startActivityForResult(i, VOICE); } catch (Exception e) { voiceId = null; cb(id, false, "Voice typing isn't available"); }
            });
        }

        // ---------- files ----------
        @JavascriptInterface public void scan(String id) { FileIndex.get(MainActivity.this).scan(() -> cb(id, true, "ok")); }
        @JavascriptInterface public String stats() { try { return FileIndex.get(MainActivity.this).stats(); } catch (Exception e) { return "{}"; } }
        @JavascriptInterface public String search(String q, String kind, String sort, int offset, int limit, String folder) {
            try { return FileIndex.get(MainActivity.this).search(q, kind, sort, offset, limit, folder); } catch (Exception e) { return "{\"total\":0,\"items\":[]}"; }
        }
        @JavascriptInterface public String listDir(String path) { try { return FileIndex.get(MainActivity.this).listDir(path); } catch (Exception e) { return "{\"dirs\":[],\"files\":[]}"; } }
        @JavascriptInterface public String info(String path) { try { return FileIndex.get(MainActivity.this).info(path); } catch (Exception e) { return "{}"; } }
        @JavascriptInterface public String root() { return FileIndex.root().getAbsolutePath(); }
        @JavascriptInterface public void duplicates(String id) { bg(id, () -> FileIndex.get(MainActivity.this).duplicates()); }
        @JavascriptInterface public String move(String src, String dest) { return op(() -> FileIndex.get(MainActivity.this).move(src, dest)); }
        @JavascriptInterface public String rename(String src, String name) { return op(() -> FileIndex.get(MainActivity.this).rename(src, name)); }
        @JavascriptInterface public String mkdir(String path) { return op(() -> FileIndex.get(MainActivity.this).mkdir(path)); }
        @JavascriptInterface public String trash(String path) { return op(() -> FileIndex.get(MainActivity.this).trash(path)); }
        @JavascriptInterface public void putContent(String path, String json) { try { FileIndex.get(MainActivity.this).putContent(path, new JSONObject(json)); } catch (Exception ignored) { } }
        @JavascriptInterface public void clearContent() { FileIndex.get(MainActivity.this).clearContent(); }
        private String op(Job j) {
            try { return new JSONObject().put("ok", true).put("path", j.run()).toString(); }
            catch (Exception e) { try { return new JSONObject().put("ok", false).put("error", String.valueOf(e.getMessage())).toString(); } catch (Exception x) { return "{\"ok\":false}"; } }
        }
        @JavascriptInterface public void open(String path) {
            runOnUiThread(() -> {
                try {
                    File f = new File(path);
                    Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uriFor(f), FileIndex.mime(FileIndex.ext(f.getName())))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(Intent.createChooser(i, "Open with"));
                } catch (Exception e) { runJs("window.toast&&toast('No app can open this file')"); }
            });
        }
        @JavascriptInterface public void share(String pathsJson) {
            runOnUiThread(() -> {
                try {
                    JSONArray a = new JSONArray(pathsJson);
                    ArrayList<Uri> uris = new ArrayList<>();
                    for (int k = 0; k < a.length(); k++) uris.add(uriFor(new File(a.getString(k))));
                    Intent i = uris.size() == 1 ? new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0))
                            : new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    i.setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Share"));
                } catch (Exception ignored) { }
            });
        }

        // ---------- AI ----------
        @JavascriptInterface public boolean aiReady() { return Ai.ready(MainActivity.this); }
        @JavascriptInterface public void ai(String reqJson, String id) { bg(id, () -> Ai.call(MainActivity.this, new JSONObject(reqJson))); }

        // ---------- agent ----------
        @JavascriptInterface public boolean agentBusy() { return AgentService.isBusy(); }
        @JavascriptInterface public String runTasks(String tasksJson) {
            if (!AgentService.on()) return "off";
            if (!Ai.ready(MainActivity.this)) return "nokey";
            try { return AgentService.run(new JSONArray(tasksJson)) ? "ok" : "busy"; } catch (Exception e) { return "error"; }
        }
        @JavascriptInterface public void stopAgent() { AgentService.requestStop(); }
        @JavascriptInterface public String agentLog() { return AgentService.log(); }
        @JavascriptInterface public boolean launch(String app) { String p = Apps.resolve(MainActivity.this, app); return p != null && Apps.launch(MainActivity.this, p); }

        /** Opens a filled-in compose screen. When send is true and the agent is on, the agent taps Send for you. */
        @JavascriptInterface public String email(String to, String cc, String subject, String body, String attachJson, String app, boolean send) {
            try {
                JSONArray a = new JSONArray(attachJson == null || attachJson.isEmpty() ? "[]" : attachJson);
                ArrayList<Uri> uris = new ArrayList<>();
                for (int k = 0; k < a.length(); k++) { File f = new File(a.getString(k)); if (f.exists()) uris.add(uriFor(f)); }
                Intent i;
                if (uris.size() > 1) i = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                else { i = new Intent(Intent.ACTION_SEND); if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0)); }
                i.setType(uris.isEmpty() ? "message/rfc822" : "*/*");
                String[] tos = to == null || to.trim().isEmpty() ? new String[0] : to.split("\\s*[,;]\\s*");
                i.putExtra(Intent.EXTRA_EMAIL, tos);
                if (cc != null && !cc.trim().isEmpty()) i.putExtra(Intent.EXTRA_CC, cc.split("\\s*[,;]\\s*"));
                i.putExtra(Intent.EXTRA_SUBJECT, subject);
                i.putExtra(Intent.EXTRA_TEXT, body);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                String pkg = Apps.resolve(MainActivity.this, app == null || app.isEmpty() ? "gmail" : app);
                if (pkg != null) i.setPackage(pkg);
                final Intent go = pkg != null ? i : Intent.createChooser(i, "Send with");
                runOnUiThread(() -> { try { startActivity(go); } catch (Exception e) { runJs("window.toast&&toast('No mail app found')"); } });
                if (send && pkg != null && AgentService.on() && Ai.ready(MainActivity.this)) {
                    String name = Apps.label(MainActivity.this, pkg);
                    JSONObject t = new JSONObject().put("app", name).put("nolaunch", true).put("allow", "send")
                            .put("goal", "The " + name + " compose screen is open with a drafted email to " + to + " with subject \"" + subject + "\". "
                                    + "Check the To field contains " + to + " (type it in if empty and pick the suggestion), then tap Send. The user has already approved sending this email — do not ask again. Then use done.");
                    new Handler(Looper.getMainLooper()).postDelayed(() -> AgentService.run(new JSONArray().put(t)), 2500);
                    return "agent";
                }
                return "opened";
            } catch (Exception e) { return "error:" + e.getMessage(); }
        }
    }
}
