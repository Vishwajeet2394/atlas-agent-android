package com.atlas.agent;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Everything on shared storage, plus what the AI has said about each image/PDF. */
final class FileIndex {
    static final class E {
        String path, name, kind, ext, dir; long size, mtime;
        String lname, lpath;
    }

    private static FileIndex inst;
    static synchronized FileIndex get(Context c) { if (inst == null) inst = new FileIndex(c.getApplicationContext()); return inst; }

    private final Context ctx;
    private volatile List<E> all = new ArrayList<>();
    private final Map<String, JSONObject> content = new HashMap<>();
    volatile boolean scanning = false;
    volatile long scannedAt = 0;
    volatile int scanCount = 0;
    private final List<Runnable> waiting = new ArrayList<>();
    private volatile int version = 0;
    private String statsCache = null; private int statsVersion = -1;

    private FileIndex(Context c) { ctx = c; loadContent(); }

    static File root() { return Environment.getExternalStorageDirectory(); }

    // ---------- kinds ----------
    static String ext(String name) { int i = name.lastIndexOf('.'); return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT); }
    private static final Set<String> IMG = set("jpg jpeg png webp heic heif gif bmp dng"), VID = set("mp4 mkv avi mov webm 3gp m4v ts flv wmv"),
            AUD = set("mp3 m4a aac wav flac ogg opus amr wma mid"), DOC = set("doc docx odt rtf txt md xls xlsx xlsm csv ppt pptx odp ods json xml html htm log epub"),
            ARC = set("zip rar 7z tar gz tgz bz2 xz");
    private static Set<String> set(String s) { Set<String> h = new HashSet<>(); Collections.addAll(h, s.split(" ")); return h; }
    static String kindOf(String ext) {
        if (IMG.contains(ext)) return "image";
        if ("pdf".equals(ext)) return "pdf";
        if (VID.contains(ext)) return "video";
        if (AUD.contains(ext)) return "audio";
        if (DOC.contains(ext)) return "doc";
        if ("apk".equals(ext) || "apks".equals(ext) || "xapk".equals(ext)) return "apk";
        if (ARC.contains(ext)) return "archive";
        return "other";
    }
    static String mime(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png"; case "webp": return "image/webp"; case "gif": return "image/gif";
            case "heic": case "heif": return "image/heic";
            case "pdf": return "application/pdf";
            case "mp4": case "m4v": return "video/mp4"; case "mkv": return "video/x-matroska"; case "webm": return "video/webm"; case "3gp": return "video/3gpp"; case "mov": return "video/quicktime";
            case "mp3": return "audio/mpeg"; case "m4a": case "aac": return "audio/mp4"; case "wav": return "audio/wav"; case "ogg": case "opus": return "audio/ogg"; case "flac": return "audio/flac";
            case "txt": case "log": case "md": return "text/plain"; case "csv": return "text/csv"; case "html": case "htm": return "text/html"; case "json": return "application/json";
            case "doc": return "application/msword"; case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel"; case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint"; case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "zip": return "application/zip"; case "apk": return "application/vnd.android.package-archive"; case "epub": return "application/epub+zip";
            default: return "*/*";
        }
    }
    static String human(long b) {
        if (b < 1024) return b + " B";
        double k = b / 1024.0; if (k < 1024) return String.format(Locale.US, "%.0f KB", k);
        double m = k / 1024; if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024);
    }

    // ---------- scanning ----------
    void scan(Runnable done) {
        synchronized (waiting) {
            if (done != null) waiting.add(done);
            if (scanning) return; // the running scan will call everyone back
            scanning = true;
        }
        new Thread(() -> {
            List<E> out = new ArrayList<>();
            try {
                File r = root();
                String rp = r.getAbsolutePath();
                ArrayDeque<File> st = new ArrayDeque<>();
                st.push(r);
                while (!st.isEmpty() && out.size() < 300000) {
                    File d = st.pop();
                    File[] kids = d.listFiles();
                    if (kids == null) continue;
                    for (File f : kids) {
                        String n = f.getName();
                        if (n.startsWith(".")) continue;
                        if (f.isDirectory()) {
                            String p = f.getAbsolutePath();
                            if (p.equals(rp + "/Android/data") || p.equals(rp + "/Android/obb") || p.equals(rp + "/Atlas Bin")) continue;
                            st.push(f);
                        } else out.add(entry(f));
                    }
                    scanCount = out.size();
                }
            } catch (Exception ignored) { }
            out.sort((a, b) -> Long.compare(b.mtime, a.mtime));
            all = out;
            version++;
            scannedAt = System.currentTimeMillis();
            List<Runnable> cbs;
            synchronized (waiting) { scanning = false; cbs = new ArrayList<>(waiting); waiting.clear(); }
            for (Runnable r : cbs) try { r.run(); } catch (Exception ignored) { }
        }, "atlas-scan").start();
    }

    private static E entry(File f) {
        E e = new E();
        e.path = f.getAbsolutePath(); e.name = f.getName(); e.size = f.length(); e.mtime = f.lastModified();
        e.ext = ext(e.name); e.kind = kindOf(e.ext);
        File p = f.getParentFile(); e.dir = p == null ? "" : p.getAbsolutePath();
        e.lname = e.name.toLowerCase(Locale.ROOT); e.lpath = e.path.toLowerCase(Locale.ROOT);
        return e;
    }

    private JSONObject json(E e) throws Exception {
        JSONObject o = new JSONObject().put("p", e.path).put("n", e.name).put("s", e.size).put("m", e.mtime).put("k", e.kind).put("x", e.ext)
                .put("d", rel(e.dir));
        JSONObject c;
        synchronized (content) { c = content.get(e.path); }
        if (c != null && c.optLong("m") == e.mtime) { o.put("sum", c.optString("summary")); o.put("tags", c.optJSONArray("tags")); o.put("cat", c.optString("category")); }
        return o;
    }

    static String rel(String abs) {
        String r = root().getAbsolutePath();
        if (abs.equals(r)) return "Phone";
        return abs.startsWith(r + "/") ? abs.substring(r.length() + 1) : abs;
    }

    // ---------- queries ----------
    String stats() throws Exception {
        if (!scanning && statsCache != null && statsVersion == version) return statsCache;
        Map<String, long[]> k = new HashMap<>();
        long total = 0; int analyzed = 0, todo = 0;
        Set<String> described;
        synchronized (content) { analyzed = content.size(); described = new HashSet<>(content.keySet()); }
        for (E e : all) {
            long[] v = k.computeIfAbsent(e.kind, x -> new long[2]);
            v[0]++; v[1] += e.size; total += e.size;
            String extra = e.lpath.contains("/download") ? "downloads" : null;
            if (extra != null) { long[] d = k.computeIfAbsent(extra, x -> new long[2]); d[0]++; d[1] += e.size; }
            if (e.lpath.contains("screenshot")) { long[] d = k.computeIfAbsent("screenshots", x -> new long[2]); d[0]++; d[1] += e.size; }
            if (e.lpath.contains("whatsapp")) { long[] d = k.computeIfAbsent("whatsapp", x -> new long[2]); d[0]++; d[1] += e.size; }
            if (e.lpath.contains("/dcim/")) { long[] d = k.computeIfAbsent("camera", x -> new long[2]); d[0]++; d[1] += e.size; }
            if (e.size >= 50L * 1024 * 1024) { long[] d = k.computeIfAbsent("large", x -> new long[2]); d[0]++; d[1] += e.size; }
            if (("image".equals(e.kind) || "pdf".equals(e.kind)) && !described.contains(e.path)) todo++;
        }
        JSONObject o = new JSONObject().put("files", all.size()).put("bytes", total).put("scanning", scanning).put("count", scanCount)
                .put("at", scannedAt).put("analyzed", analyzed);
        JSONObject kinds = new JSONObject();
        for (Map.Entry<String, long[]> en : k.entrySet()) kinds.put(en.getKey(), new JSONObject().put("n", en.getValue()[0]).put("b", en.getValue()[1]));
        o.put("kinds", kinds);
        // freest space
        File r = root();
        o.put("free", r.getFreeSpace()).put("cap", r.getTotalSpace()).put("todo", todo);
        // the 8 newest files, so the home screen needs one call
        JSONArray recent = new JSONArray();
        for (int i = 0; i < Math.min(8, all.size()); i++) recent.put(json(all.get(i)));
        o.put("recent", recent);
        String out = o.toString();
        if (!scanning) { statsCache = out; statsVersion = version; }
        return out;
    }

    /** kind: image|pdf|video|audio|doc|apk|archive|other|any|large|analyzed|todo. sort: recent|old|size|name|score */
    String search(String q, String kind, String sort, int offset, int limit, String folder) throws Exception {
        String[] toks = q == null || q.trim().isEmpty() ? new String[0] : q.toLowerCase(Locale.ROOT).trim().split("\\s+");
        String fold = folder == null || folder.isEmpty() ? null : folder;
        List<Object[]> hits = new ArrayList<>();
        Map<String, JSONObject> cs;
        synchronized (content) { cs = new HashMap<>(content); }
        for (E e : all) {
            if (fold != null && !e.dir.equals(fold)) continue;
            if (kind != null && !kind.isEmpty() && !"any".equals(kind)) {
                if ("large".equals(kind)) { if (e.size < 50L * 1024 * 1024) continue; }
                else if ("analyzed".equals(kind)) { if (!cs.containsKey(e.path)) continue; }
                else if ("todo".equals(kind)) { if (!("image".equals(e.kind) || "pdf".equals(e.kind)) || cs.containsKey(e.path)) continue; }
                else if ("screenshots".equals(kind)) { if (!e.lpath.contains("screenshot")) continue; }
                else if ("downloads".equals(kind)) { if (!e.lpath.contains("/download")) continue; }
                else if ("whatsapp".equals(kind)) { if (!e.lpath.contains("whatsapp")) continue; }
                else if ("camera".equals(kind)) { if (!e.lpath.contains("/dcim/")) continue; }
                else if (!kind.equals(e.kind)) continue;
            }
            double score = 0;
            if (toks.length > 0) {
                JSONObject c = cs.get(e.path);
                String meta = c == null ? "" : (c.optString("summary") + " " + c.optString("category") + " " + c.optString("text") + " " + c.optJSONArray("tags")).toLowerCase(Locale.ROOT);
                boolean ok = true;
                for (String t : toks) {
                    double s = 0;
                    if (e.lname.contains(t)) s += e.lname.startsWith(t) ? 6 : 4;
                    else if (e.lpath.contains(t)) s += 1.5;
                    if (meta.contains(t)) s += 3;
                    if (s == 0 && t.length() > 4 && t.endsWith("s")) { // plurals
                        String t2 = t.substring(0, t.length() - 1);
                        if (e.lname.contains(t2)) s += 3; else if (meta.contains(t2)) s += 2;
                    }
                    if (s == 0) { ok = false; break; }
                    score += s;
                }
                if (!ok) continue;
                score += Math.max(0, 2 - (System.currentTimeMillis() - e.mtime) / (86400000.0 * 90)); // newer first on ties
            }
            hits.add(new Object[]{e, score});
        }
        Comparator<Object[]> cmp;
        switch (sort == null ? "" : sort) {
            case "size": cmp = (a, b) -> Long.compare(((E) b[0]).size, ((E) a[0]).size); break;
            case "name": cmp = (a, b) -> ((E) a[0]).lname.compareTo(((E) b[0]).lname); break;
            case "old": cmp = (a, b) -> Long.compare(((E) a[0]).mtime, ((E) b[0]).mtime); break;
            case "recent": cmp = (a, b) -> Long.compare(((E) b[0]).mtime, ((E) a[0]).mtime); break;
            default: cmp = toks.length > 0 ? (a, b) -> Double.compare((double) b[1], (double) a[1]) : (a, b) -> Long.compare(((E) b[0]).mtime, ((E) a[0]).mtime);
        }
        boolean alreadyRecent = toks.length == 0 && (sort == null || sort.isEmpty() || "recent".equals(sort));
        if (!alreadyRecent) hits.sort(cmp);
        JSONArray items = new JSONArray();
        long bytes = 0;
        for (Object[] h : hits) bytes += ((E) h[0]).size;
        for (int i = offset; i < Math.min(hits.size(), offset + limit); i++) items.put(json((E) hits.get(i)[0]));
        return new JSONObject().put("total", hits.size()).put("bytes", bytes).put("items", items).toString();
    }

    /** Folders and files directly inside a folder (read from disk, so it is always current). */
    String listDir(String path) throws Exception {
        File d = path == null || path.isEmpty() ? root() : new File(path);
        File[] kids = d.listFiles();
        JSONArray dirs = new JSONArray(), files = new JSONArray();
        if (kids != null) {
            List<File> l = new ArrayList<>();
            Collections.addAll(l, kids);
            l.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : l) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    String[] n = f.list();
                    dirs.put(new JSONObject().put("p", f.getAbsolutePath()).put("n", f.getName()).put("c", n == null ? 0 : n.length).put("m", f.lastModified()));
                } else files.put(json(entry(f)));
            }
        }
        File par = d.getParentFile();
        boolean atRoot = d.getAbsolutePath().equals(root().getAbsolutePath());
        return new JSONObject().put("p", d.getAbsolutePath()).put("rel", rel(d.getAbsolutePath())).put("up", atRoot || par == null ? "" : par.getAbsolutePath())
                .put("dirs", dirs).put("files", files).toString();
    }

    String info(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) return new JSONObject().put("missing", true).toString();
        JSONObject o = json(entry(f));
        JSONObject c;
        synchronized (content) { c = content.get(path); }
        if (c != null) o.put("ai", c);
        if ("pdf".equals(o.optString("k"))) o.put("pages", Media.pdfPageCount(f));
        return o.toString();
    }

    /** Groups of identical files (same size, same first 1 MB and last 64 KB). */
    String duplicates() throws Exception {
        Map<Long, List<E>> bySize = new HashMap<>();
        for (E e : all) if (e.size > 20 * 1024) bySize.computeIfAbsent(e.size, x -> new ArrayList<>()).add(e);
        List<List<E>> groups = new ArrayList<>();
        for (List<E> g : bySize.values()) {
            if (g.size() < 2) continue;
            Map<String, List<E>> byHash = new HashMap<>();
            for (E e : g) { String h = quickHash(new File(e.path), e.size); if (h != null) byHash.computeIfAbsent(h, x -> new ArrayList<>()).add(e); }
            for (List<E> s : byHash.values()) if (s.size() > 1) groups.add(s);
        }
        groups.sort((a, b) -> Long.compare(b.get(0).size * (b.size() - 1), a.get(0).size * (a.size() - 1)));
        JSONArray out = new JSONArray();
        long wasted = 0;
        for (List<E> g : groups) {
            wasted += g.get(0).size * (g.size() - 1);
            if (out.length() >= 150) continue;
            g.sort((a, b) -> Long.compare(a.mtime, b.mtime)); // oldest first = the one to keep
            JSONArray arr = new JSONArray();
            for (E e : g) arr.put(json(e));
            out.put(arr);
        }
        return new JSONObject().put("groups", out).put("wasted", wasted).put("count", groups.size()).toString();
    }

    private static String quickHash(File f, long size) {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536]; int n; long read = 0;
            while (read < 1048576 && (n = in.read(buf)) > 0) { md.update(buf, 0, n); read += n; }
            if (size > 1048576 + 65536) {
                long skip = size - 65536 - read;
                while (skip > 0) { long s = in.skip(skip); if (s <= 0) break; skip -= s; }
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    // ---------- AI descriptions ----------
    private File contentFile() { return new File(ctx.getFilesDir(), "content.json"); }
    private void loadContent() {
        try {
            String s = Media.readText(contentFile(), 50_000_000);
            if (s == null || s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) content.put(names.getString(i), o.getJSONObject(names.getString(i)));
        } catch (Exception ignored) { }
    }
    private void saveContent() {
        try {
            JSONObject o;
            synchronized (content) { o = new JSONObject(); for (Map.Entry<String, JSONObject> e : content.entrySet()) o.put(e.getKey(), e.getValue()); }
            File tmp = new File(ctx.getFilesDir(), "content.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(contentFile());
        } catch (Exception ignored) { }
    }
    void putContent(String path, JSONObject c) {
        version++;
        File f = new File(path);
        try { c.put("m", f.lastModified()).put("at", System.currentTimeMillis()); } catch (Exception ignored) { }
        synchronized (content) { content.put(path, c); }
        saveContent();
    }
    void clearContent() { synchronized (content) { content.clear(); } version++; saveContent(); }

    // ---------- file operations ----------
    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int i = name.lastIndexOf('.');
        if (i > 0) { base = name.substring(0, i); ext = name.substring(i); }
        for (int n = 1; n < 1000; n++) { f = new File(dir, base + " (" + n + ")" + ext); if (!f.exists()) return f; }
        return f;
    }

    /** Moves a file (or folder) into destDir, never overwriting. Returns the new path. */
    String move(String src, String destDir) throws Exception {
        File s = new File(src), d = new File(destDir);
        if (!s.exists()) throw new Exception("File no longer exists: " + s.getName());
        if (!d.exists() && !d.mkdirs()) throw new Exception("Could not create folder " + d.getName());
        if (s.getParentFile() != null && s.getParentFile().getAbsolutePath().equals(d.getAbsolutePath())) return s.getAbsolutePath();
        File t = unique(d, s.getName());
        if (!s.renameTo(t)) {
            if (s.isDirectory()) throw new Exception("Could not move folder " + s.getName());
            try (InputStream in = new FileInputStream(s); FileOutputStream out = new FileOutputStream(t)) {
                byte[] buf = new byte[262144]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (t.length() != s.length()) { //noinspection ResultOfMethodCallIgnored
                t.delete(); throw new Exception("Copy failed for " + s.getName()); }
            //noinspection ResultOfMethodCallIgnored
            s.delete();
        }
        moved(s, t);
        return t.getAbsolutePath();
    }

    String rename(String src, String newName) throws Exception {
        File s = new File(src);
        newName = newName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (newName.isEmpty()) throw new Exception("Empty name");
        File t = unique(s.getParentFile(), newName);
        if (!s.renameTo(t)) throw new Exception("Could not rename " + s.getName());
        moved(s, t);
        return t.getAbsolutePath();
    }

    String mkdir(String path) throws Exception {
        File d = new File(path);
        if (!d.exists() && !d.mkdirs()) throw new Exception("Could not create " + d.getName());
        return d.getAbsolutePath();
    }

    static File bin() { return new File(root(), "Atlas Bin"); }

    /** "Delete" moves to the Atlas Bin folder; deleting from inside the bin removes it for good. */
    String trash(String path) throws Exception {
        File s = new File(path);
        if (s.getAbsolutePath().startsWith(bin().getAbsolutePath() + "/")) {
            if (!s.delete()) throw new Exception("Could not delete " + s.getName());
            moved(s, null);
            return "";
        }
        return move(path, bin().getAbsolutePath());
    }

    private void moved(File from, File to) {
        List<E> l = new ArrayList<>(all);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).path.equals(from.getAbsolutePath())) {
                if (to == null || to.isDirectory()) l.remove(i); else l.set(i, entry(to));
                break;
            }
        }
        all = l;
        version++;
        synchronized (content) {
            JSONObject c = content.remove(from.getAbsolutePath());
            if (c != null && to != null) content.put(to.getAbsolutePath(), c);
        }
        saveContent();
        List<String> paths = new ArrayList<>();
        paths.add(from.getAbsolutePath());
        if (to != null) paths.add(to.getAbsolutePath());
        try { MediaScannerConnection.scanFile(ctx, paths.toArray(new String[0]), null, null); } catch (Exception ignored) { }
    }
}
