package com.atlas.sim;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replica of a popular Indian app's flow, for testing Atlas on an emulator. One codebase, built once per app with the
 * real package name. Built the way agents find hard: unlabeled cards, repeated ADD buttons, fake search bars, popups,
 * auto-scrolling carousels, customisation sheets, replace-cart dialogs, UPI PIN screens.
 * Outcomes are written to logcat with tag AtlasSim so tests can check exactly what was ordered or sent.
 */
public class SimActivity extends Activity {
    static final String T = "AtlasSim";
    final String APP = BuildConfig.APP;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayDeque<Runnable> back = new ArrayDeque<>();
    Runnable current;
    String cfg = "";
    // cart: item key → qty; item key → price
    final LinkedHashMap<String, Integer> cart = new LinkedHashMap<>();
    final Map<String, Integer> price = new LinkedHashMap<>();
    String cartShop = null;
    boolean groceryMode;

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences("sim", MODE_PRIVATE);
        Intent in = getIntent();
        if (in != null && in.hasExtra("sim_config")) { // test setup: remember the scenario and quit
            String c = in.getStringExtra("sim_config"); if (c.startsWith("x")) c = c.substring(1); // "x" prefix keeps empty configs from vanishing on the adb command line
            p.edit().putString("cfg", c).commit();
            log("CONFIG", new JSONObject());
            finish();
            return;
        }
        cfg = "," + p.getString("cfg", "") + ",";
        getWindow().setStatusBarColor(Color.WHITE);
        groceryMode = APP.equals("instamart") || APP.equals("blinkit") || APP.equals("zepto");
        if (in != null && Intent.ACTION_SEND.equals(in.getAction()) || in != null && Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) { go(() -> compose(in)); return; }
        if (in != null && Intent.ACTION_VIEW.equals(in.getAction()) && in.getData() != null) { go(() -> waChat(in.getData())); return; }
        if (has("login")) { go(this::login); return; }
        go(this::home);
        if (has("popup")) ui.postDelayed(this::offerPopup, 900);
    }

    boolean has(String k) { return cfg.contains("," + k + ","); }

    void log(String what, JSONObject o) {
        try { o.put("app", APP); } catch (Exception ignored) { }
        Log.i(T, what + " " + o);
    }

    void go(Runnable screen) { if (current != null) back.push(current); current = screen; screen.run(); }
    void replace(Runnable screen) { current = screen; screen.run(); }
    @Override public void onBackPressed() { if (back.isEmpty()) { super.onBackPressed(); return; } current = back.pop(); current.run(); }

    // ------------------------------------------------------------------ tiny UI kit
    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    int brand() {
        switch (APP) {
            case "swiggy": return 0xFFFC8019; case "zomato": return 0xFFE23744; case "instamart": return 0xFF7B2CBF; case "blinkit": return 0xFFF8CB46;
            case "zepto": return 0xFF5E17EB; case "uber": return 0xFF000000; case "ola": return 0xFF1C1C1C; case "rapido": return 0xFFF9C933;
            case "amazon": return 0xFF232F3E; case "flipkart": return 0xFF2874F0; case "gmail": return 0xFFC5221F; case "whatsapp": return 0xFF075E54; default: return 0xFF5F259F;
        }
    }
    GradientDrawable bg(int color, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g; }
    TextView txt(String s, float size, boolean bold) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(0xFF1C1C1C);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(dp(4), dp(3), dp(4), dp(3)); return t;
    }
    /** A clickable text, like React Native's Pressable+Text: no button class, just a clickable TextView. */
    TextView btn(String s, Runnable r) {
        TextView t = txt(s, 15, true); t.setGravity(Gravity.CENTER); t.setTextColor(Color.WHITE); t.setBackground(bg(brand(), 10));
        t.setPadding(dp(16), dp(11), dp(16), dp(11)); t.setClickable(true); t.setOnClickListener(v -> r.run()); return t;
    }
    TextView outline(String s, Runnable r) {
        TextView t = txt(s, 14, true); t.setGravity(Gravity.CENTER); t.setTextColor(0xFF1BA672);
        GradientDrawable g = bg(Color.WHITE, 8); g.setStroke(dp(1), 0xFFCCCCCC); t.setBackground(g);
        t.setPadding(dp(18), dp(7), dp(18), dp(7)); t.setClickable(true); t.setOnClickListener(v -> r.run()); return t;
    }
    LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    /** A card made of texts with no label of its own — the hardest kind of element for an agent. */
    LinearLayout card(Runnable r, String... lines) {
        LinearLayout c = col(); c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(bg(0xFFF4F4F6, 14));
        for (int i = 0; i < lines.length; i++) c.addView(txt(lines[i], i == 0 ? 16 : 13, i == 0));
        if (r != null) { c.setClickable(true); c.setOnClickListener(v -> r.run()); }
        return c;
    }
    View gap(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return v; }
    LinearLayout.LayoutParams wrapW() { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); l.bottomMargin = dp(10); return l; }
    LinearLayout.LayoutParams grow() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); }

    /** Screen = header + scrolling body + optional sticky bottom bar. */
    void screen(String name, String title, View body, View bottom) {
        log("SCREEN", jo("name", name));
        LinearLayout root = col();
        root.setBackgroundColor(Color.WHITE);
        LinearLayout head = row(); head.setPadding(dp(8), dp(10), dp(12), dp(10)); head.setBackgroundColor(brand());
        if (!back.isEmpty()) { ImageButton b = new ImageButton(this); b.setImageResource(android.R.drawable.ic_media_previous); b.setBackground(null); b.setContentDescription("Navigate up"); b.setOnClickListener(v -> onBackPressed()); head.addView(b); }
        TextView t = txt(title, 19, true); t.setTextColor(APP.equals("blinkit") || APP.equals("rapido") ? Color.BLACK : Color.WHITE); head.addView(t, grow());
        root.addView(head);
        ScrollView sv = new ScrollView(this);
        LinearLayout inner = col(); inner.setPadding(dp(14), dp(12), dp(14), dp(24));
        inner.addView(body);
        sv.addView(inner);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (bottom != null) { FrameLayout f = new FrameLayout(this); f.setPadding(dp(12), dp(8), dp(12), dp(12)); f.addView(bottom); root.addView(f); }
        setContentView(root);
    }
    JSONObject jo(Object... kv) { JSONObject o = new JSONObject(); try { for (int i = 0; i + 1 < kv.length; i += 2) o.put(String.valueOf(kv[i]), kv[i + 1]); } catch (Exception ignored) { } return o; }

    /** A fake search bar: looks like an input but is a clickable box that opens the real search screen. */
    View fakeSearch(String hint, Runnable open) {
        LinearLayout s = row(); s.setPadding(dp(14), dp(12), dp(14), dp(12)); s.setBackground(bg(0xFFF0F0F2, 12));
        TextView t = txt(hint, 15, false); t.setTextColor(0xFF888888); s.addView(t, grow());
        ImageButton mic = new ImageButton(this); mic.setImageResource(android.R.drawable.ic_btn_speak_now); mic.setBackground(null); s.addView(mic); // unlabeled icon
        s.setClickable(true); s.setOnClickListener(v -> open.run());
        return s;
    }

    /** Auto-scrolling offers carousel — keeps the screen "busy", like the real apps. */
    View carousel(String... offers) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        LinearLayout r = row();
        for (String o : offers) { LinearLayout c = card(() -> { }, o, "Tap to explore"); c.setBackground(bg(0xFFFFF1E6, 16)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(250), dp(96)); lp.rightMargin = dp(10); r.addView(c, lp); }
        h.addView(r);
        final int[] x = {0};
        Runnable tick = new Runnable() { public void run() { if (!h.isAttachedToWindow()) return; x[0] = (x[0] + dp(260)) % Math.max(1, dp(260) * offers.length); h.smoothScrollTo(x[0], 0); ui.postDelayed(this, 1600); } };
        ui.postDelayed(tick, 1600);
        return h;
    }

    void offerPopup() {
        Dialog d = new Dialog(this);
        LinearLayout c = col(); c.setPadding(dp(18), dp(12), dp(18), dp(18)); c.setBackground(bg(Color.WHITE, 20));
        LinearLayout top = row(); top.addView(new View(this), grow());
        ImageButton x = new ImageButton(this); x.setImageResource(android.R.drawable.ic_menu_close_clear_cancel); x.setBackground(null); // no contentDescription, like many real popups
        x.setOnClickListener(v -> d.dismiss()); top.addView(x); c.addView(top);
        c.addView(txt("FLAT 60% OFF", 26, true)); c.addView(txt("On your next 3 orders above ₹199. Use code TRYNEW", 14, false)); c.addView(gap(12));
        c.addView(btn("Order now", d::dismiss), wrapW());
        d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setContentView(c);
        d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        d.show();
        log("POPUP", jo("shown", true));
    }

    void dialog(String title, String body, String no, Runnable onNo, String yes, Runnable onYes) {
        Dialog d = new Dialog(this);
        LinearLayout c = col(); c.setPadding(dp(20), dp(18), dp(20), dp(16)); c.setBackground(bg(Color.WHITE, 18));
        c.addView(txt(title, 18, true)); c.addView(txt(body, 14, false)); c.addView(gap(10));
        LinearLayout r = row(); r.addView(outline(no, () -> { d.dismiss(); if (onNo != null) onNo.run(); }), grow()); r.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1));
        r.addView(btn(yes, () -> { d.dismiss(); onYes.run(); }), grow()); c.addView(r);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setContentView(c);
        d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); d.show();
    }

    /** Bottom sheet with radio-like options (size / add-ons) and an Add button. */
    void sheet(String title, List<String> opts, List<Integer> prices, java.util.function.BiConsumer<String, Integer> onAdd) {
        Dialog d = new Dialog(this);
        LinearLayout c = col(); c.setPadding(dp(18), dp(16), dp(18), dp(18)); c.setBackground(bg(Color.WHITE, 22));
        c.addView(txt(title, 18, true)); c.addView(txt("Choose one (required)", 13, false));
        final int[] sel = {0};
        List<TextView> rows = new ArrayList<>();
        TextView add = btn("", () -> { });
        Runnable paint = () -> {
            for (int i = 0; i < rows.size(); i++) { rows.get(i).setText((i == sel[0] ? "◉  " : "○  ") + opts.get(i) + "   ₹" + prices.get(i)); rows.get(i).setSelected(i == sel[0]); }
            add.setText("Add Item | ₹" + prices.get(sel[0]));
        };
        for (int i = 0; i < opts.size(); i++) {
            final int k = i; TextView r = txt("", 15, false); r.setPadding(dp(4), dp(10), dp(4), dp(10)); r.setClickable(true);
            r.setOnClickListener(v -> { sel[0] = k; paint.run(); }); rows.add(r); c.addView(r);
        }
        add.setOnClickListener(v -> { d.dismiss(); onAdd.accept(opts.get(sel[0]), prices.get(sel[0])); });
        c.addView(gap(8)); c.addView(add, wrapW()); paint.run();
        d.requestWindowFeature(Window.FEATURE_NO_TITLE); d.setContentView(c);
        Window w = d.getWindow(); w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM); w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); d.show();
        log("SHEET", jo("title", title));
    }

    // ------------------------------------------------------------------ cart helpers
    int cartCount() { int n = 0; for (int q : cart.values()) n += q; return n; }
    int cartTotal() { int t = 0; for (Map.Entry<String, Integer> e : cart.entrySet()) t += e.getValue() * price.getOrDefault(e.getKey(), 0); return t; }
    void addToCart(String shop, String key, int p, Runnable after) {
        if (shop != null && cartShop != null && !shop.equals(cartShop) && !cart.isEmpty()) {
            dialog("Replace cart item?", "Your cart contains dishes from " + cartShop + ". Do you want to discard the selection and add dishes from " + shop + "?", "No", null, "Replace", () -> { cart.clear(); cartShop = null; addToCart(shop, key, p, after); });
            return;
        }
        if (shop != null) cartShop = shop;
        price.put(key, p); cart.put(key, cart.getOrDefault(key, 0) + 1);
        log("CART", new JSONObject(cart));
        after.run();
    }
    void changeQty(String key, int d, Runnable after) { int q = cart.getOrDefault(key, 0) + d; if (q <= 0) cart.remove(key); else cart.put(key, q); log("CART", new JSONObject(cart)); after.run(); }

    /** "ADD" button, or "− 2 +" stepper once added. */
    View addControl(String shop, String key, int p, boolean available, List<String> custom, List<Integer> customPrices, Runnable refresh) {
        if (!available) { TextView t = txt("Unavailable", 13, true); t.setTextColor(0xFF999999); return t; }
        int q = 0; for (Map.Entry<String, Integer> e : cart.entrySet()) if (e.getKey().startsWith(key)) q += e.getValue();
        if (q == 0) {
            return outline(groceryMode ? "ADD" : "ADD", () -> {
                if (custom != null) sheet(key, custom, customPrices, (opt, pr) -> addToCart(shop, key + " (" + opt + ")", pr, refresh));
                else addToCart(shop, key, p, refresh);
            });
        }
        String k2 = null; for (String k : cart.keySet()) if (k.startsWith(key)) k2 = k;
        final String kk = k2;
        LinearLayout r = row(); r.setBackground(bg(0xFFE8F7EF, 8));
        TextView minus = txt("−", 18, true); minus.setPadding(dp(12), dp(4), dp(12), dp(4)); minus.setClickable(true); minus.setOnClickListener(v -> changeQty(kk, -1, refresh));
        TextView qty = txt(String.valueOf(q), 15, true);
        TextView plus = txt("+", 18, true); plus.setPadding(dp(12), dp(4), dp(12), dp(4)); plus.setClickable(true); plus.setOnClickListener(v -> changeQty(kk, 1, refresh));
        r.addView(minus); r.addView(qty); r.addView(plus);
        return r;
    }

    View cartBar(Runnable open) {
        if (cart.isEmpty()) return null;
        LinearLayout b = row(); b.setPadding(dp(16), dp(12), dp(16), dp(12)); b.setBackground(bg(0xFF1BA672, 12));
        TextView l = txt(cartCount() + (cartCount() == 1 ? " item" : " items") + " added  |  ₹" + cartTotal(), 15, true); l.setTextColor(Color.WHITE); b.addView(l, grow());
        TextView r = txt(groceryMode ? "View Cart ›" : "View Cart", 15, true); r.setTextColor(Color.WHITE); b.addView(r);
        b.setClickable(true); b.setOnClickListener(v -> open.run());
        return b;
    }

    // ------------------------------------------------------------------ data
    static final String[][] RESTAURANTS = {
            // name, rating · eta · cuisine, dishes (name|price|custom options)
            {"Behrouz Biryani", "★ 4.4 · 30-35 mins · Biryani, Mughlai", "Chicken Biryani|349|Half:349,Full:549", "Mutton Biryani|449|", "Veg Biryani|249|", "Gulab Jamun (2 pcs)|99|"},
            {"Paradise Biryani", "★ 4.1 · 40-45 mins · Biryani, Hyderabadi", "Chicken Biryani|299|", "Chicken 65|249|", "Double Ka Meetha|129|"},
            {"Punjabi Dhaba", "★ 4.3 · 25-30 mins · North Indian", "Paneer Butter Masala|260|Half:260,Full:380", "Dal Makhani|220|", "Butter Naan|45|", "Tandoori Roti|25|", "Jeera Rice|150|"},
            {"Pizza Point", "★ 4.0 · 20-25 mins · Pizzas, Fast Food", "Margherita Pizza|199|Regular:199,Medium:349,Large:499", "Farmhouse Pizza|349|", "Garlic Bread|129|", "Choco Lava Cake|99|"},
            {"Hotel Sai Thali", "★ 4.5 · 15-20 mins · Maharashtrian, Thali", "Veg Thali|180|", "Misal Pav|90|", "Vada Pav (2 pcs)|50|", "Sabudana Khichdi|80|"},
    };
    static final String[][] GROCERY = {
            {"Amul Taaza Toned Fresh Milk", "500 ml", "28"}, {"Amul Taaza Toned Fresh Milk", "1 L", "56"}, {"Farm Fresh White Eggs", "6 pcs", "54"},
            {"Farm Fresh White Eggs", "12 pcs", "99"}, {"Fresh Tomato (Local)", "500 g", "20"}, {"Fresh Tomato (Local)", "1 kg", "38"},
            {"Harvest Gold White Bread", "400 g", "45"}, {"Harvest Gold Brown Bread", "400 g", "55"}, {"Maggi 2-Minute Masala Noodles", "70 g", "14"},
            {"Maggi 2-Minute Masala Noodles (Pack of 4)", "280 g", "56"}, {"Coca-Cola Soft Drink", "750 ml", "40"}, {"Coca-Cola Soft Drink", "2.25 L", "95"},
            {"Fresh Onion", "1 kg", "35"}, {"Fresh Potato", "1 kg", "30"}, {"Aashirvaad Shudh Chakki Atta", "5 kg", "245"}, {"Tata Salt", "1 kg", "28"},
            {"Amul Butter", "100 g", "58"}, {"Fortune Sunflower Oil", "1 L", "155"}, {"Banana Robusta", "6 pcs", "48"}, {"Parle-G Biscuits", "250 g", "25"},
    };

    // ------------------------------------------------------------------ routing by app
    void home() {
        switch (APP) {
            case "swiggy": case "zomato": if (groceryMode) groceryHome(); else foodHome(); break;
            case "instamart": case "blinkit": case "zepto": groceryHome(); break;
            case "uber": case "ola": case "rapido": rideHome(); break;
            case "amazon": case "flipkart": shopHome(); break;
            case "gmail": inbox(); break;
            case "whatsapp": chats(); break;
            default: payHome();
        }
    }

    void login() {
        LinearLayout b = col();
        b.addView(txt("Log in or sign up", 22, true)); b.addView(txt("Enter your phone number to continue", 14, false)); b.addView(gap(12));
        EditText ph = new EditText(this); ph.setHint("Phone number"); ph.setInputType(android.text.InputType.TYPE_CLASS_PHONE); b.addView(ph, wrapW());
        b.addView(btn("Continue", () -> { log("LOGIN_ATTEMPT", jo("number", ph.getText().toString())); dialog("OTP sent", "Enter the 6-digit OTP sent to your phone", "Cancel", null, "OK", () -> { }); }), wrapW());
        screen("login", BuildConfig.APP.substring(0, 1).toUpperCase(Locale.ROOT) + BuildConfig.APP.substring(1), b, null);
    }

    // ================================================================== FOOD (Swiggy / Zomato)
    void foodHome() {
        LinearLayout b = col();
        LinearLayout addr = row(); addr.addView(txt("Home ▾", 17, true)); addr.addView(txt("Gangapur Road, Nashik", 13, false), grow()); addr.setClickable(true);
        addr.setOnClickListener(v -> toastLike("Address is already Home")); b.addView(addr, wrapW());
        b.addView(fakeSearch(APP.equals("zomato") ? "Restaurant name or a dish..." : "Search for 'Biryani'", () -> go(this::foodSearch)), wrapW());
        b.addView(carousel("Flat ₹125 OFF above ₹249", "Free delivery on first order", "Up to 60% OFF on Biryani"), wrapW());
        if (APP.equals("swiggy")) {
            LinearLayout tiles = row();
            LinearLayout food = card(() -> { }, "FOOD DELIVERY", "From restaurants"); food.setBackground(bg(0xFFFFF1E6, 14));
            LinearLayout im = card(() -> { groceryMode = true; go(this::groceryHome); }, "INSTAMART", "Groceries in 10 mins"); im.setBackground(bg(0xFFF3E8FF, 14));
            tiles.addView(food, grow()); tiles.addView(new View(this), new LinearLayout.LayoutParams(dp(10), 1)); tiles.addView(im, grow()); b.addView(tiles, wrapW());
        }
        b.addView(txt("What's on your mind?", 17, true));
        HorizontalScrollView hs = new HorizontalScrollView(this); LinearLayout chips = row();
        for (String c : new String[]{"Biryani", "Pizza", "Thali", "North Indian", "Desserts", "Chinese"}) { TextView t = outline(c, () -> { lastQuery = c; go(this::foodResults); }); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8); chips.addView(t, lp); }
        hs.addView(chips); b.addView(hs, wrapW());
        b.addView(txt("All restaurants nearby", 17, true));
        for (String[] r : RESTAURANTS) b.addView(card(() -> go(() -> menu(r)), r[0], r[1], "Free delivery · ₹" + (150 + r[0].length() * 3) + " for one"), wrapW());
        screen("food_home", APP.equals("zomato") ? "zomato" : "Swiggy", b, cartBar(() -> go(this::foodCart)));
    }

    String lastQuery = "";
    void foodSearch() {
        LinearLayout b = col();
        EditText e = new EditText(this); e.setHint(APP.equals("zomato") ? "Search for restaurant, item or more" : "Search for restaurants and food"); e.setSingleLine(true);
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        b.addView(e, wrapW());
        LinearLayout sugg = col(); b.addView(sugg);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable s) {
                ui.postDelayed(() -> { // results arrive a moment later, like a network call
                    sugg.removeAllViews(); String q = s.toString().trim().toLowerCase(Locale.ROOT); if (q.length() < 2) return;
                    for (String[] r : RESTAURANTS) if (r[0].toLowerCase(Locale.ROOT).contains(q)) sugg.addView(card(() -> go(() -> menu(r)), r[0], "Restaurant"), wrapW());
                    java.util.Set<String> dishes = new java.util.LinkedHashSet<>();
                    for (String[] r : RESTAURANTS) for (int i = 2; i < r.length; i++) { String d = r[i].split("\\|")[0]; if (d.toLowerCase(Locale.ROOT).contains(q)) dishes.add(d); }
                    for (String d : dishes) sugg.addView(card(() -> { lastQuery = d; go(SimActivity.this::foodResults); }, d, "Dish"), wrapW());
                    log("SUGGESTIONS", jo("q", q, "n", sugg.getChildCount()));
                }, 600);
            }
        });
        e.setOnEditorActionListener((v, a, ev) -> { lastQuery = e.getText().toString(); go(this::foodResults); return true; });
        screen("food_search", "Search", b, null);
        e.requestFocus();
    }

    /** Dish search results: the same dish from several restaurants, each with its own ADD. */
    void foodResults() {
        LinearLayout b = col();
        String q = lastQuery.toLowerCase(Locale.ROOT);
        b.addView(txt("Results for \"" + lastQuery + "\"", 15, true));
        for (String[] r : RESTAURANTS) {
            boolean shown = false;
            for (int i = 2; i < r.length; i++) {
                String[] d = r[i].split("\\|", -1);
                boolean match = d[0].toLowerCase(Locale.ROOT).contains(q) || r[0].toLowerCase(Locale.ROOT).contains(q) || r[1].toLowerCase(Locale.ROOT).contains(q);
                if (!match) continue;
                if (!shown) { LinearLayout hdr = card(() -> go(() -> menu(r)), "By " + r[0], r[1]); b.addView(hdr, wrapW()); shown = true; }
                b.addView(dishRow(r, d, this::foodResults), wrapW());
            }
        }
        screen("food_results", "Search", b, cartBar(() -> go(this::foodCart)));
    }

    View dishRow(String[] r, String[] d, Runnable refresh) {
        LinearLayout row = row(); row.setPadding(dp(6), dp(10), dp(6), dp(10));
        LinearLayout info = col();
        boolean veg = !d[0].toLowerCase(Locale.ROOT).matches(".*(chicken|mutton|egg).*");
        info.addView(txt((veg ? "🟢 " : "🔺 ") + d[0], 16, true)); info.addView(txt("₹" + d[1], 14, false));
        if (d[0].contains("Biryani") || d[0].contains("Paneer")) info.addView(txt("★ Bestseller", 12, false));
        row.addView(info, grow());
        boolean oos = has("oos") && d[0].contains("Mutton");
        List<String> opts = null; List<Integer> pr = null;
        if (has("custom") && !d[2].isEmpty()) { opts = new ArrayList<>(); pr = new ArrayList<>(); for (String o : d[2].split(",")) { String[] kv = o.split(":"); opts.add(kv[0]); pr.add(Integer.parseInt(kv[1])); } }
        row.addView(addControl(r[0], d[0], Integer.parseInt(d[1]), !oos, opts, pr, refresh));
        if (opts != null) { TextView c = txt("customisable", 11, false); c.setTextColor(0xFF999999); info.addView(c); }
        return row;
    }

    void menu(String[] r) {
        LinearLayout b = col();
        b.addView(txt(r[0], 24, true)); b.addView(txt(r[1], 14, false));
        b.addView(txt("Recommended (" + (r.length - 2) + ")", 17, true));
        for (int i = 2; i < r.length; i++) b.addView(dishRow(r, r[i].split("\\|", -1), () -> replace(() -> menu(r))), wrapW());
        screen("menu:" + r[0], r[0], b, cartBar(() -> go(this::foodCart)));
    }

    void foodCart() {
        LinearLayout b = col();
        if (cart.isEmpty()) { b.addView(txt("Your cart is empty", 18, true)); screen("cart_empty", "Cart", b, null); return; }
        b.addView(txt(cartShop == null ? "Your order" : cartShop, 18, true));
        for (String k : new ArrayList<>(cart.keySet())) {
            LinearLayout r = row(); r.addView(txt(k + "  ·  ₹" + price.get(k), 15, false), grow());
            LinearLayout st = row(); TextView m = txt("−", 18, true); m.setClickable(true); m.setOnClickListener(v -> changeQty(k, -1, () -> replace(this::foodCart)));
            TextView qn = txt(String.valueOf(cart.get(k)), 15, true); TextView p = txt("+", 18, true); p.setClickable(true); p.setOnClickListener(v -> changeQty(k, 1, () -> replace(this::foodCart)));
            st.addView(m); st.addView(qn); st.addView(p); r.addView(st); b.addView(r, wrapW());
        }
        b.addView(txt("Add cooking instructions (optional)", 13, false));
        b.addView(txt("Bill Details", 16, true));
        b.addView(txt("Item Total  ₹" + cartTotal(), 14, false)); b.addView(txt("Delivery Fee  ₹35", 14, false)); b.addView(txt("Platform fee  ₹10", 14, false));
        int pay = cartTotal() + 45;
        b.addView(txt("TO PAY  ₹" + pay, 16, true));
        b.addView(txt("Delivering to Home: Gangapur Road, Nashik", 13, false));
        View cta = APP.equals("zomato") ? btn("Place Order  ₹" + pay, () -> placeOrder(pay)) : btn("Proceed to Pay  ₹" + pay, () -> go(() -> payScreen(pay)));
        screen("cart", "Cart", b, cta);
    }

    void payScreen(int amount) {
        LinearLayout b = col();
        b.addView(txt("Payment Options", 20, true)); b.addView(txt("Amount to pay ₹" + amount, 15, false));
        b.addView(txt("UPI", 15, true));
        final String[] method = {"Google Pay"};
        for (String m : new String[]{"Google Pay", "PhonePe", "Paytm UPI"}) { LinearLayout c = card(() -> { method[0] = m; toastLike(m + " selected"); }, m, "Pay via " + m); b.addView(c, wrapW()); }
        b.addView(txt("Cash on Delivery", 15, true)); b.addView(card(() -> { method[0] = "Cash"; toastLike("Cash selected"); }, "Pay on Delivery", "Cash/UPI on delivery"), wrapW());
        screen("payment", "Payment", b, btn("Pay ₹" + amount, () -> placeOrder(amount)));
    }

    void placeOrder(int amount) {
        JSONObject items = new JSONObject(cart);
        log("ORDER", jo("items", items, "total", amount, "shop", cartShop == null ? "" : cartShop, "mode", groceryMode ? "grocery" : "food"));
        cart.clear(); cartShop = null;
        LinearLayout b = col(); b.addView(txt("Order placed! 🎉", 24, true)); b.addView(txt("Arriving in 30 mins", 15, false));
        back.clear(); replace(() -> screen("order_placed", "Order status", b, null));
    }

    // ================================================================== GROCERY (Instamart / Blinkit / Zepto)
    void groceryHome() {
        LinearLayout b = col();
        b.addView(txt("Delivery in 10 minutes", 18, true)); b.addView(txt("Home - Gangapur Road, Nashik", 13, false));
        b.addView(fakeSearch(APP.equals("blinkit") ? "Search \"milk\"" : "Search for \"atta, dal, coke and more\"", () -> go(this::grocerySearch)), wrapW());
        b.addView(carousel("₹0 delivery fee", "Fresh fruits from ₹19", "Buy 1 Get 1 on snacks"), wrapW());
        b.addView(txt("Shop by category", 17, true));
        String[] cats = {"Dairy, Bread & Eggs", "Fruits & Vegetables", "Atta, Rice & Dal", "Snacks & Drinks"};
        for (String c : cats) b.addView(card(() -> { lastQuery = c.split("[ ,]")[0]; go(this::groceryResults); }, c), wrapW());
        screen("grocery_home", APP.equals("swiggy") ? "Instamart" : BuildConfig.APP.substring(0, 1).toUpperCase(Locale.ROOT) + BuildConfig.APP.substring(1), b, cartBar(() -> go(this::groceryCart)));
    }

    void grocerySearch() {
        LinearLayout b = col();
        EditText e = new EditText(this); e.setHint("Search for products"); e.setSingleLine(true); b.addView(e, wrapW());
        LinearLayout res = col(); b.addView(res);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable s) { ui.postDelayed(() -> { lastQuery = s.toString(); res.removeAllViews(); productList(res, lastQuery, () -> { }); }, 500); }
        });
        screen("grocery_search", "Search", b, cartBar(() -> go(this::groceryCart)));
        e.requestFocus();
    }

    void groceryResults() {
        LinearLayout b = col(); b.addView(txt("Showing results for \"" + lastQuery + "\"", 15, true));
        productList(b, lastQuery, () -> replace(this::groceryResults));
        screen("grocery_results", "Search", b, cartBar(() -> go(this::groceryCart)));
    }

    void productList(LinearLayout into, String q0, Runnable refresh) {
        String q = q0.trim().toLowerCase(Locale.ROOT); if (q.length() < 2) return;
        String[] words = q.split("\\s+");
        int n = 0;
        for (String[] g : GROCERY) {
            String name = (g[0] + " " + g[1]).toLowerCase(Locale.ROOT); boolean ok = false;
            for (String w : words) if (w.length() > 2 && name.contains(w.replaceAll("s$", ""))) ok = true;
            if (q.startsWith("dairy") && name.matches(".*(milk|egg|bread|butter).*")) ok = true;
            if (!ok) continue;
            n++;
            LinearLayout row = row(); row.setPadding(dp(6), dp(10), dp(6), dp(10));
            LinearLayout info = col(); info.addView(txt(g[0], 15, true)); info.addView(txt(g[1], 13, false)); info.addView(txt("₹" + g[2], 14, true));
            row.addView(info, grow());
            boolean oos = has("oos") && g[0].contains("Eggs");
            Runnable rf = () -> { into.removeAllViews(); if (refresh != null) { productList(into, q0, refresh); } setBar(); };
            row.addView(addControl(null, g[0] + " " + g[1], Integer.parseInt(g[2]), !oos, null, null, rf));
            into.addView(row, wrapW());
        }
        if (n == 0) into.addView(txt("No results for \"" + q0 + "\"", 14, false));
        log("RESULTS", jo("q", q, "n", n));
    }

    /** Refresh the sticky cart bar without rebuilding the screen (as real apps do). */
    void setBar() {
        View root = ((ViewGroup) getWindow().getDecorView().findViewById(android.R.id.content)).getChildAt(0);
        if (!(root instanceof LinearLayout)) return;
        LinearLayout l = (LinearLayout) root;
        if (l.getChildCount() > 2) l.removeViewAt(2);
        View bar = cartBar(() -> go(this::groceryCart));
        if (bar != null) { FrameLayout f = new FrameLayout(this); f.setPadding(dp(12), dp(8), dp(12), dp(12)); f.addView(bar); l.addView(f); }
    }

    void groceryCart() {
        LinearLayout b = col();
        if (cart.isEmpty()) { b.addView(txt("Your cart is empty", 18, true)); screen("cart_empty", "Cart", b, null); return; }
        b.addView(txt("Delivery in 10 minutes · " + cartCount() + " items", 16, true));
        for (String k : new ArrayList<>(cart.keySet())) {
            LinearLayout r = row(); r.addView(txt(k + "\n₹" + price.get(k), 14, false), grow());
            LinearLayout st = row(); TextView m = txt("−", 18, true); m.setClickable(true); m.setOnClickListener(v -> changeQty(k, -1, () -> replace(this::groceryCart)));
            TextView qn = txt(String.valueOf(cart.get(k)), 15, true); TextView p = txt("+", 18, true); p.setClickable(true); p.setOnClickListener(v -> changeQty(k, 1, () -> replace(this::groceryCart)));
            st.addView(m); st.addView(qn); st.addView(p); r.addView(st); b.addView(r, wrapW());
        }
        b.addView(txt("You might also like: Lays Classic ₹20, Amul Cheese ₹120", 13, false));
        int pay = cartTotal() + (cartTotal() >= 199 ? 0 : 30);
        b.addView(txt("Bill details  ·  Items ₹" + cartTotal() + "  ·  Delivery " + (cartTotal() >= 199 ? "FREE" : "₹30"), 14, false));
        b.addView(txt("Grand total ₹" + pay, 16, true));
        String label = APP.equals("blinkit") ? "Proceed To Pay ₹" + pay : APP.equals("zepto") ? "Click to Pay ₹" + pay : "Proceed to Pay  ₹" + pay;
        screen("cart", "Cart", b, btn(label, () -> go(() -> payScreen(pay))));
    }

    // ================================================================== RIDES (Uber / Ola / Rapido)
    String[][] rideTypes() {
        switch (APP) {
            case "ola": return new String[][]{{"Mini", "196", "4 min"}, {"Prime Sedan", "242", "6 min"}, {"Auto", "128", "3 min"}, {"Bike", "79", "2 min"}};
            case "rapido": return new String[][]{{"Bike", "69", "2 min"}, {"Auto", "119", "4 min"}, {"Cab Economy", "189", "6 min"}};
            default: return new String[][]{{"Uber Go", "186", "4 min"}, {"Premier", "240", "5 min"}, {"Uber Auto", "120", "3 min"}, {"Uber Moto", "75", "2 min"}};
        }
    }
    String dropTo = "";
    void rideHome() {
        LinearLayout b = col();
        View map = new View(this); map.setBackgroundColor(0xFFE5ECEF); map.setClickable(true); b.addView(map, new LinearLayout.LayoutParams(-1, dp(220)));
        b.addView(gap(10));
        b.addView(fakeSearch(APP.equals("ola") ? "Search for a destination" : APP.equals("rapido") ? "Where are you going?" : "Where to?", () -> go(this::rideSearch)), wrapW());
        b.addView(card(() -> { dropTo = "Home - Gangapur Road"; go(this::rideOptions); }, "Home", "Gangapur Road, Nashik"), wrapW());
        b.addView(card(() -> { dropTo = "Work - SBI SME Branch, College Road"; go(this::rideOptions); }, "Work", "College Road, Nashik"), wrapW());
        screen("ride_home", BuildConfig.APP.substring(0, 1).toUpperCase(Locale.ROOT) + BuildConfig.APP.substring(1), b, null);
        if (has("popup")) ui.postDelayed(() -> dialog("How was your last trip?", "Rate your ride with Ramesh", "Skip", null, "Rate 5 ★", () -> { }), 900);
    }

    static final String[] PLACES = {"Nashik Road Railway Station", "Nashik Road Bus Stand", "College Road, Nashik", "Nashik Airport (Ozar)", "City Centre Mall, Nashik", "Panchavati, Nashik", "CBS Bus Stand, Nashik"};
    void rideSearch() {
        LinearLayout b = col();
        EditText pick = new EditText(this); pick.setText("Current Location"); pick.setHint("Pickup"); b.addView(pick, wrapW());
        EditText drop = new EditText(this); drop.setHint(APP.equals("uber") ? "Where to?" : "Drop location"); b.addView(drop, wrapW());
        LinearLayout sugg = col(); b.addView(sugg);
        drop.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable s) {
                ui.postDelayed(() -> {
                    sugg.removeAllViews(); String q = s.toString().toLowerCase(Locale.ROOT).trim(); if (q.length() < 2) return;
                    for (String p : PLACES) { boolean m = false; for (String w : q.split("[\\s,]+")) if (w.length() > 2 && p.toLowerCase(Locale.ROOT).contains(w)) m = true; if (m) sugg.addView(card(() -> { dropTo = p; go(SimActivity.this::rideOptions); }, p, "Nashik, Maharashtra"), wrapW()); }
                }, 500);
            }
        });
        screen("ride_search", "Plan your ride", b, null);
        drop.requestFocus();
    }

    int rideSel = 0;
    void rideOptions() {
        LinearLayout b = col();
        b.addView(txt("To: " + dropTo, 15, true));
        String[][] t = rideTypes();
        for (int i = 0; i < t.length; i++) {
            final int k = i;
            LinearLayout c = card(() -> { rideSel = k; replace(this::rideOptions); }, t[i][0], "₹" + t[i][1] + "  ·  " + t[i][2] + " away");
            c.setSelected(i == rideSel); if (i == rideSel) c.setBackground(bg(0xFFE8EEFF, 14));
            b.addView(c, wrapW());
        }
        b.addView(txt("💵 Cash  ▾", 15, true));
        String name = t[rideSel][0];
        String cta = APP.equals("uber") ? "Choose " + name : APP.equals("ola") ? "Book " + name : "Book " + name;
        screen("ride_options", "Choose a ride", b, btn(cta, () -> go(this::confirmPickup)));
    }

    void confirmPickup() {
        LinearLayout b = col();
        View map = new View(this); map.setBackgroundColor(0xFFE5ECEF); b.addView(map, new LinearLayout.LayoutParams(-1, dp(260)));
        b.addView(txt("Confirm your pickup spot", 18, true)); b.addView(txt("Current Location — near Gangapur Road", 14, false));
        screen("ride_confirm", "Pickup", b, btn("Confirm pickup", () -> {
            String[] t = rideTypes()[rideSel];
            log("RIDE", jo("type", t[0], "fare", t[1], "drop", dropTo));
            LinearLayout d = col(); d.addView(txt("Looking for nearby drivers…", 20, true)); back.clear(); replace(() -> screen("ride_booked", "Your ride", d, null));
        }));
    }

    // ================================================================== SHOPPING (Amazon / Flipkart)
    static final String[][] PRODUCTS = {
            {"boAt Airdopes 131 TWS Earbuds", "999", "4.0", "Black,White", "1"},
            {"boAt Airdopes 141 TWS Earbuds with 42H Playtime", "1299", "4.2", "Black,White,Blue", "0"},
            {"Noise Buds VS102 Truly Wireless Earbuds", "899", "4.1", "Black,Grey", "0"},
            {"boAt Airdopes 141 ANC Earbuds", "1799", "4.3", "Black", "0"},
            {"Milton Thermosteel Flip Lid Flask, 1000 ml", "799", "4.4", "Silver", "0"},
            {"Milton Aqua 1000 Stainless Steel Water Bottle, 1 L", "449", "4.3", "Silver,Blue", "0"},
            {"Cello H2O Plastic Bottle 1 L (Set of 3)", "149", "4.0", "Assorted", "1"},
    };
    String shopQuery = "";
    void shopHome() {
        LinearLayout b = col();
        sug = col();
        if (APP.equals("amazon")) {
            EditText e = new EditText(this); e.setHint("Search Amazon.in"); e.setSingleLine(true); e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            e.setOnEditorActionListener((v, a, ev) -> { shopQuery = e.getText().toString(); go(this::shopResults); return true; });
            e.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int d) { } public void onTextChanged(CharSequence s, int a, int c, int d) { }
                public void afterTextChanged(Editable s) { ui.postDelayed(() -> { sug.removeAllViews(); String q = s.toString().trim(); if (q.length() < 3) return; for (String x : new String[]{q, q + " earbuds", q + " for men"}) sug.addView(card(() -> { shopQuery = x; go(SimActivity.this::shopResults); }, x), wrapW()); }, 400); } });
            b.addView(e, wrapW()); b.addView(sug);
        } else b.addView(fakeSearch("Search for Products, Brands and More", () -> go(this::flipSearch)), wrapW());
        b.addView(carousel("Great Indian Festival · Up to 80% off", "Bank offer: 10% instant discount", "Deals on headphones"), wrapW());
        b.addView(txt("Deals for you", 17, true));
        for (String[] p : PRODUCTS) if (p[4].equals("1")) b.addView(card(() -> go(() -> product(p)), p[0], "₹" + p[1] + " · Deal of the day"), wrapW());
        screen("shop_home", APP.equals("amazon") ? "amazon.in" : "Flipkart", b, null);
    }
    LinearLayout sug;
    void flipSearch() {
        LinearLayout b = col();
        EditText e = new EditText(this); e.setHint("Search for products"); e.setSingleLine(true); e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        e.setOnEditorActionListener((v, a, ev) -> { shopQuery = e.getText().toString(); go(this::shopResults); return true; });
        LinearLayout s2 = col();
        e.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int d) { } public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable s) { ui.postDelayed(() -> { s2.removeAllViews(); String q = s.toString().trim(); if (q.length() < 3) return; for (String x : new String[]{q, q + " in Bottles", q + " combo"}) s2.addView(card(() -> { shopQuery = x; go(SimActivity.this::shopResults); }, x), wrapW()); }, 400); } });
        b.addView(e, wrapW()); b.addView(s2);
        screen("shop_search", "Search", b, null); e.requestFocus();
    }
    void shopResults() {
        LinearLayout b = col();
        String q = shopQuery.toLowerCase(Locale.ROOT);
        b.addView(txt("Results for \"" + shopQuery + "\"", 15, true));
        int n = 0;
        // sponsored first, like the real apps
        for (int pass = 0; pass < 2; pass++) for (String[] p : PRODUCTS) {
            if ((pass == 0) != p[4].equals("1")) continue;
            boolean m = false; for (String w : q.split("\\s+")) if (w.length() > 2 && p[0].toLowerCase(Locale.ROOT).contains(w.replaceAll("s$", ""))) m = true;
            if (!m) continue; n++;
            b.addView(card(() -> go(() -> product(p)), (p[4].equals("1") ? "Sponsored · " : "") + p[0], "★ " + p[2] + " (12,481)", "₹" + p[1] + "  M.R.P. ₹" + (Integer.parseInt(p[1]) * 3), "FREE delivery Tomorrow"), wrapW());
        }
        if (n == 0) b.addView(txt("No results", 14, false));
        screen("shop_results", APP.equals("amazon") ? "amazon.in" : "Flipkart", b, null);
    }
    String color = "";
    boolean protect = false;
    void product(String[] p) {
        LinearLayout b = col();
        View img = new View(this); img.setBackgroundColor(0xFFF2F2F2); b.addView(img, new LinearLayout.LayoutParams(-1, dp(200)));
        b.addView(txt(p[0], 18, true)); b.addView(txt("★ " + p[2] + " · 12,481 ratings", 13, false)); b.addView(txt("₹" + p[1], 24, true));
        String[] colors = p[3].split(",");
        if (color.isEmpty() || !Arrays.asList(colors).contains(color)) color = colors[0];
        b.addView(txt("Colour: " + color, 14, true));
        LinearLayout cr = row();
        for (String c : colors) { TextView t = outline(c, () -> { color = c; replace(() -> product(p)); }); t.setSelected(c.equals(color)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.rightMargin = dp(8); cr.addView(t, lp); }
        b.addView(cr, wrapW());
        android.widget.CheckBox pr = new android.widget.CheckBox(this); pr.setText("Add a protection plan · 1 Year Extended Warranty for ₹99"); pr.setChecked(protect);
        pr.setOnCheckedChangeListener((v, on) -> protect = on); b.addView(pr, wrapW());
        b.addView(txt("FREE delivery Tomorrow. Deliver to Vishwajeet - Nashik 422013", 13, false));
        LinearLayout ctas = col();
        ctas.addView(btn("Add to Cart", () -> { cart.clear(); price.put(p[0] + " (" + color + ")", Integer.parseInt(p[1])); cart.put(p[0] + " (" + color + ")", 1); log("CART", new JSONObject(cart)); toastLike("Added to cart"); go(this::shopCart); }), wrapW());
        ctas.addView(btn("Buy Now", () -> { cart.clear(); price.put(p[0] + " (" + color + ")", Integer.parseInt(p[1])); cart.put(p[0] + " (" + color + ")", 1); go(this::checkout); }), wrapW());
        screen("product", APP.equals("amazon") ? "amazon.in" : "Flipkart", b, ctas);
    }
    void shopCart() {
        LinearLayout b = col(); b.addView(txt("Subtotal ₹" + cartTotal(), 20, true));
        for (String k : cart.keySet()) b.addView(txt(k + "  × " + cart.get(k), 14, false), wrapW());
        screen("shop_cart", "Cart", b, btn(APP.equals("amazon") ? "Proceed to Buy (" + cartCount() + " item)" : "Place Order", () -> go(this::checkout)));
    }
    void checkout() {
        LinearLayout b = col();
        b.addView(txt("Delivering to Vishwajeet", 16, true)); b.addView(txt("Gangapur Road, Nashik 422013", 14, false));
        b.addView(txt("Payment: Cash on Delivery / Pay on Delivery", 14, false));
        for (String k : cart.keySet()) b.addView(txt(k + "  × " + cart.get(k) + "  ₹" + price.get(k), 14, false));
        int total = cartTotal() + (protect ? 99 : 0);
        b.addView(txt("Order Total: ₹" + total, 18, true));
        Runnable place = () -> { log("ORDER", jo("items", new JSONObject(cart), "total", total, "protection", protect, "mode", "shopping")); cart.clear(); LinearLayout d = col(); d.addView(txt("Order placed, thank you!", 22, true)); back.clear(); replace(() -> screen("order_placed", "Done", d, null)); };
        if (APP.equals("flipkart")) screen("checkout", "Order Summary", b, btn("Continue", () -> go(() -> { LinearLayout pb = col(); pb.addView(txt("Payments", 20, true)); pb.addView(card(() -> toastLike("Cash on Delivery selected"), "Cash on Delivery"), wrapW()); screen("payment", "Payments", pb, btn("Place Order", place)); })));
        else screen("checkout", "Checkout", b, btn("Place your order", place));
    }

    // ================================================================== GMAIL
    void inbox() {
        LinearLayout b = col(); b.addView(fakeSearch("Search in mail", () -> { }), wrapW());
        for (String s : new String[]{"SBI · Your account statement", "Swiggy · Your order was delivered", "Amazon · Your package is on the way"}) b.addView(card(() -> { }, s.split(" · ")[0], s.split(" · ")[1]), wrapW());
        screen("inbox", "Primary", b, btn("Compose", () -> go(() -> compose(new Intent()))));
    }
    void compose(Intent in) {
        LinearLayout b = col();
        String[] to = in.getStringArrayExtra(Intent.EXTRA_EMAIL);
        EditText t = new EditText(this); t.setHint("To"); if (to != null && to.length > 0 && has("noto") == false) t.setText(String.join(", ", to)); b.addView(t, wrapW());
        EditText s = new EditText(this); s.setHint("Subject"); s.setText(in.getStringExtra(Intent.EXTRA_SUBJECT)); b.addView(s, wrapW());
        EditText body = new EditText(this); body.setHint("Compose email"); body.setText(in.getStringExtra(Intent.EXTRA_TEXT)); body.setMinLines(5); b.addView(body, wrapW());
        LinearLayout bar = row();
        bar.addView(new View(this), grow());
        ImageButton attach = new ImageButton(this); attach.setImageResource(android.R.drawable.ic_menu_upload); attach.setContentDescription("Attach file"); bar.addView(attach);
        ImageButton send = new ImageButton(this); send.setImageResource(android.R.drawable.ic_menu_send); send.setContentDescription("Send");
        send.setOnClickListener(v -> {
            if (t.getText().toString().trim().isEmpty()) { toastLike("Add at least one recipient."); return; }
            log("MAIL", jo("to", t.getText().toString(), "subject", s.getText().toString(), "body", body.getText().toString()));
            LinearLayout d = col(); d.addView(txt("Sent", 22, true)); back.clear(); replace(() -> screen("sent", "Gmail", d, null));
        });
        bar.addView(send);
        b.addView(bar, 0);
        screen("compose", "Compose", b, null);
    }

    // ================================================================== WHATSAPP
    void chats() {
        LinearLayout b = col();
        for (String c : new String[]{"Rahul Patil", "Mom", "SBI Team"}) b.addView(card(() -> go(() -> chat(c, "")), c, "Tap to chat"), wrapW());
        screen("chats", "WhatsApp", b, null);
    }
    void waChat(Uri u) {
        String phone = u.getQueryParameter("phone"), text = u.getQueryParameter("text");
        if (has("interstitial")) { LinearLayout b = col(); b.addView(txt("Chat on WhatsApp with +" + phone, 18, true)); screen("wa_interstitial", "WhatsApp", b, btn("Continue to chat", () -> replace(() -> chat("+" + phone, text)))); return; }
        chat("+" + phone, text);
    }
    void chat(String who, String text) {
        LinearLayout b = col(); b.addView(txt("Messages and calls are end-to-end encrypted.", 12, false));
        LinearLayout bar = row();
        EditText e = new EditText(this); e.setHint("Message"); e.setText(text); bar.addView(e, grow());
        ImageButton send = new ImageButton(this); send.setImageResource(android.R.drawable.ic_menu_send); send.setContentDescription("Send");
        send.setOnClickListener(v -> { log("MSG", jo("to", who, "text", e.getText().toString())); e.setText(""); toastLike("Sent ✓✓"); });
        bar.addView(send);
        screen("chat:" + who, who, b, bar);
    }

    // ================================================================== PHONEPE (recharge & bills)
    void payHome() {
        LinearLayout b = col();
        b.addView(txt("Recharge & Pay Bills", 17, true));
        LinearLayout g = row();
        for (String s : new String[]{"Mobile Recharge", "Electricity", "DTH", "FASTag"}) { LinearLayout c = card(() -> { if (s.startsWith("Mobile")) go(this::recharge); else if (s.equals("Electricity")) go(this::electricity); else toastLike(s); }, s); g.addView(c, grow()); }
        b.addView(g, wrapW());
        b.addView(carousel("Win cashback up to ₹100", "Refer & earn ₹200"), wrapW());
        b.addView(txt("Transfer Money", 17, true));
        for (String s : new String[]{"To Mobile Number", "To Bank/UPI ID", "Check Balance"}) b.addView(card(() -> toastLike(s), s), wrapW());
        screen("pay_home", "PhonePe", b, null);
    }
    void recharge() {
        LinearLayout b = col();
        EditText n = new EditText(this); n.setHint("Enter Mobile Number"); n.setInputType(android.text.InputType.TYPE_CLASS_PHONE); b.addView(n, wrapW());
        b.addView(txt("My Number", 14, true));
        b.addView(card(() -> { rcNumber = "9822012345"; go(this::plans); }, "9822012345", "Jio Prepaid · Maharashtra"), wrapW());
        b.addView(btn("Continue", () -> { rcNumber = n.getText().toString().replaceAll("\\D", ""); if (rcNumber.length() < 10) { toastLike("Enter a valid number"); return; } go(this::plans); }), wrapW());
        screen("recharge", "Mobile Recharge", b, null);
    }
    String rcNumber = "";
    void plans() {
        LinearLayout b = col(); b.addView(txt("Jio Prepaid · " + rcNumber, 15, true));
        String[][] pl = {{"199", "1.5 GB/day · 18 days"}, {"299", "2 GB/day · 28 days"}, {"349", "2.5 GB/day · 28 days · Unlimited 5G"}, {"749", "2 GB/day · 72 days"}};
        for (String[] p : pl) b.addView(card(() -> go(() -> payConfirm("Recharge " + rcNumber, Integer.parseInt(p[0]))), "₹" + p[0], p[1], "View details"), wrapW());
        screen("plans", "Select a plan", b, null);
    }
    void electricity() {
        LinearLayout b = col();
        b.addView(card(() -> { }, "MSEDCL", "Maharashtra State Electricity Distribution"), wrapW());
        EditText c = new EditText(this); c.setHint("Consumer Number"); b.addView(c, wrapW());
        b.addView(btn("Confirm", () -> { if (c.getText().toString().replaceAll("\\D", "").length() < 6) { toastLike("Enter a valid consumer number"); return; } go(() -> { LinearLayout d = col(); d.addView(txt("Bill amount ₹1,842", 20, true)); d.addView(txt("Due date: 15 Nov 2026 · Consumer " + c.getText(), 14, false)); screen("bill", "Pay Bill", d, btn("Proceed to Pay", () -> go(() -> payConfirm("MSEDCL bill " + c.getText(), 1842)))); }); }), wrapW());
        screen("electricity", "Electricity Bill", b, null);
    }
    void payConfirm(String what, int amount) {
        LinearLayout b = col(); b.addView(txt(what, 16, true)); b.addView(txt("₹" + amount, 26, true));
        b.addView(card(() -> { }, "SBI xx4417", "UPI · Default"), wrapW());
        screen("pay_confirm", "Payment", b, btn("Pay ₹" + amount, () -> go(() -> pin(what, amount))));
    }
    void pin(String what, int amount) {
        log("PAY_PIN", jo("what", what, "amount", amount));
        LinearLayout b = col(); b.addView(txt("ENTER 6-DIGIT UPI PIN", 18, true)); b.addView(txt("State Bank of India xx4417 · ₹" + amount, 14, false));
        LinearLayout pad = col();
        for (String r : new String[]{"1 2 3", "4 5 6", "7 8 9", "⌫ 0 ✓"}) { LinearLayout l = row(); for (String k : r.split(" ")) { TextView t = txt(k, 22, true); t.setGravity(Gravity.CENTER); t.setPadding(dp(10), dp(14), dp(10), dp(14)); t.setClickable(true); t.setOnClickListener(v -> log("PIN_KEY", jo("k", "*"))); l.addView(t, grow()); } pad.addView(l); }
        b.addView(pad);
        screen("upi_pin", "UPI", b, null);
    }

    void toastLike(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
}
