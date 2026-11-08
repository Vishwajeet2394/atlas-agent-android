package com.atlas.agent;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Friendly app names → package names, and launching them. */
final class Apps {
    static final Map<String, String[]> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("uber", new String[]{"com.ubercab"});
        KNOWN.put("ola", new String[]{"com.olacabs.customer"});
        KNOWN.put("rapido", new String[]{"com.rapido.passenger"});
        KNOWN.put("swiggy", new String[]{"in.swiggy.android"});
        KNOWN.put("instamart", new String[]{"in.swiggy.android.instamart", "in.swiggy.android"});
        KNOWN.put("zomato", new String[]{"com.application.zomato"});
        KNOWN.put("blinkit", new String[]{"com.grofers.customer"});
        KNOWN.put("zepto", new String[]{"com.zeptoconsumerapp"});
        KNOWN.put("gmail", new String[]{"com.google.android.gm"});
        KNOWN.put("outlook", new String[]{"com.microsoft.office.outlook"});
        KNOWN.put("whatsapp", new String[]{"com.whatsapp"});
        KNOWN.put("maps", new String[]{"com.google.android.apps.maps"});
        KNOWN.put("amazon", new String[]{"in.amazon.mShop.android.shopping"});
        KNOWN.put("flipkart", new String[]{"com.flipkart.android"});
        KNOWN.put("myntra", new String[]{"com.myntra.android"});
        KNOWN.put("meesho", new String[]{"com.meesho.supply"});
        KNOWN.put("ajio", new String[]{"com.ril.ajio"});
        KNOWN.put("nykaa", new String[]{"com.fsn.nykaa"});
        KNOWN.put("bigbasket", new String[]{"com.bigbasket.mobileapp"});
        KNOWN.put("jiomart", new String[]{"com.jpl.jiomart"});
        KNOWN.put("tata neu", new String[]{"com.tatadigital.tcp"});
        KNOWN.put("croma", new String[]{"com.croma.app"});
        KNOWN.put("dmart", new String[]{"in.dmart"});
        KNOWN.put("dominos", new String[]{"com.Dominos"});
        KNOWN.put("mcdonalds", new String[]{"com.mcdonalds.mobileapp"});
        KNOWN.put("indrive", new String[]{"sinet.startup.inDriver"});
        KNOWN.put("namma yatri", new String[]{"in.juspay.nammayatri"});
        KNOWN.put("bookmyshow", new String[]{"com.bt.bms"});
        KNOWN.put("makemytrip", new String[]{"com.makemytrip"});
        KNOWN.put("irctc", new String[]{"cris.org.in.prs.ima"});
        KNOWN.put("redbus", new String[]{"in.redbus.android"});
        KNOWN.put("goibibo", new String[]{"com.goibibo"});
        KNOWN.put("paytm", new String[]{"net.one97.paytm"});
        KNOWN.put("telegram", new String[]{"org.telegram.messenger"});
        KNOWN.put("instagram", new String[]{"com.instagram.android"});
        KNOWN.put("youtube", new String[]{"com.google.android.youtube"});
        KNOWN.put("spotify", new String[]{"com.spotify.music"});
        KNOWN.put("bhim", new String[]{"in.org.npci.upiapp"});
        KNOWN.put("phonepe", new String[]{"com.phonepe.app"});
        KNOWN.put("gpay", new String[]{"com.google.android.apps.nbu.paisa.user"});
    }

    /** Installed, enabled and openable (a disabled app still has package info, but can't be launched). */
    static boolean installed(Context c, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled && c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
        } catch (Exception e) { return false; }
    }

    /** "uber", "Swiggy Instamart", "com.ubercab" or any launcher label → an installed package, or null. */
    static String resolve(Context c, String app) {
        if (app == null) return null;
        String a = app.trim().toLowerCase(Locale.ROOT);
        if (a.isEmpty()) return null;
        if (a.contains(".") && installed(c, app.trim())) return app.trim();
        String key = a.replace("swiggy instamart", "instamart").replace("google ", "").replace(" app", "").trim();
        for (Map.Entry<String, String[]> e : KNOWN.entrySet()) {
            if (key.equals(e.getKey()) || key.contains(e.getKey())) {
                for (String p : e.getValue()) if (installed(c, p)) return p;
            }
        }
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        String best = null;
        for (ResolveInfo r : list) {
            String label = String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT);
            if (label.equals(key)) return r.activityInfo.packageName;
            if (best == null && (label.contains(key) || key.contains(label))) best = r.activityInfo.packageName;
        }
        return best;
    }

    static String label(Context c, String pkg) {
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) { return pkg; }
    }

    static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { c.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    /** Names of every app with a launcher icon (so the AI can pick any of them). */
    static String allLabels(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        JSONArray out = new JSONArray();
        java.util.TreeSet<String> seen = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            if (c.getPackageName().equals(r.activityInfo.packageName)) continue;
            seen.add(String.valueOf(r.loadLabel(pm)));
        }
        for (String l : seen) out.put(l);
        return out.toString();
    }

    /** Which of the known apps are on this phone. */
    static String installedJson(Context c) {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, String[]> e : KNOWN.entrySet()) {
            for (String p : e.getValue()) {
                if (installed(c, p)) {
                    try { out.put(new JSONObject().put("key", e.getKey()).put("pkg", p).put("label", label(c, p))); } catch (Exception ignored) { }
                    break;
                }
            }
        }
        return out.toString();
    }
}
