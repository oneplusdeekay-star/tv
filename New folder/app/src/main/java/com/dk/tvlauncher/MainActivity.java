package com.dk.tvlauncher;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apple TV style home screen. Plain Views, no third-party libraries, no blur, no polling.
 * All D-pad handling is done by hand in dispatchKeyEvent so navigation is deterministic.
 */
public class MainActivity extends Activity {

    // ---- Edit this list to change the dock (package names, in order) ----
    private static final String[] DOCK_PKGS = {
            "org.smarttube.stable",
            "com.jio.media.jiotvplus",
            "in.startv.hotstar",
            "com.amazon.amazonvideo.livingroom"
    };

    // Apps that never appear in the grid
    private static final String[] HIDDEN_PKGS = {
            "com.android.tv.settings",
            "com.google.android.tvlauncher",
            "com.oneplus.tv.launcher"
    };

    private static final int Z_TOP = 0, Z_DOCK = 1, Z_GRID = 2, Z_MENU = 3;
    private static final int COLS = 4;
    private static final float SCALE = 1.08f;

    static class App {
        String label, pkg;
        ComponentName cn;
        Drawable image;
        boolean banner;
        int glow = 0xFF5B8DEF;
        View tile;
        TextView name;
    }

    static class Input {
        String id, label;
        int type;
        boolean passthrough;
    }

    // layout metrics (u = pixels per design unit; the design is 960 x 540 units)
    private float u;
    private int W, H;
    private int TW, TH;

    private FrameLayout root, content;
    private ScrollView scroll;
    private GlassView glass;
    private LinearLayout bar, menuPanel;
    private TextView[] pills = new TextView[3];
    private TextView bigName, greeting;
    private View glow;
    private GradientDrawable glowDrawable;
    private int glowColor = 0xFF5B8DEF;
    private ValueAnimator glowAnim;
    private ObjectAnimator scrollAnim;

    private final List<App> dock = new ArrayList<>();
    private final List<App> grid = new ArrayList<>();
    private List<Input> menuInputs = new ArrayList<>();
    private String appSignature = "";

    private Typeface tfThin, tfLight, tfReg;

    private int zone = Z_DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0, menuIdx = 0;
    private int scrollTarget = 0;
    private View scaledTile;
    private TextView shownLabel;
    private ConnectivityManager.NetworkCallback netCallback;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!computeSignature().equals(appSignature)) {
            buildUi();           // an app was installed, removed, enabled or disabled
        } else {
            updateGreeting();
        }
        updateWifi();
        registerNet();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterNet();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the launcher: go back to the dock.
        if (hasWindowFocus() && root != null) {
            closeMenuQuiet();
            if (!dock.isEmpty()) {
                zone = Z_DOCK;
                applyFocus(true);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // ------------------------------------------------------------------ build

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        W = dm.widthPixels;
        H = dm.heightPixels;
        u = W / 960f;
        TW = (int) (168 * u);
        TH = (int) (94 * u);

        loadFonts();
        loadApps();
        appSignature = computeSignature();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(FrameLayout.FOCUS_BLOCK_DESCENDANTS);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        int rows = (grid.size() + COLS - 1) / COLS;
        int contentH = Math.max(H, (int) (H + 40 * u + rows * 150 * u + 70 * u));
        content = new FrameLayout(this);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, contentH));

        // ambient glow behind the dock app name
        glow = new View(this);
        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(380 * u);
        setGlowColor(glowColor);
        glow.setBackground(glowDrawable);
        int bandH = (int) (138 * u);
        int bandTop = H - (int) (40 * u) - bandH;
        int nameY = bandTop - (int) (84 * u);
        int glowW = (int) (760 * u), glowH = (int) (420 * u);
        place(content, glow, (W - glowW) / 2, nameY + (int) (28 * u) - glowH / 2, glowW, glowH);

        // greeting, clock, date (top left)
        greeting = text("", 20, tfLight, 0xB3FFFFFF);
        place(content, greeting, (int) (60 * u), (int) (40 * u), -2, -2);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        styleText(clock, 84, tfThin, Color.WHITE);
        place(content, clock, (int) (54 * u), (int) (62 * u), -2, -2);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        styleText(date, 20, tfLight, 0xB3FFFFFF);
        place(content, date, (int) (60 * u), (int) (160 * u), -2, -2);
        updateGreeting();

        // dock app name
        bigName = text("", 46, tfLight, Color.WHITE);
        bigName.setGravity(Gravity.CENTER);
        place(content, bigName, 0, nameY, W, (int) (56 * u));

        // dock band
        View band = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xFF1C1C1E);
        bd.setCornerRadius(32 * u);
        bd.setStroke(Math.max(1, (int) (1 * u)), 0xFF2A2A2C);
        band.setBackground(bd);
        place(content, band, colX(0) - (int) (30 * u), bandTop, COLS * TW + (COLS - 1) * (int) (24 * u) + (int) (60 * u), bandH);

        int dockY = bandTop + (bandH - TH) / 2;
        for (int i = 0; i < dock.size(); i++) {
            App a = dock.get(i);
            a.tile = makeTile(a);
            place(content, a.tile, colX(i), dockY, TW, TH);
        }
        for (int i = 0; i < grid.size(); i++) {
            App a = grid.get(i);
            a.tile = makeTile(a);
            int x = colX(i % COLS), y = gridY(i / COLS);
            place(content, a.tile, x, y, TW, TH);
            a.name = text(a.label, 15, tfReg, Color.WHITE);
            a.name.setGravity(Gravity.CENTER);
            a.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            a.name.setAlpha(0f);
            place(content, a.name, x - (int) (12 * u), y + TH + (int) (14 * u), TW + (int) (24 * u), (int) (24 * u));
        }

        // frosted pills, top right
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"Inputs", "Wi-Fi", "Settings"};
        for (int i = 0; i < 3; i++) {
            TextView p = text(labels[i], 17, tfReg, Color.WHITE);
            p.setGravity(Gravity.CENTER);
            int pad = (int) (20 * u);
            p.setPadding(pad, 0, pad, 0);
            GradientDrawable g = new GradientDrawable();
            g.setColor(0x33FFFFFF);
            g.setCornerRadius(20 * u);
            p.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, (int) (40 * u));
            if (i > 0) lp.leftMargin = (int) (12 * u);
            bar.addView(p, lp);
            pills[i] = p;
        }
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        blp.topMargin = (int) (36 * u);
        blp.rightMargin = (int) (56 * u);
        root.addView(bar, blp);

        // inputs dropdown (hidden until opened)
        menuPanel = new LinearLayout(this);
        menuPanel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable md = new GradientDrawable();
        md.setColor(0xF0262628);
        md.setCornerRadius(20 * u);
        md.setStroke(Math.max(1, (int) (1 * u)), 0x40FFFFFF);
        menuPanel.setBackground(md);
        int mp = (int) (8 * u);
        menuPanel.setPadding(mp, mp, mp, mp);
        menuPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        root.addView(menuPanel, mlp);

        // the one glass highlight, on top of everything
        glass = new GlassView(this);
        glass.setStrokeWidth(Math.max(1.5f, 1.5f * u));
        glass.attachScroll(scroll);
        root.addView(glass, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        updateWifi();

        // restore the last focused tile
        SharedPreferences sp = getSharedPreferences("state", MODE_PRIVATE);
        dockIdx = Math.max(0, Math.min(sp.getInt("dock", 0), Math.max(0, dock.size() - 1)));
        gridIdx = Math.max(0, Math.min(sp.getInt("grid", 0), Math.max(0, grid.size() - 1)));
        int z = sp.getInt("zone", Z_DOCK);
        if (z == Z_GRID && !grid.isEmpty()) zone = Z_GRID;
        else if (!dock.isEmpty()) zone = Z_DOCK;
        else if (!grid.isEmpty()) zone = Z_GRID;
        else zone = Z_TOP;
        topIdx = 0;
        scrollTarget = 0;
        scaledTile = null;
        shownLabel = null;

        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                applyFocus(false);
            }
        });
    }

    private int colX(int c) {
        int total = COLS * TW + (COLS - 1) * (int) (24 * u);
        return (W - total) / 2 + c * (TW + (int) (24 * u));
    }

    private int gridY(int r) {
        return (int) (H + 40 * u + r * 150 * u);
    }

    private void place(FrameLayout parent, View v, int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = x;
        lp.topMargin = y;
        parent.addView(v, lp);
    }

    private TextView text(String s, float sizeU, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        styleText(t, sizeU, tf, color);
        t.setSingleLine(true);
        return t;
    }

    private void styleText(TextView t, float sizeU, Typeface tf, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeU * u);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
    }

    private void loadFonts() {
        tfThin = font("fonts/Outfit-Thin.ttf", "sans-serif-thin");
        tfLight = font("fonts/Outfit-Light.ttf", "sans-serif-light");
        tfReg = font("fonts/Outfit-Regular.ttf", "sans-serif");
    }

    private Typeface font(String asset, String fallback) {
        try {
            return Typeface.createFromAsset(getAssets(), asset);
        } catch (Exception e) {
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }

    private View makeTile(App a) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(a.image);
        iv.setBackgroundColor(0xFF2C2C2E);
        if (a.banner) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = (int) (14 * u);
            iv.setPadding(p, p, p, p);
        }
        final float r = 10 * u;
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        return iv;
    }

    // ------------------------------------------------------------------ apps

    private void loadApps() {
        dock.clear();
        grid.clear();
        PackageManager pm = getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || byPkg.containsKey(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.cn = new ComponentName(pkg, ri.activityInfo.name);
                a.label = String.valueOf(ri.loadLabel(pm));
                Drawable b = null;
                try {
                    b = ri.activityInfo.loadBanner(pm);
                    if (b == null) b = ri.activityInfo.applicationInfo.loadBanner(pm);
                } catch (Exception ignored) {
                }
                if (b != null) {
                    a.image = b;
                    a.banner = true;
                } else {
                    a.image = ri.loadIcon(pm);
                    a.banner = false;
                }
                a.glow = sampleColor(a.image);
                byPkg.put(pkg, a);
            }
        }
        for (String p : DOCK_PKGS) {
            App a = byPkg.remove(p);
            if (a != null) dock.add(a);
        }
        for (String p : HIDDEN_PKGS) byPkg.remove(p);
        grid.addAll(byPkg.values());
        Collections.sort(grid, (x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    private String computeSignature() {
        PackageManager pm = getPackageManager();
        List<String> names = new ArrayList<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                names.add(ri.activityInfo.packageName);
            }
        }
        Collections.sort(names);
        return names.toString();
    }

    /** Average colour of the image, nudged towards a vivid tone so the glow reads on black. */
    private int sampleColor(Drawable d) {
        try {
            Drawable.ConstantState cs = d.getConstantState();
            Drawable copy = cs != null ? cs.newDrawable().mutate() : d;
            Bitmap bm = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, 16, 16);
            copy.draw(new Canvas(bm));
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int c = bm.getPixel(x, y);
                    if (Color.alpha(c) < 128) continue;
                    r += Color.red(c);
                    g += Color.green(c);
                    b += Color.blue(c);
                    n++;
                }
            }
            bm.recycle();
            if (n == 0) return 0xFF5B8DEF;
            float[] hsv = new float[3];
            Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.85f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF5B8DEF;
        }
    }

    // ------------------------------------------------------------------ top bar info

    private void updateGreeting() {
        if (greeting == null) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening");
    }

    private void updateWifi() {
        if (pills[1] == null) return;
        String s = "Offline";
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc != null) {
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    s = "Ethernet";
                } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    s = "Wi-Fi";
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                    String ssid = wi == null ? null : wi.getSSID();
                    if (ssid != null && !ssid.contains("unknown ssid")) s = ssid.replace("\"", "");
                } else {
                    s = "Connected";
                }
            }
        } catch (Exception ignored) {
        }
        if (!s.contentEquals(pills[1].getText())) {
            pills[1].setText(s);
            if (zone == Z_TOP) bar.post(() -> applyFocus(false));
        }
    }

    private void registerNet() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    updateWifi();
                }

                @Override
                public void onLost(Network n) {
                    updateWifi();
                }
            };
            cm.registerDefaultNetworkCallback(netCallback, new Handler(Looper.getMainLooper()));
        } catch (Exception ignored) {
            netCallback = null;
        }
    }

    private void unregisterNet() {
        try {
            if (netCallback != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        netCallback = null;
    }

    // ------------------------------------------------------------------ focus

    private void setGlowColor(int c) {
        glowColor = c;
        int core = (c & 0x00FFFFFF) | 0x70000000;
        int edge = c & 0x00FFFFFF;
        glowDrawable.setColors(new int[]{core, edge});
    }

    private void animateGlowTo(int target) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, target);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlowColor((Integer) a.getAnimatedValue()));
        glowAnim.start();
    }

    private void setScroll(int target, boolean animate) {
        int max = Math.max(0, content.getLayoutParams().height - H);
        target = Math.max(0, Math.min(target, max));
        scrollTarget = target;
        if (scrollAnim != null) scrollAnim.cancel();
        if (!animate) {
            scroll.scrollTo(0, target);
            glass.invalidate();
            return;
        }
        scrollAnim = ObjectAnimator.ofInt(scroll, "scrollY", target);
        scrollAnim.setDuration(340);
        scrollAnim.setInterpolator(new DecelerateInterpolator());
        scrollAnim.start();
    }

    private RectF scaledRect(int x, int y) {
        float w = TW * SCALE + 12 * u, h = TH * SCALE + 12 * u;
        float cx = x + TW / 2f, cy = y + TH / 2f;
        return new RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    }

    private RectF windowRect(View v, float pad) {
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        return new RectF(loc[0] - pad, loc[1] - pad, loc[0] + v.getWidth() + pad, loc[1] + v.getHeight() + pad);
    }

    private void applyFocus(boolean animate) {
        if (root == null) return;
        if (scaledTile != null) {
            scaledTile.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
            scaledTile = null;
        }
        if (shownLabel != null) {
            shownLabel.animate().alpha(0f).setDuration(150).start();
            shownLabel = null;
        }

        if (zone == Z_TOP) {
            glass.moveTo(windowRect(pills[topIdx], 2 * u), false, 22 * u, animate);
            setScroll(0, animate);
            showDockName(animate);
        } else if (zone == Z_DOCK && !dock.isEmpty()) {
            App a = dock.get(dockIdx);
            int dockY = H - (int) (40 * u) - (int) (138 * u) + ((int) (138 * u) - TH) / 2;
            glass.moveTo(scaledRect(colX(dockIdx), dockY), true, 14 * u, animate);
            a.tile.animate().scaleX(SCALE).scaleY(SCALE).setDuration(220).start();
            scaledTile = a.tile;
            setScroll(0, animate);
            showDockName(animate);
        } else if (zone == Z_GRID && !grid.isEmpty()) {
            App a = grid.get(gridIdx);
            int row = gridIdx / COLS;
            int x = colX(gridIdx % COLS), y = gridY(row);
            // scroll: jump to a comfortable position when entering the grid, then scroll minimally
            int target = scrollTarget;
            if (scrollTarget == 0) {
                target = (int) (y - 0.34f * H);
            } else {
                int top = y - (int) (110 * u);
                int bottom = y + TH + (int) (70 * u);
                if (top < scrollTarget) target = top;
                else if (bottom > scrollTarget + H) target = bottom - H;
            }
            setScroll(target, animate);
            glass.moveTo(scaledRect(x, y), true, 14 * u, animate);
            a.tile.animate().scaleX(SCALE).scaleY(SCALE).setDuration(220).start();
            scaledTile = a.tile;
            a.name.animate().alpha(1f).setDuration(200).start();
            shownLabel = a.name;
            hideDockName(animate);
        } else if (zone == Z_MENU && menuPanel.getChildCount() > 0) {
            glass.moveTo(windowRect(menuPanel.getChildAt(menuIdx), 0), false, 14 * u, animate);
        }

        boolean barVisible = scrollTarget < 120 * u;
        bar.animate().alpha(barVisible ? 1f : 0f).setDuration(200).start();
        persist();
    }

    private void showDockName(boolean animate) {
        if (dock.isEmpty()) return;
        App a = dock.get(Math.min(dockIdx, dock.size() - 1));
        bigName.setText(a.label);
        bigName.animate().alpha(1f).setDuration(animate ? 220 : 0).start();
        glow.animate().alpha(1f).setDuration(animate ? 300 : 0).start();
        if (animate) animateGlowTo(a.glow);
        else setGlowColor(a.glow);
    }

    private void hideDockName(boolean animate) {
        bigName.animate().alpha(0f).setDuration(animate ? 150 : 0).start();
        glow.animate().alpha(0f).setDuration(animate ? 200 : 0).start();
    }

    private void persist() {
        if (zone != Z_DOCK && zone != Z_GRID) return;
        getSharedPreferences("state", MODE_PRIVATE).edit()
                .putInt("zone", zone).putInt("dock", dockIdx).putInt("grid", gridIdx).apply();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BACK;
        if (!nav) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) handleKey(k, e.getRepeatCount());
        return true;
    }

    private void handleKey(int k, int repeat) {
        boolean up = k == KeyEvent.KEYCODE_DPAD_UP, down = k == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean left = k == KeyEvent.KEYCODE_DPAD_LEFT, right = k == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean back = k == KeyEvent.KEYCODE_BACK;
        boolean ok = !(up || down || left || right || back);
        if ((ok || back) && repeat > 0) return;

        switch (zone) {
            case Z_TOP:
                if (left) {
                    if (topIdx > 0) topIdx--;            // ends of the bar do not fall through
                } else if (right) {
                    if (topIdx < pills.length - 1) topIdx++;
                } else if (down) {
                    if (!dock.isEmpty()) zone = Z_DOCK;
                    else if (!grid.isEmpty()) zone = Z_GRID;
                } else if (ok) {
                    activatePill();
                    return;
                }
                break;

            case Z_DOCK:
                if (left) {
                    if (dockIdx > 0) dockIdx--;
                } else if (right) {
                    if (dockIdx < dock.size() - 1) dockIdx++;
                } else if (up) {
                    zone = Z_TOP;
                } else if (down) {
                    if (!grid.isEmpty()) {
                        gridIdx = Math.min(dockIdx, grid.size() - 1);   // same column, first row
                        zone = Z_GRID;
                    }
                } else if (ok) {
                    launch(dock.get(dockIdx));
                    return;
                }
                break;

            case Z_GRID:
                int col = gridIdx % COLS;
                if (left) {
                    if (col > 0) gridIdx--;
                } else if (right) {
                    if (col < COLS - 1 && gridIdx + 1 < grid.size()) gridIdx++;
                } else if (up) {
                    if (gridIdx >= COLS) {
                        gridIdx -= COLS;
                    } else if (!dock.isEmpty()) {
                        dockIdx = Math.min(col, dock.size() - 1);
                        zone = Z_DOCK;
                    } else {
                        zone = Z_TOP;
                    }
                } else if (down) {
                    if (gridIdx + COLS < grid.size()) gridIdx += COLS;
                    else if ((gridIdx / COLS + 1) * COLS < grid.size()) gridIdx = grid.size() - 1;
                } else if (back) {
                    if (!dock.isEmpty()) zone = Z_DOCK;
                } else if (ok) {
                    launch(grid.get(gridIdx));
                    return;
                }
                break;

            case Z_MENU:
                if (up) {
                    if (menuIdx > 0) menuIdx--;
                } else if (down) {
                    if (menuIdx < menuInputs.size() - 1) menuIdx++;
                } else if (left || right || back) {
                    closeMenu();
                    return;
                } else if (ok) {
                    Input in = menuInputs.get(menuIdx);
                    closeMenuQuiet();
                    zone = Z_TOP;
                    switchInput(in);
                    applyFocus(false);
                    return;
                }
                break;
            default:
                break;
        }
        applyFocus(true);
    }

    // ------------------------------------------------------------------ actions

    private void launch(App a) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(a.cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ex) {
            toast("Can't open " + a.label);
        }
    }

    private void activatePill() {
        if (topIdx == 0) {
            openMenu();
        } else if (topIdx == 1) {
            openSettings(Settings.ACTION_WIFI_SETTINGS);
        } else {
            openSettings(Settings.ACTION_SETTINGS);
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException ex) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ex2) {
                toast("Settings not available");
            }
        }
    }

    private void openMenu() {
        menuInputs = loadInputs();
        if (menuInputs.isEmpty()) {
            toast("No inputs found");
            return;
        }
        menuPanel.removeAllViews();
        for (Input in : menuInputs) {
            TextView t = text(in.label, 17, tfReg, Color.WHITE);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding((int) (16 * u), 0, (int) (16 * u), 0);
            menuPanel.addView(t, new LinearLayout.LayoutParams((int) (230 * u), (int) (42 * u)));
        }
        int[] loc = new int[2];
        pills[0].getLocationInWindow(loc);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuPanel.getLayoutParams();
        lp.leftMargin = loc[0];
        lp.topMargin = loc[1] + pills[0].getHeight() + (int) (10 * u);
        menuPanel.setLayoutParams(lp);
        menuPanel.setVisibility(View.VISIBLE);
        menuIdx = 0;
        zone = Z_MENU;
        menuPanel.post(() -> applyFocus(true));
    }

    private void closeMenu() {
        closeMenuQuiet();
        zone = Z_TOP;
        topIdx = 0;
        applyFocus(true);
    }

    private void closeMenuQuiet() {
        if (menuPanel != null) menuPanel.setVisibility(View.GONE);
        if (zone == Z_MENU) zone = Z_TOP;
    }

    private List<Input> loadInputs() {
        List<Input> out = new ArrayList<>();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return out;
            for (TvInputInfo info : tim.getTvInputList()) {
                int t = info.getType();
                if (t == TvInputInfo.TYPE_OTHER) continue;
                try {
                    if (info.isHidden(this)) continue;
                } catch (Exception ignored) {
                }
                Input in = new Input();
                in.id = info.getId();
                in.type = t;
                in.passthrough = info.isPassthroughInput();
                CharSequence l = null;
                try {
                    l = info.loadCustomLabel(this);
                } catch (Exception ignored) {
                }
                if (l == null || l.length() == 0) l = info.loadLabel(this);
                in.label = t == TvInputInfo.TYPE_TUNER ? "Live TV" : String.valueOf(l);
                out.add(in);
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> {
            int ta = a.type == TvInputInfo.TYPE_TUNER ? 0 : 1, tb = b.type == TvInputInfo.TYPE_TUNER ? 0 : 1;
            if (ta != tb) return ta - tb;
            return a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT));
        });
        return out;
    }

    private void switchInput(Input in) {
        Uri uri = in.passthrough
                ? TvContract.buildChannelUriForPassthroughInput(in.id)
                : TvContract.buildChannelsUriForInput(in.id);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (Exception ignored) {
        }
        if (!in.passthrough) {
            // OnePlus Live TV app as a fallback for the tuner
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("com.heytap.tv.livetv");
            if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.heytap.tv.livetv");
            if (i != null) {
                try {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (Exception ignored) {
                }
            }
        }
        toast("Couldn't switch to " + in.label);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
