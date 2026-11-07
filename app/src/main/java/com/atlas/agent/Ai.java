package com.atlas.agent;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * One request format for both providers.
 * req = {system, json:bool, maxTokens, messages:[{role:"user"|"assistant", parts:[{text}|{mime,data}|{file, as}]}]}
 * A {file} part is read here: images are scaled down, PDFs are sent whole when small enough (as:"pdf")
 * or as their first pages (as:"pages"), text files as text.
 */
final class Ai {
    private Ai() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("atlas", Context.MODE_PRIVATE); }

    static String provider(Context c) { return prefs(c).getString("provider", "gemini"); }

    static boolean ready(Context c) {
        SharedPreferences p = prefs(c);
        if ("selftest".equals(provider(c))) return true;
        return !p.getString("claude".equals(provider(c)) ? "claude_key" : "gemini_key", "").trim().isEmpty();
    }

    static String call(Context c, JSONObject req) throws Exception {
        String prov = req.optString("provider", provider(c));
        if ("selftest".equals(prov)) return selftest(req);
        JSONArray msgs = resolveFiles(c, req.getJSONArray("messages"));
        return "claude".equals(prov) ? claude(c, req, msgs) : gemini(c, req, msgs);
    }

    // ---------- files → inline parts ----------
    private static JSONArray resolveFiles(Context c, JSONArray msgs) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            JSONArray parts = m.optJSONArray("parts"), np = new JSONArray();
            if (parts == null) { parts = new JSONArray(); parts.put(new JSONObject().put("text", m.optString("text", ""))); }
            for (int j = 0; j < parts.length(); j++) {
                JSONObject p = parts.getJSONObject(j);
                if (!p.has("file")) { np.put(p); continue; }
                File f = new File(p.getString("file"));
                String name = f.getName(), ext = FileIndex.ext(name), kind = FileIndex.kindOf(ext);
                String as = p.optString("as", "auto");
                np.put(new JSONObject().put("text", "[File: " + name + ", " + FileIndex.human(f.length()) + "]"));
                if ("image".equals(kind)) {
                    String d = Media.imageB64(f, p.optInt("max", 1280));
                    if (d != null) np.put(new JSONObject().put("mime", "image/jpeg").put("data", d));
                    else np.put(new JSONObject().put("text", "(could not read this image)"));
                } else if ("pdf".equals(kind)) {
                    if (!"pages".equals(as) && f.length() < 14L * 1024 * 1024) {
                        byte[] d = Media.readAll(f);
                        if (d != null) { np.put(new JSONObject().put("mime", "application/pdf").put("data", Media.b64(d))); continue; }
                    }
                    List<String> pages = Media.pdfPages(f, p.optInt("pages", 3), 1400);
                    if (pages.isEmpty()) np.put(new JSONObject().put("text", "(could not open this PDF — it may be password protected)"));
                    int total = Media.pdfPageCount(f);
                    if (total > pages.size() && !pages.isEmpty()) np.put(new JSONObject().put("text", "(showing the first " + pages.size() + " of " + total + " pages)"));
                    for (String d : pages) np.put(new JSONObject().put("mime", "image/jpeg").put("data", d));
                } else if ("video".equals(kind) || "audio".equals(kind)) {
                    byte[] t = Media.thumb(c, f, kind, 1024);
                    if (t != null) np.put(new JSONObject().put("mime", "image/jpeg").put("data", Media.b64(t)));
                    np.put(new JSONObject().put("text", "(a " + kind + " file; only its cover frame is attached)"));
                } else {
                    String t = Media.readText(f, 40000);
                    if (t != null && looksText(t)) np.put(new JSONObject().put("text", "Contents of " + name + ":\n" + t));
                    else np.put(new JSONObject().put("text", "(binary file; contents not readable)"));
                }
            }
            out.put(new JSONObject().put("role", m.optString("role", "user")).put("parts", np));
        }
        return out;
    }

    private static boolean looksText(String s) {
        int bad = 0, n = Math.min(s.length(), 2000);
        for (int i = 0; i < n; i++) { char ch = s.charAt(i); if (ch == 0 || ch == '�') bad++; }
        return bad < n / 20 + 1;
    }

    // ---------- Gemini ----------
    private static String gemini(Context c, JSONObject req, JSONArray msgs) throws Exception {
        SharedPreferences p = prefs(c);
        String key = p.getString("gemini_key", "").trim();
        if (key.isEmpty()) throw new Exception("Add your Gemini API key in Settings first.");
        String model = p.getString("gemini_model", "gemini-3.6-flash").trim();
        if (model.isEmpty()) model = "gemini-3.6-flash";
        JSONObject body = new JSONObject();
        if (req.has("system")) body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", req.getString("system")))));
        JSONArray contents = new JSONArray();
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            JSONArray parts = new JSONArray(), src = m.getJSONArray("parts");
            for (int j = 0; j < src.length(); j++) {
                JSONObject s = src.getJSONObject(j);
                if (s.has("data")) parts.put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", s.getString("mime")).put("data", s.getString("data"))));
                else parts.put(new JSONObject().put("text", s.optString("text", "")));
            }
            contents.put(new JSONObject().put("role", "assistant".equals(m.optString("role")) ? "model" : "user").put("parts", parts));
        }
        body.put("contents", contents);
        // Gemini's thinking counts toward maxOutputTokens, so never send a small cap; keep thinking short for snappy replies.
        JSONObject gc = new JSONObject().put("maxOutputTokens", Math.max(8192, req.optInt("maxTokens", 4096)));
        if (req.optBoolean("json")) gc.put("responseMimeType", "application/json");
        String level = req.optBoolean("deep") ? null : "low";
        boolean thinkOk = p.getBoolean("gemini_thinking_ok:" + model, true);
        if (level != null && thinkOk && model.startsWith("gemini-3")) gc.put("thinkingConfig", new JSONObject().put("thinkingLevel", level));
        else if (level != null && thinkOk && model.contains("2.5-flash")) gc.put("thinkingConfig", new JSONObject().put("thinkingBudget", 0));
        body.put("generationConfig", gc);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        JSONObject r;
        try { r = post(url, body.toString(), new String[][]{{"x-goog-api-key", key}}); }
        catch (Exception e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (!gc.has("thinkingConfig") || !(m.contains("thinking") || m.contains("invalid json payload") || m.contains("unknown name"))) throw e;
            // this model doesn't accept the thinking setting — drop it and remember
            gc.remove("thinkingConfig");
            p.edit().putBoolean("gemini_thinking_ok:" + model, false).apply();
            r = post(url, body.toString(), new String[][]{{"x-goog-api-key", key}});
        }
        JSONArray cands = r.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) {
            JSONObject fb = r.optJSONObject("promptFeedback");
            throw new Exception("Gemini returned no answer" + (fb != null ? " (" + fb.optString("blockReason") + ")" : ""));
        }
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        StringBuilder sb = new StringBuilder();
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject pt = parts.getJSONObject(i);
            if (pt.optBoolean("thought")) continue;
            sb.append(pt.optString("text", ""));
        }
        if (sb.length() == 0) throw new Exception("Gemini returned an empty answer (" + cands.getJSONObject(0).optString("finishReason") + ")");
        return sb.toString();
    }

    // ---------- Claude ----------
    private static String claude(Context c, JSONObject req, JSONArray msgs) throws Exception {
        SharedPreferences p = prefs(c);
        String key = p.getString("claude_key", "").trim();
        if (key.isEmpty()) throw new Exception("Add your Claude API key in Settings first.");
        String model = p.getString("claude_model", "claude-sonnet-5-5").trim();
        if (model.isEmpty()) model = "claude-sonnet-5-5";
        JSONObject body = new JSONObject().put("model", model).put("max_tokens", req.optInt("maxTokens", 4096));
        String sys = req.optString("system", "");
        if (req.optBoolean("json")) sys += "\n\nReply with a single JSON object only — no prose, no code fences.";
        if (!sys.isEmpty()) body.put("system", sys);
        JSONArray messages = new JSONArray();
        String lastRole = null;
        for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            String role = "assistant".equals(m.optString("role")) ? "assistant" : "user";
            JSONArray content = new JSONArray(), src = m.getJSONArray("parts");
            for (int j = 0; j < src.length(); j++) {
                JSONObject s = src.getJSONObject(j);
                if (s.has("data")) {
                    boolean pdf = "application/pdf".equals(s.getString("mime"));
                    content.put(new JSONObject().put("type", pdf ? "document" : "image")
                            .put("source", new JSONObject().put("type", "base64").put("media_type", s.getString("mime")).put("data", s.getString("data"))));
                } else {
                    String t = s.optString("text", "");
                    if (!t.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", t));
                }
            }
            if (content.length() == 0) content.put(new JSONObject().put("type", "text").put("text", "(empty)"));
            if (role.equals(lastRole)) { // the API wants turns to alternate
                JSONArray prev = messages.getJSONObject(messages.length() - 1).getJSONArray("content");
                for (int k = 0; k < content.length(); k++) prev.put(content.get(k));
            } else messages.put(new JSONObject().put("role", role).put("content", content));
            lastRole = role;
        }
        body.put("messages", messages);
        JSONObject r = post("https://api.anthropic.com/v1/messages", body.toString(),
                new String[][]{{"x-api-key", key}, {"anthropic-version", "2023-06-01"}});
        JSONArray content = r.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.getJSONObject(i);
            if ("text".equals(b.optString("type"))) sb.append(b.optString("text"));
        }
        if (sb.length() == 0) throw new Exception("Claude returned an empty answer (" + r.optString("stop_reason") + ")");
        return sb.toString();
    }

    // ---------- HTTP ----------
    private static JSONObject post(String url, String body, String[][] headers) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("POST");
        h.setConnectTimeout(20000);
        h.setReadTimeout(120000);
        h.setDoOutput(true);
        h.setRequestProperty("content-type", "application/json");
        for (String[] kv : headers) h.setRequestProperty(kv[0], kv[1]);
        try (OutputStream o = h.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        String text = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            text = bo.toString("UTF-8");
        }
        h.disconnect();
        JSONObject j;
        try { j = new JSONObject(text); } catch (Exception e) { throw new Exception("HTTP " + code + ": " + (text.length() > 200 ? text.substring(0, 200) : text)); }
        if (code >= 400) {
            JSONObject err = j.optJSONObject("error");
            String msg = err != null ? err.optString("message", err.toString()) : text;
            if (code == 401 || code == 403 || msg.toLowerCase(Locale.ROOT).contains("api key")) msg = "The API key was rejected — check it in Settings. (" + msg + ")";
            if (code == 429) msg = "Rate limit reached — wait a minute and try again. (" + msg + ")";
            throw new Exception(msg);
        }
        return j;
    }

    /**
     * Offline scripted "AI" used only by the emulator self-test (provider = selftest): taps the first element whose
     * label contains the word after TAP: in the goal, then reports done. Lets CI exercise the whole agent loop.
     */
    private static String selftest(JSONObject req) throws Exception {
        JSONArray msgs = req.getJSONArray("messages");
        String u = msgs.getJSONObject(msgs.length() - 1).getJSONArray("parts").getJSONObject(0).optString("text");
        if (!u.contains("ELEMENTS:")) return "{\"say\":\"selftest ok\",\"actions\":[]}";
        String goal = u.substring(u.indexOf("GOAL:") + 5, u.indexOf('\n')).trim();
        String want = goal.contains("TAP:") ? goal.substring(goal.indexOf("TAP:") + 4).trim().toLowerCase(Locale.ROOT) : "";
        if (u.contains("RECENT ACTIONS:") && u.contains("tapped")) return "{\"thought\":\"tapped the target\",\"action\":\"done\",\"summary\":\"Self-test passed: opened " + want + "\"}";
        String[] lines = u.substring(u.indexOf("ELEMENTS:")).split("\n");
        for (String l : lines) {
            if (l.startsWith("[") && l.toLowerCase(Locale.ROOT).contains(want)) {
                String id = l.substring(1, l.indexOf(']'));
                return "{\"thought\":\"found " + want + "\",\"action\":\"tap\",\"id\":" + id + "}";
            }
        }
        return "{\"thought\":\"not visible, scrolling\",\"action\":\"scroll\",\"direction\":\"down\"}";
    }

    /** Pulls the first JSON object out of a model reply (tolerates code fences and chatter). */
    static JSONObject parseJson(String s) throws Exception {
        String t = s.trim();
        if (t.startsWith("```")) { int a = t.indexOf('\n'); int b = t.lastIndexOf("```"); if (a > 0 && b > a) t = t.substring(a + 1, b).trim(); }
        try { return new JSONObject(t); } catch (Exception ignored) { }
        int a = t.indexOf('{');
        if (a < 0) throw new Exception("No JSON in reply");
        int depth = 0; boolean str = false, esc = false;
        for (int i = a; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (str) { if (esc) esc = false; else if (ch == '\\') esc = true; else if (ch == '"') str = false; continue; }
            if (ch == '"') str = true;
            else if (ch == '{') depth++;
            else if (ch == '}') { depth--; if (depth == 0) return new JSONObject(t.substring(a, i + 1)); }
        }
        throw new Exception("Unfinished JSON in reply");
    }
}
