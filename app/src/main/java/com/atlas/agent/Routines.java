package com.atlas.agent;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Reminders and routines. A routine is a prompt Atlas runs at a set time (once, daily, weekdays, weekly, monthly).
 * When the phone is unlocked and "auto" is on, it starts right away; otherwise a notification lets you tap Run.
 */
public class Routines extends BroadcastReceiver {
    static final String FIRE = "com.atlas.agent.ROUTINE";
    static final String CH = "routines";

    static JSONArray all(Context c) { try { return new JSONArray(Ai.prefs(c).getString("routines", "[]")); } catch (Exception e) { return new JSONArray(); } }
    static void save(Context c, JSONArray a) { Ai.prefs(c).edit().putString("routines", a.toString()).apply(); }

    static String upsert(Context c, JSONObject r) throws Exception {
        JSONArray a = all(c), out = new JSONArray();
        if (!r.has("id") || r.optString("id").isEmpty()) r.put("id", Long.toString(System.currentTimeMillis(), 36));
        if (!r.has("on")) r.put("on", true);
        boolean replaced = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.getJSONObject(i);
            if (x.optString("id").equals(r.optString("id"))) { out.put(r); replaced = true; } else out.put(x);
        }
        if (!replaced) out.put(r);
        save(c, out);
        reschedule(c);
        return r.toString();
    }

    static void remove(Context c, String id) {
        JSONArray a = all(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && !x.optString("id").equals(id)) out.put(x); }
        cancel(c, id);
        save(c, out);
    }

    /** Next time this routine should fire after now, or 0. */
    static long next(JSONObject r, long now) {
        String[] hm = r.optString("time", "09:00").split(":");
        int h = parse(hm[0], 9), m = hm.length > 1 ? parse(hm[1], 0) : 0;
        String rep = r.optString("repeat", "once");
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m);
        if ("once".equals(rep)) {
            String d = r.optString("date", "");
            if (d.length() >= 10) {
                cal.set(Calendar.YEAR, parse(d.substring(0, 4), cal.get(Calendar.YEAR)));
                cal.set(Calendar.MONTH, parse(d.substring(5, 7), 1) - 1);
                cal.set(Calendar.DAY_OF_MONTH, parse(d.substring(8, 10), 1));
                return cal.getTimeInMillis() > now ? cal.getTimeInMillis() : 0;
            }
            if (cal.getTimeInMillis() <= now) cal.add(Calendar.DAY_OF_MONTH, 1);
            return cal.getTimeInMillis();
        }
        for (int i = 0; i < 400; i++) {
            if (cal.getTimeInMillis() > now && matches(r, rep, cal)) return cal.getTimeInMillis();
            cal.add(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m);
        }
        return 0;
    }

    private static boolean matches(JSONObject r, String rep, Calendar cal) {
        int dow = cal.get(Calendar.DAY_OF_WEEK) - 1; // 0 = Sunday
        switch (rep) {
            case "daily": return true;
            case "weekdays": return dow >= 1 && dow <= 5;
            case "weekends": return dow == 0 || dow == 6;
            case "weekly": {
                JSONArray d = r.optJSONArray("days");
                if (d == null || d.length() == 0) return dow == 1;
                for (int i = 0; i < d.length(); i++) if (d.optInt(i) == dow) return true;
                return false;
            }
            case "monthly": {
                int want = r.optInt("day", 1), last = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
                return cal.get(Calendar.DAY_OF_MONTH) == Math.min(want, last);
            }
            default: return false;
        }
    }

    private static int parse(String s, int d) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return d; } }

    private static PendingIntent pi(Context c, String id) {
        Intent i = new Intent(c, Routines.class).setAction(FIRE).putExtra("id", id);
        return PendingIntent.getBroadcast(c, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void cancel(Context c, String id) { ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(pi(c, id)); }

    static void reschedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        JSONArray a = all(c);
        long now = System.currentTimeMillis();
        for (int i = 0; i < a.length(); i++) {
            JSONObject r = a.optJSONObject(i);
            if (r == null) continue;
            String id = r.optString("id");
            long t = r.optBoolean("on", true) ? next(r, now) : 0;
            try { r.put("next", t); } catch (Exception ignored) { }
            if (t <= 0) { cancel(c, id); continue; }
            PendingIntent p = pi(c, id);
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p);
        }
        save(c, a);
    }

    @Override public void onReceive(Context c, Intent in) {
        String act = in.getAction();
        if (!FIRE.equals(act)) { reschedule(c); return; }
        String id = in.getStringExtra("id");
        JSONArray a = all(c);
        JSONObject r = null;
        for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && x.optString("id").equals(id)) r = x; }
        if (r == null) return;
        try {
            r.put("last", System.currentTimeMillis());
            if ("once".equals(r.optString("repeat", "once"))) r.put("on", false);
        } catch (Exception ignored) { }
        save(c, a);
        reschedule(c);
        boolean remind = "remind".equals(r.optString("kind"));
        String name = r.optString("name", remind ? "Reminder" : "Routine");
        String prompt = r.optString("prompt", "");
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean usable = pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked();
        if (!remind && r.optBoolean("auto") && usable && AgentService.on()) {
            Intent go = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("run_prompt", prompt).putExtra("routine", name);
            try { c.startActivity(go); return; } catch (Exception ignored) { }
        }
        notify(c, id, remind ? name : "Atlas routine · " + name, remind ? r.optString("text", name) : prompt, remind ? null : prompt);
    }

    static void notify(Context c, String id, String title, String text, String runPrompt) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "Reminders & routines", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (runPrompt != null) open.putExtra("run_prompt", runPrompt).putExtra("routine", title);
        PendingIntent p = PendingIntent.getActivity(c, ("n" + id).hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(p).setCategory(runPrompt == null ? Notification.CATEGORY_REMINDER : Notification.CATEGORY_RECOMMENDATION);
        if (runPrompt != null) b.addAction(new Notification.Action.Builder(null, "Run now", p).build());
        try { nm.notify(id.hashCode(), b.build()); } catch (SecurityException ignored) { }
    }
}
