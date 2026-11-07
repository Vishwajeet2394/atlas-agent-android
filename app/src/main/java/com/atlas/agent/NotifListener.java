package com.atlas.agent;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;

/** Keeps the last few hundred notifications (in memory only) so Atlas can summarise "what did I miss?". */
public class NotifListener extends NotificationListenerService {
    private static final ArrayDeque<JSONObject> RECENT = new ArrayDeque<>();

    static boolean allowed(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(c, NotifListener.class).flattenToString());
    }

    @Override public void onListenerConnected() {
        try { for (StatusBarNotification s : getActiveNotifications()) add(s); } catch (Exception ignored) { }
    }

    @Override public void onNotificationPosted(StatusBarNotification s) { add(s); }

    private void add(StatusBarNotification s) {
        try {
            if (s.isOngoing() || getPackageName().equals(s.getPackageName())) return;
            Notification n = s.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle e = n.extras;
            CharSequence title = e.getCharSequence(Notification.EXTRA_TITLE), text = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (text == null) text = e.getCharSequence(Notification.EXTRA_TEXT);
            if (title == null && text == null) return;
            JSONObject o = new JSONObject().put("app", Apps.label(this, s.getPackageName())).put("pkg", s.getPackageName())
                    .put("title", title == null ? "" : title.toString()).put("text", text == null ? "" : text.toString()).put("t", s.getPostTime()).put("key", s.getKey());
            synchronized (RECENT) {
                RECENT.removeIf(x -> x.optString("key").equals(o.optString("key")));
                RECENT.addLast(o);
                while (RECENT.size() > 400) RECENT.removeFirst();
            }
        } catch (Exception ignored) { }
    }

    static String recent(long sinceMs) {
        JSONArray a = new JSONArray();
        synchronized (RECENT) { for (JSONObject o : RECENT) if (o.optLong("t") >= sinceMs) a.put(o); }
        return a.toString();
    }
}
