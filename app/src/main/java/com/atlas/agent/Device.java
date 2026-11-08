package com.atlas.agent;

import android.Manifest;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Everyday phone actions that don't need the screen agent. Each returns {ok, text} for the chat. */
final class Device {
    private Device() { }
    private static boolean torch = false;

    static JSONObject res(boolean ok, String text) { try { return new JSONObject().put("ok", ok).put("text", text); } catch (Exception e) { return new JSONObject(); } }

    static boolean has(Context c, String perm) { return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    private static boolean start(Context c, Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    static JSONObject run(Context c, JSONObject a) {
        String op = a.optString("op");
        try {
            switch (op) {
                case "alarm": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour", 7)).putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute", 0))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    if (!a.optString("label").isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"));
                    JSONArray d = a.optJSONArray("days");
                    if (d != null && d.length() > 0) {
                        ArrayList<Integer> days = new ArrayList<>();
                        for (int k = 0; k < d.length(); k++) days.add(d.optInt(k) + 1); // 0=Sun → Calendar.SUNDAY=1
                        i.putExtra(AlarmClock.EXTRA_DAYS, days);
                    }
                    return res(start(c, i), String.format(Locale.US, "Alarm set for %d:%02d %s", ((a.optInt("hour", 7) + 11) % 12) + 1, a.optInt("minute", 0), a.optInt("hour", 7) < 12 ? "AM" : "PM"));
                }
                case "timer": {
                    int s = Math.max(1, a.optInt("seconds", 60));
                    Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    if (!a.optString("label").isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"));
                    return res(start(c, i), "Timer started for " + (s >= 60 ? (s / 60) + " min" + (s % 60 > 0 ? " " + s % 60 + " s" : "") : s + " s"));
                }
                case "show_alarms": return res(start(c, new Intent(AlarmClock.ACTION_SHOW_ALARMS)), "Opened your alarms");
                case "event": {
                    Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                            .putExtra(CalendarContract.Events.TITLE, a.optString("title"));
                    long st = parseTime(a.optString("start")), en = parseTime(a.optString("end"));
                    if (st > 0) i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, st);
                    if (en > 0) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, en); else if (st > 0) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, st + 3600000);
                    if (!a.optString("location").isEmpty()) i.putExtra(CalendarContract.Events.EVENT_LOCATION, a.optString("location"));
                    if (!a.optString("notes").isEmpty()) i.putExtra(CalendarContract.Events.DESCRIPTION, a.optString("notes"));
                    return res(start(c, i), "Calendar opened with the event filled in — tap Save");
                }
                case "navigate": {
                    String mode = a.optString("mode", "d"); // d drive, w walk, b bike, l two-wheeler, transit via maps url
                    Uri u = "transit".equals(mode) ? Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=transit&destination=" + Uri.encode(a.optString("to")))
                            : Uri.parse("google.navigation:q=" + Uri.encode(a.optString("to")) + "&mode=" + mode);
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    if (Apps.installed(c, "com.google.android.apps.maps")) i.setPackage("com.google.android.apps.maps");
                    return res(start(c, i), "Navigating to " + a.optString("to"));
                }
                case "map": {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.optString("query"))));
                    return res(start(c, i), "Showing " + a.optString("query") + " on the map");
                }
                case "play": {
                    String q = a.optString("query"), app = a.optString("app", "").toLowerCase(Locale.ROOT);
                    if (app.contains("youtube") && !app.contains("music")) {
                        Intent i = new Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(SearchManager.QUERY, q);
                        if (start(c, i)) return res(true, "Searching YouTube for " + q);
                        return res(start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))), "Searching YouTube for " + q);
                    }
                    Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, q)
                            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
                    String pkg = app.contains("spotify") ? "com.spotify.music" : app.contains("music") ? "com.google.android.apps.youtube.music" : app.isEmpty() ? null : Apps.resolve(c, app);
                    if (pkg == null && Apps.installed(c, "com.spotify.music")) pkg = "com.spotify.music";
                    if (pkg != null) i.setPackage(pkg);
                    return res(start(c, i), "Playing " + q + (pkg != null ? " on " + Apps.label(c, pkg) : ""));
                }
                case "flashlight": {
                    CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
                    String id = null;
                    for (String cid : cm.getCameraIdList()) { Boolean f = cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE); if (f != null && f) { id = cid; break; } }
                    if (id == null) return res(false, "No flashlight on this phone");
                    boolean on = a.has("on") ? a.optBoolean("on") : !torch;
                    cm.setTorchMode(id, on); torch = on;
                    return res(true, "Flashlight " + (on ? "on" : "off"));
                }
                case "volume": {
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    String s = a.optString("stream", "media");
                    int st = "ring".equals(s) ? AudioManager.STREAM_RING : "alarm".equals(s) ? AudioManager.STREAM_ALARM : "call".equals(s) ? AudioManager.STREAM_VOICE_CALL : "notification".equals(s) ? AudioManager.STREAM_NOTIFICATION : AudioManager.STREAM_MUSIC;
                    int max = am.getStreamMaxVolume(st), pct = Math.max(0, Math.min(100, a.optInt("percent", 50)));
                    am.setStreamVolume(st, Math.round(max * pct / 100f), AudioManager.FLAG_SHOW_UI);
                    return res(true, cap(s) + " volume " + pct + "%");
                }
                case "ringer": {
                    AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                    String m = a.optString("mode", "normal");
                    try {
                        am.setRingerMode("vibrate".equals(m) ? AudioManager.RINGER_MODE_VIBRATE : "silent".equals(m) ? AudioManager.RINGER_MODE_SILENT : AudioManager.RINGER_MODE_NORMAL);
                        return res(true, "Phone set to " + m);
                    } catch (SecurityException e) {
                        start(c, new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                        return res(false, "Android needs Do Not Disturb access for silent mode — allow Atlas on the screen that opened, then ask again");
                    }
                }
                case "dnd": {
                    android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (!nm.isNotificationPolicyAccessGranted()) return res(false, "needs-access");
                    boolean on = a.optBoolean("on", true);
                    nm.setInterruptionFilter(on ? android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY : android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
                    return res(true, "Do Not Disturb " + (on ? "on" : "off"));
                }
                case "settings": {
                    String p = a.optString("panel", "");
                    String act;
                    switch (p) {
                        case "wifi": act = Settings.Panel.ACTION_WIFI; break;
                        case "internet": case "data": act = Settings.Panel.ACTION_INTERNET_CONNECTIVITY; break;
                        case "volume": act = Settings.Panel.ACTION_VOLUME; break;
                        case "nfc": act = Settings.Panel.ACTION_NFC; break;
                        case "bluetooth": act = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                        case "battery": act = Intent.ACTION_POWER_USAGE_SUMMARY; break;
                        case "display": case "brightness": act = Settings.ACTION_DISPLAY_SETTINGS; break;
                        case "location": act = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
                        case "dnd": act = Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS; break;
                        case "hotspot": act = "android.settings.TETHER_SETTINGS"; break;
                        case "storage": act = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
                        case "apps": act = Settings.ACTION_APPLICATION_SETTINGS; break;
                        case "sound": act = Settings.ACTION_SOUND_SETTINGS; break;
                        case "date": act = Settings.ACTION_DATE_SETTINGS; break;
                        default: act = Settings.ACTION_SETTINGS;
                    }
                    return res(start(c, new Intent(act)), "Opened " + (p.isEmpty() ? "Settings" : p + " settings"));
                }
                case "battery": {
                    Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    int lvl = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b == null ? 100 : b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int status = b == null ? -1 : b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    boolean ch = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                    float temp = b == null ? 0 : b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                    return res(true, "Battery " + Math.round(lvl * 100f / sc) + "%" + (ch ? ", charging" : "") + String.format(Locale.US, " · %.0f°C", temp));
                }
                case "web": {
                    String q = a.optString("query"), url = a.optString("url");
                    if (!url.isEmpty()) return res(start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(url.startsWith("http") ? url : "https://" + url))), "Opened " + url);
                    Intent i = new Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q);
                    if (start(c, i)) return res(true, "Searching the web for " + q);
                    return res(start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))), "Searching the web for " + q);
                }
                case "screenshot": case "lock": case "notifications_shade": case "quick_settings": case "recents": case "home": case "back": case "power_menu": {
                    AgentService s = AgentService.I;
                    if (s == null) return res(false, "Turn on the Atlas agent for this");
                    int g = "screenshot".equals(op) ? 9 /* GLOBAL_ACTION_TAKE_SCREENSHOT */ : "lock".equals(op) ? 8 /* LOCK_SCREEN */ : "notifications_shade".equals(op) ? 4 : "quick_settings".equals(op) ? 5 : "recents".equals(op) ? 3 : "home".equals(op) ? 2 : "back".equals(op) ? 1 : 6;
                    boolean ok = s.global(g, "screenshot".equals(op) ? 700 : 0);
                    return res(ok, "screenshot".equals(op) ? "Screenshot taken" : "lock".equals(op) ? "Phone locked" : "Done");
                }
                case "call": {
                    String num = a.optString("number");
                    if (num.isEmpty()) return res(false, "No number");
                    boolean direct = has(c, Manifest.permission.CALL_PHONE);
                    Intent i = new Intent(direct ? Intent.ACTION_CALL : Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num)));
                    return res(start(c, i), direct ? "Calling " + a.optString("name", num) : "Dialer opened — tap call");
                }
                case "sms": {
                    String num = a.optString("number"), text = a.optString("text");
                    if (has(c, Manifest.permission.SEND_SMS) && !num.isEmpty()) {
                        SmsManager sm = c.getSystemService(SmsManager.class);
                        ArrayList<String> parts = sm.divideMessage(text);
                        sm.sendMultipartTextMessage(num, null, parts, null, null);
                        return res(true, "SMS sent to " + a.optString("name", num));
                    }
                    Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))).putExtra("sms_body", text);
                    return res(start(c, i), "Messages opened with your text — tap send");
                }
                case "whatsapp": {
                    String num = a.optString("number").replaceAll("[^0-9+]", "");
                    if (num.startsWith("+")) num = num.substring(1);
                    else if (num.length() == 10) num = "91" + num;
                    else if (num.startsWith("0") && num.length() == 11) num = "91" + num.substring(1);
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + num + "&text=" + Uri.encode(a.optString("text"))));
                    String pkg = Apps.installed(c, "com.whatsapp") ? "com.whatsapp" : Apps.installed(c, "com.whatsapp.w4b") ? "com.whatsapp.w4b" : null;
                    if (pkg == null) return res(false, "WhatsApp isn't installed");
                    i.setPackage(pkg);
                    return res(start(c, i), "WhatsApp chat opened with your message");
                }
                case "open_app": {
                    String p = Apps.resolve(c, a.optString("app"));
                    return res(p != null && Apps.launch(c, p), p != null ? "Opened " + Apps.label(c, p) : a.optString("app") + " isn't installed");
                }
                default: return res(false, "Unknown action " + op);
            }
        } catch (Exception e) { return res(false, String.valueOf(e.getMessage())); }
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** "2026-11-08T09:30" (local) or epoch millis. */
    static long parseTime(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Long.parseLong(s); } catch (Exception ignored) { }
        String[] fmts = {"yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"};
        String t = s.replaceAll("(Z|[+-]\\d\\d:?\\d\\d)$", "");
        for (String f : fmts) { try { Date d = new SimpleDateFormat(f, Locale.US).parse(t); if (d != null) return d.getTime(); } catch (Exception ignored) { } }
        return 0;
    }

    /** Contacts whose name contains the query → [{name, number, type}] */
    static String findContacts(Context c, String q) throws Exception {
        JSONArray out = new JSONArray();
        if (!has(c, Manifest.permission.READ_CONTACTS)) return new JSONObject().put("perm", false).toString();
        Map<String, JSONObject> seen = new LinkedHashMap<>();
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED, ContactsContract.CommonDataKinds.Phone.STARRED};
        try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", new String[]{"%" + q + "%"},
                ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, " + ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {
            while (cur != null && cur.moveToNext() && seen.size() < 8) {
                String name = cur.getString(0), num = cur.getString(1);
                if (num == null) continue;
                String key = name + "|" + num.replaceAll("[^0-9]", "").replaceAll("^(91|0)", "");
                if (seen.containsKey(key)) continue;
                int type = cur.getInt(2);
                String tl = type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE ? "mobile" : type == ContactsContract.CommonDataKinds.Phone.TYPE_HOME ? "home" : type == ContactsContract.CommonDataKinds.Phone.TYPE_WORK ? "work" : "phone";
                seen.put(key, new JSONObject().put("name", name).put("number", num).put("type", tl).put("starred", cur.getInt(4) == 1));
            }
        }
        for (JSONObject o : seen.values()) out.put(o);
        return new JSONObject().put("perm", true).put("items", out).toString();
    }
}
