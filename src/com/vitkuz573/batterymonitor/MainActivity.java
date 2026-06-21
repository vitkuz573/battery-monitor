package com.vitkuz573.batterymonitor;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

public class MainActivity extends android.app.Activity {

    private LinearLayout contentArea, tabStrip;
    private TextView headerPercent, headerStatus, headerMeta;
    private Button btnRefresh, btnService;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoRefresh;
    private boolean serviceRunning = false, loading = false, destroyed = false;
    private int currentTab = 0;
    private String currentAppPkg;
    private float density;

    private static final String[] TABS = {"BATTERY","CPU","NETWORK","STORAGE","SYSTEM","LOCKS","APPS"};
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int DIM = 0xFF9E9E9E;
    private static final int SURFACE = 0xFF2D2D2D;

    private GestureDetector gestureDetector;
    private SocDatabase socDb;
    private String findSoc(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        SocDatabase.SocInfo info = socDb.findSoc(raw);
        return info != null ? info.name : null;
    }

    private String readSysfs(String path) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(path));
            String v = r.readLine();
            r.close();
            return v != null ? v.trim() : "";
        } catch (Exception e) { return ""; }
    }

    private String refineSoc(String base, String hw) {
        String lc = hw != null ? hw.toLowerCase(Locale.US) : "";
        String rev = exec("cat /sys/devices/soc0/revision 2>/dev/null || echo ''").trim();
        String machine = exec("cat /sys/devices/soc0/machine 2>/dev/null || echo ''").trim().toLowerCase(Locale.US);
        String socModel = exec("getprop ro.soc.model 2>/dev/null").trim().toLowerCase(Locale.US);
        String boardPlat = exec("getprop ro.board.platform 2>/dev/null").trim().toLowerCase(Locale.US);

        if (machine.contains("sm8250") || socModel.contains("sm8250") || boardPlat.contains("kona") || lc.contains("kona")) {
            if (rev.startsWith("2.")) return "Snapdragon 870 (rev " + rev + ")";
            if (rev.startsWith("1.")) return "Snapdragon 865+ (rev " + rev + ")";
            if (!rev.isEmpty()) return "Snapdragon 865 (rev " + rev + ")";
            return "Snapdragon 870";
        }
        if (machine.contains("sm8350") || socModel.contains("sm8350") || boardPlat.contains("lahaina") || lc.contains("lahaina")) {
            if (rev.startsWith("2.")) return "Snapdragon 888+ (rev " + rev + ")";
            return "Snapdragon 888 (rev " + rev + ")";
        }
        if (machine.contains("sm8450") || boardPlat.contains("waipio") || lc.contains("waipio"))
            return "Snapdragon 8 Gen 1 (rev " + rev + ")";
        if (machine.contains("sm8475") || boardPlat.contains("waipio") || lc.contains("waipio"))
            return "Snapdragon 8+ Gen 1 (rev " + rev + ")";
        if (machine.contains("sm8550") || boardPlat.contains("kalama") || lc.contains("kalama"))
            return "Snapdragon 8 Gen 2 (rev " + rev + ")";
        if (machine.contains("sm8650") || boardPlat.contains("pineapple") || lc.contains("pineapple"))
            return "Snapdragon 8 Gen 3 (rev " + rev + ")";
        if (machine.contains("sm7250") || boardPlat.contains("lito") || lc.contains("lito"))
            return "Snapdragon 765/768G (rev " + rev + ")";
        if (machine.contains("sm7150") || boardPlat.contains("sm7150") || lc.contains("sm7150"))
            return "Snapdragon 730/732G (rev " + rev + ")";
        if (machine.contains("shima") || boardPlat.contains("shima") || lc.contains("shima"))
            return "Snapdragon 780G (rev " + rev + ")";
        if (machine.contains("yupik") || boardPlat.contains("yupik") || lc.contains("yupik"))
            return "Snapdragon 778G+ (rev " + rev + ")";

        if (base != null && !base.isEmpty()) {
            if (rev.isEmpty()) return base;
            return base + " (rev " + rev + ")";
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;

        headerPercent = findViewById(R.id.battery_percent);
        headerStatus = findViewById(R.id.battery_status);
        headerMeta = findViewById(R.id.battery_temp_voltage);
        contentArea = findViewById(R.id.content_area);
        tabStrip = findViewById(R.id.tab_bar);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnService = findViewById(R.id.btn_service);

        socDb = new SocDatabase();
        socDb.loadRemote(this, () -> { if (!destroyed) refreshAll(); });

        tabStrip.removeAllViews();
        tabStrip.setPadding(0, 0, 0, 0);
        buildTabs();

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > 120 && Math.abs(vX) > 200) {
                    int next = dx > 0 ? currentTab - 1 : currentTab + 1;
                    if (next >= 0 && next < TABS.length) switchTab(next);
                    return true;
                }
                return false;
            }
        });
        ((ScrollView) findViewById(R.id.content_scroll)).setOnTouchListener((v, e) -> {
            gestureDetector.onTouchEvent(e);
            return false;
        });

        btnRefresh.setOnClickListener(v -> refreshAll());
        btnService.setOnClickListener(v -> toggleService());

        switchTab(0);
        autoRefresh = () -> { if (destroyed) return; updateHeader(); if (!loading && currentTab != 6) refreshAll(); handler.postDelayed(autoRefresh, 5000); };
        handler.postDelayed(autoRefresh, 5000);
    }

    // ==================== TABS ====================

    private void buildTabs() {
        int minW = (int) (90 * density);
        int padH = (int) (18 * density);
        int padV = (int) (10 * density);
        int indH = (int) (3 * density);

        for (int i = 0; i < TABS.length; i++) {
            LinearLayout tab = new LinearLayout(this);
            tab.setOrientation(LinearLayout.VERTICAL);
            tab.setPadding(padH, padV, padH, 0);
            tab.setMinimumWidth(minW);
            tab.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));

            TextView tv = new TextView(this);
            tv.setText(TABS[i]);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tab.addView(tv);

            View ind = new View(this);
            ind.setLayoutParams(new LinearLayout.LayoutParams(-1, indH));
            ind.setBackgroundColor(ACCENT);
            tab.addView(ind);

            int fi = i;
            tab.setOnClickListener(v -> switchTab(fi));
            tabStrip.addView(tab);
        }
        updateTabs();
    }

    private void updateTabs() {
        for (int i = 0; i < TABS.length; i++) {
            LinearLayout tab = (LinearLayout) tabStrip.getChildAt(i);
            TextView tv = (TextView) tab.getChildAt(0);
            View ind = tab.getChildAt(1);
            boolean sel = i == currentTab;
            tv.setTextColor(sel ? ACCENT : DIM);
            tv.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
            ind.setVisibility(sel ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void switchTab(int i) {
        if (i == currentTab && contentArea.getChildCount() > 0) return;
        currentTab = i;
        updateTabs();
        refreshAll();
    }

    private void refreshAll() {
        int t = currentTab;
        if (t == 0) { showBattery(); return; }
        if (t == 6) { showApps(); return; }
        if (loading) return;
        loading = true;
        if (t == 1) bg(this::loadCpu); else if (t == 2) bg(this::loadNetwork);
        else if (t == 3) bg(this::loadStorage); else if (t == 4) bg(this::loadSystem);
        else if (t == 5) bg(this::loadWakelocks);
    }

    private void bg(Runnable r) { new Thread(r).start(); }

    // ==================== BATTERY (sync) ====================

    private void showBattery() {
        contentArea.removeAllViews();
        loading = false;
        Intent bat = getBatteryIntent();
        if (bat == null) { addCard("Battery", new String[][]{{"Error","No data"}}); return; }

        int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level * 100 / Math.max(scale, 1);
        int tempC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
        int voltage = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int health = bat.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plug = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        String tech = bat.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
        if (tech == null) tech = "N/A";

        addCard("Battery", new String[][]{
            {"Level", pct + "%"},
            {"Status", status == BatteryManager.BATTERY_STATUS_CHARGING ? "\u26A1 Charging" :
                       status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "Discharging" :
                       status == BatteryManager.BATTERY_STATUS_FULL ? "Full" : "Idle"},
            {"Health", health == BatteryManager.BATTERY_HEALTH_GOOD ? "Good" :
                       health == BatteryManager.BATTERY_HEALTH_OVERHEAT ? "Overheat" :
                       health == BatteryManager.BATTERY_HEALTH_DEAD ? "Dead" : "Unknown"},
            {"Temperature", tempC + "\u00B0C"},
            {"Voltage", voltage + " mV"},
            {"Technology", tech},
            {"Plugged", plug == BatteryManager.BATTERY_PLUGGED_AC ? "AC" :
                        plug == BatteryManager.BATTERY_PLUGGED_USB ? "USB" :
                        plug == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "Wireless" : "Battery"},
            {"Power Save", isPowerSaveMode() ? "ON" : "OFF"},
        });
        addProgressBar(pct, pct > 20 ? 0xFF81C784 : 0xFFFFD54F);

        if (Build.VERSION.SDK_INT >= 21) try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                int cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                int cn = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                int ca = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE);
                addCard("BatteryManager", new String[][]{
                    {"Charge counter", cc + " mAh"},
                    {"Capacity", cap + "%"},
                    {"Current now", cn + " \u00B5A"},
                    {"Current avg", ca + " \u00B5A"},
                });
            }
        } catch (SecurityException e) {
            addCard("BatteryManager", new String[][]{{"Note","Requires BATTERY_STATS perm"}});
        }
        updateHeader();
    }

    // ==================== CPU (bg) ====================

    private void loadCpu() {
        String cpuInfo = exec("cat /proc/cpuinfo 2>/dev/null || echo ''");
        String memInfo = exec("cat /proc/meminfo 2>/dev/null || echo ''");
        String load = exec("uptime 2>/dev/null | sed 's/.*load average://' || echo '0.00,0.00,0.00'");
        String freqs = exec("for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq; do "
            + "echo \"$(basename $(dirname $f)): $(( $(cat $f 2>/dev/null || echo 0) / 1000 )) MHz\"; done 2>/dev/null || echo ''");
        String top = exec("ps -Ao pid,pcpu,comm --sort=-pcpu 2>/dev/null | head -20 || echo ''");

        String[] lp = load.trim().split("[\\s,]+");
        String ld = lp.length >= 3 ? lp[0] + " / " + lp[1] + " / " + lp[2] : "N/A";
        int cores = Runtime.getRuntime().availableProcessors();
        String model = extractModel(cpuInfo);
        long total = parseMem(memInfo, "MemTotal");
        long avail = parseMem(memInfo, "MemAvailable");
        long used = total - avail;
        int mp = total > 0 ? (int)(used * 100 / total) : 0;
        long st = parseMem(memInfo, "SwapTotal"), sf = parseMem(memInfo, "SwapFree");
        String[] fl = freqs.split("\n");

        handler.post(() -> { if (destroyed) return;
            contentArea.removeAllViews();
            String vendorName = "";
            String archName = "";
            String hwRaw = extractRawHw();
            if (!hwRaw.isEmpty()) {
                SocDatabase.SocInfo si = socDb.findSoc(hwRaw);
                if (si != null) {
                    if (si.vendor != null && !si.vendor.isEmpty()) vendorName = si.vendor;
                    if (si.architecture != null && !si.architecture.isEmpty()) archName = si.architecture;
                }
            }
            java.util.ArrayList<String[]> procRows = new java.util.ArrayList<>();
            procRows.add(new String[]{"Cores", String.valueOf(cores)});
            procRows.add(new String[]{"Model", model.isEmpty() ? "N/A" : model});
            if (!vendorName.isEmpty()) procRows.add(new String[]{"Vendor", vendorName});
            if (!archName.isEmpty()) procRows.add(new String[]{"Architecture", archName});
            procRows.add(new String[]{"Load 1/5/15m", ld});
            addCard("Processor", procRows.toArray(new String[0][]));
            addCard("Memory", new String[][]{
                {"Total", fmt(total*1024)},
                {"Used", fmt(used*1024) + " (" + mp + "%)"},
                {"Available", fmt(avail*1024)},
                {"Swap", fmt(st*1024) + " (free: " + fmt(sf*1024) + ")"},
            });
            addProgressBar(mp, ACCENT);
            if (fl.length > 0 && !fl[0].isEmpty()) {
                String[][] fd = new String[fl.length][2];
                for (int i = 0; i < fl.length; i++) { String[] p = fl[i].split(":",2); fd[i][0]=p[0].trim(); fd[i][1]=p.length>1?p[1].trim():""; }
                addCard("CPU Frequencies", fd);
            }
            if (!top.isEmpty()) addCard("Top Processes", lines(top, 20));
            loading = false;
        });
    }

    // ==================== NETWORK (bg) ====================

    private void loadNetwork() {
        String ifaces = exec("ip -o addr show 2>/dev/null | awk '$2!~/^lo/{print $2,$3,$4}'|head -10||echo ''");
        String tr = exec("cat /proc/net/dev 2>/dev/null | tail -n+3 | head -10 || echo ''");
        String wifi = exec("dumpsys wifi 2>/dev/null | grep -E 'SSID|rssi|linkSpeed|mWifiInfo'|head -8||echo ''");
        String bg = exec("cmd netpolicy get restrict-background 2>/dev/null||echo 'N/A'");
        handler.post(() -> { if (destroyed) return; contentArea.removeAllViews();
            if (!ifaces.isEmpty()) addCard("Interfaces", lines(ifaces,10));
            if (!tr.isEmpty()) addCard("Traffic (RX / TX)", lines(tr,10));
            if (!wifi.isEmpty()) addCard("WiFi", lines(wifi,8));
            addCard("Background data", new String[][]{{"Restricted", bg.trim()}});
            loading = false; });
    }

    // ==================== STORAGE (bg) ====================

    private void loadStorage() {
        String df = exec("df -h /data /storage/emulated /sdcard /system 2>/dev/null||df /data 2>/dev/null||echo ''");
        String mounts = exec("mount|grep -E '^/dev|^tmpfs'|awk '{print $1,$3}'|head -20||echo ''");
        handler.post(() -> { if (destroyed) return; contentArea.removeAllViews();
            if (!df.isEmpty()) addCard("Disk Usage", lines(df,15));
            if (!mounts.isEmpty()) addCard("Mount Points", lines(mounts,15));
            loading = false; });
    }

    // ==================== SYSTEM (bg) ====================

    private void loadSystem() {
        String props = exec("getprop ro.product.manufacturer ro.product.model ro.build.version.release "
            + "ro.build.display.id ro.product.cpu.abi 2>/dev/null||echo ''");
        String kernel = exec("cat /proc/version 2>/dev/null|cut -d' ' -f3-5||echo 'N/A'");
        String uptime = exec("uptime 2>/dev/null|sed 's/.*up/up/'|sed 's/,.*//'||echo 'N/A'");
        String thermal = exec("for z in /sys/class/thermal/thermal_zone*; do "
            + "echo \"$(cat $z/type 2>/dev/null): $(cat $z/temp 2>/dev/null)\"; done 2>/dev/null|head -10||echo ''");
        String sensors = exec("dumpsys sensorservice 2>/dev/null|grep -E 'Sensor [0-9]+:'|head -10||echo ''");
        String apps = exec("cmd package list packages -3 2>/dev/null|wc -l||echo '0'");
        String[] pl = props.split("\n");
        handler.post(() -> { if (destroyed) return; contentArea.removeAllViews();
            addCard("Device", new String[][]{
                {"Manufacturer", pl.length>0?pl[0]:"N/A"},{"Model",pl.length>1?pl[1]:"N/A"},
                {"Android",pl.length>2?pl[2]:"N/A"},{"ABI",pl.length>4?pl[4]:"N/A"},
                {"Kernel",kernel},{"Uptime",uptime},
            });
            if (pl.length>3) { String b=pl[3]; if(b.length()>50)b=b.substring(0,47)+"..."; addCard("Build",new String[][]{{"Display ID",b}}); }
            String[][] td = parseThermal(thermal); if(td.length>0) addCard("Thermal Zones", td);
            addCard("User apps", new String[][]{{"Count", apps.trim()}});
            if(!sensors.isEmpty()) addCard("Sensors", lines(sensors,10));

            // Display info
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            String res = dm.widthPixels + "x" + dm.heightPixels;
            String dpi = dm.densityDpi + "dpi (" + String.format(Locale.US, "%.1f", dm.density) + "x)";
            String rr = "N/A";
            if (Build.VERSION.SDK_INT >= 30) {
                rr = String.format(Locale.US, "%.1f Hz", getDisplay().getRefreshRate());
            }
            addCard("Display", new String[][]{
                {"Resolution", res},
                {"Density", dpi},
                {"Refresh", rr},
            });
            loading = false; });
    }

    // ==================== WAKELOCKS (bg) ====================

    private void loadWakelocks() {
        String kw = exec("cat /proc/wakelocks 2>/dev/null|head -25||cat /sys/kernel/debug/wakeup_sources 2>/dev/null|head -25||echo 'N/A'");
        String act = exec("dumpsys power 2>/dev/null|grep -A5 'Wake Locks:'|head -20||echo 'N/A'");
        String sus = exec("dumpsys power 2>/dev/null|grep -A20 'Suspend Blockers:'|head -25||echo 'N/A'");
        String doze = exec("dumpsys deviceidle 2>/dev/null|grep -E 'mState|mScreenOn|mCharging'|head -5||echo ''");
        handler.post(() -> { if (destroyed) return; contentArea.removeAllViews();
            addCard("Kernel Wakelocks", lines(kw,25));
            addCard("Active Wakelocks", lines(act,20));
            addCard("Suspend Blockers", lines(sus,20));
            if(!doze.isEmpty()) addCard("Doze / Idle", lines(doze,5));
            loading = false; });
    }

    // ==================== APPS ====================

    private void showApps() {
        contentArea.removeAllViews();
        new Thread(() -> {
            String[] pkgList = new String[0];

            // Approach 1: Read /data/app/ directory from Java
            try {
                java.io.File dir = new java.io.File("/data/app/");
                java.io.File[] files = dir.listFiles();
                if (files != null && files.length > 0) {
                    java.util.TreeSet<String> pkgs = new java.util.TreeSet<>();
                    for (java.io.File f : files) {
                        String name = f.getName();
                        int dash = name.indexOf('-');
                        if (dash > 0) pkgs.add(name.substring(0, dash));
                    }
                    pkgList = pkgs.toArray(new String[0]);
                }
            } catch (Exception ignored) {}

            // Approach 2: Shell fallback
            if (pkgList.length == 0) {
                String shellPkgs = exec("pm list packages -3 2>/dev/null|sed 's/^package://'|sort||echo ''");
                if (!shellPkgs.isEmpty() && !shellPkgs.equals("")) {
                    pkgList = shellPkgs.split("\n");
                    if (pkgList.length == 1 && pkgList[0].isEmpty()) pkgList = new String[0];
                }
            }

            // Approach 3: PackageManager API
            if (pkgList.length == 0) {
                try {
                    android.content.pm.PackageManager pm = getPackageManager();
                    java.util.List<android.content.pm.ApplicationInfo> apps =
                        pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);
                    java.util.ArrayList<String> list = new java.util.ArrayList<>();
                    for (android.content.pm.ApplicationInfo ai : apps) {
                        if ((ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                            list.add(ai.packageName);
                        }
                    }
                    java.util.Collections.sort(list);
                    pkgList = list.toArray(new String[0]);
                } catch (Exception ignored) {}
            }

            final String[] finalPkgs = pkgList;
            handler.post(() -> {
                if (destroyed) return;
                contentArea.removeAllViews();
                addInfo("Tap an app to view and toggle its components.");
                if (finalPkgs.length == 0) { addCard("User Apps", new String[][]{{"None",""}}); loading = false; return; }
                int chunk = 25;
                for (int start = 0; start < finalPkgs.length; start += chunk) {
                    int end = Math.min(start + chunk, finalPkgs.length);
                    int cnt = end - start;
                    String[][] rows = new String[cnt][2];
                    for (int i = 0; i < cnt; i++) { rows[i][0] = ""; rows[i][1] = finalPkgs[start + i]; }
                    addCard("Apps" + (start > 0 ? " (" + start + "-" + (end-1) + ")" : ""), rows);
                }
                makePackageRowsClickable();
                loading = false;
            });
        }).start();
    }

    private void makePackageRowsClickable() {
        for (int ci = 0; ci < contentArea.getChildCount(); ci++) {
            View cv = contentArea.getChildAt(ci);
            if (!(cv instanceof LinearLayout)) continue;
            LinearLayout card = (LinearLayout) cv;
            for (int ri = 0; ri < card.getChildCount(); ri++) {
                View rv = card.getChildAt(ri);
                if (!(rv instanceof LinearLayout)) continue;
                LinearLayout row = (LinearLayout) rv;
                if (row.getChildCount() < 2) continue;
                View valView = row.getChildAt(1);
                if (!(valView instanceof TextView)) continue;
                final String pkg = ((TextView) valView).getText().toString().trim();
                if (pkg.isEmpty() || !pkg.contains(".")) continue;
                row.setClickable(true);
                row.setBackgroundResource(android.R.drawable.list_selector_background);
                row.setOnClickListener(v -> openApp(pkg));
                row.setPadding(0, (int)(6*density), 0, (int)(6*density));
            }
        }
    }

    private void openApp(String pkg) {
        currentAppPkg = pkg;
        showLoading();
        new Thread(() -> {
            String raw = exec("dumpsys package " + pkg + " 2>/dev/null | head -200 || echo ''");
            java.util.HashSet<String> comps = new java.util.HashSet<>();
            java.util.HashSet<String> disabled = new java.util.HashSet<>();
            java.util.regex.Pattern refPat = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9._]*/[a-zA-Z0-9._]+");
            java.util.regex.Pattern shortPat = java.util.regex.Pattern.compile("[a-zA-Z0-9._]+/[a-zA-Z0-9._]+");
            boolean inDisabled = false;
            for (String line : raw.split("\n")) {
                java.util.regex.Matcher m = refPat.matcher(line);
                while (m.find()) comps.add(m.group());
                if (line.contains("disabledComponents:")) inDisabled = true;
                if (line.contains("enabledComponents:")) inDisabled = false;
                if (inDisabled) {
                    java.util.regex.Matcher dm = shortPat.matcher(line);
                    while (dm.find()) disabled.add(dm.group());
                }
            }

            handler.post(() -> {
                if (destroyed) return;
                contentArea.removeAllViews();
                addBackButton();
                addCard("Package", new String[][]{{"Name", pkg}});
                if (comps.isEmpty()) {
                    addInfo("No components found or no permission to read.");
                    return;
                }
                String[] allComps = comps.toArray(new String[0]);
                java.util.Arrays.sort(allComps);
                int limit = Math.min(allComps.length, 120);
                String[][] rows = new String[limit][2];
                for (int i = 0; i < limit; i++) {
                    String c = allComps[i].trim();
                    String shortName = c.contains("/") ? c.substring(c.indexOf('/') + 1) : c;
                    boolean isDisabled = disabled.contains(c) || disabled.contains(shortName);
                    rows[i][0] = shortName.length() > 28 ? shortName.substring(0,25)+"..." : shortName;
                    rows[i][1] = isDisabled ? "DISABLED" : "ENABLED";
                }
                addCard("Components (" + comps.size() + ")", rows);
                makeComponentRowsClickable(pkg, disabled, allComps);
            });
        }).start();
    }

    private void makeComponentRowsClickable(final String pkg, final java.util.HashSet<String> disabledSet, final String[] allComps) {
        for (int ci = 0; ci < contentArea.getChildCount(); ci++) {
            View cv = contentArea.getChildAt(ci);
            if (!(cv instanceof LinearLayout)) continue;
            LinearLayout card = (LinearLayout) cv;
            for (int ri = 0; ri < card.getChildCount(); ri++) {
                View rv = card.getChildAt(ri);
                if (!(rv instanceof LinearLayout)) continue;
                LinearLayout row = (LinearLayout) rv;
                if (row.getChildCount() < 2) continue;
                View valView = row.getChildAt(1);
                if (!(valView instanceof TextView)) continue;
                String compName = ((TextView) valView).getText().toString().trim();
                if (!compName.equals("ENABLED") && !compName.equals("DISABLED")) continue;
                int idx = ri - 1; // row 0 = Components title, row 1+ = actual data
                if (idx < 0 || idx >= allComps.length) continue;
                final String fullComp = allComps[idx];
                final boolean isDisabled = compName.equals("DISABLED");
                row.setClickable(true);
                row.setOnClickListener(v -> toggleComponent(pkg, fullComp, isDisabled));
                ((TextView) valView).setTextColor(isDisabled ? 0xFFEF5350 : 0xFF81C784);
            }
        }
    }

    private void toggleComponent(String pkg, String fullComp, boolean isDisabled) {
        String action = isDisabled ? "enable" : "disable";
        String result = exec("pm " + action + "-component " + fullComp + " 2>/dev/null || echo 'FAILED'");
        if (result.contains("New state:")) {
            String newState = isDisabled ? "ENABLED" : "DISABLED";
            String shortName = fullComp.contains("/") ? fullComp.substring(fullComp.indexOf('/') + 1) : fullComp;
            showToast(shortName.length() > 35 ? shortName.substring(0,32)+"..." : shortName + " \u2192 " + newState);
        } else {
            showToast(result.length() > 60 ? result.substring(0,57)+"..." : result);
        }
        // Refresh component view
        openApp(pkg);
    }

    private void addBackButton() {
        addCard("Actions", new String[][]{{"< Back to apps", ""}});
        // Make back row clickable
        for (int ci = 0; ci < contentArea.getChildCount(); ci++) {
            View cv = contentArea.getChildAt(ci);
            if (!(cv instanceof LinearLayout)) continue;
            LinearLayout card = (LinearLayout) cv;
            for (int ri = 0; ri < card.getChildCount(); ri++) {
                View rv = card.getChildAt(ri);
                if (!(rv instanceof LinearLayout)) continue;
                LinearLayout row = (LinearLayout) rv;
                if (row.getChildCount() < 2) continue;
                View kView = row.getChildAt(0);
                if (!(kView instanceof TextView)) continue;
                if ("< Back to apps".equals(((TextView) kView).getText().toString())) {
                    row.setClickable(true);
                    row.setOnClickListener(v -> { currentAppPkg = null; showApps(); });
                    ((TextView) row.getChildAt(0)).setTextColor(ACCENT);
                }
            }
        }
    }

    private void showLoading() {
        contentArea.removeAllViews();
        addInfo("Loading\u2026");
    }

    private void addInfo(String text) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(MP, -2);
        olp.setMargins(0, 0, 0, (int)(10*density));
        outer.setLayoutParams(olp);
        View bar = new View(this);
        bar.setBackgroundColor(DIM);
        bar.setLayoutParams(new LinearLayout.LayoutParams((int)(4*density), -1));
        outer.addView(bar);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_background);
        card.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        card.setPadding((int)(16*density), (int)(12*density), (int)(16*density), (int)(12*density));
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(DIM);
        card.addView(tv);
        outer.addView(card);
        contentArea.addView(outer);
    }

    private void showToast(final String msg) {
        handler.post(() -> android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    private String exec(String cmd) {
        Process p = null;
        BufferedReader r = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"sh","-c",cmd});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder o = new StringBuilder(1024);
            String l;
            while ((l = r.readLine()) != null) {
                if (o.length() > 32768) { o.append("... [truncated]"); break; }
                o.append(l).append('\n');
            }
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while (er.readLine() != null);
            er.close();
            p.waitFor();
            return o.toString().trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
            if (p != null) p.destroy();
        }
    }

    private String extractModel(String s) {
        String rawHw = "";
        for (String l : s.split("\n")) { String[] p = l.split(":"); if(p.length>=2&&(l.contains("Hardware")||l.contains("model name"))) { rawHw = p[1].trim(); break; } }

        String socInfo = readSysfs("/sys/devices/soc0/machine");
        if (!socInfo.isEmpty() || !rawHw.isEmpty()) {
            String match = findSoc(rawHw);
            if (match == null) match = findSoc(Build.HARDWARE);
            if (match == null) match = findSoc(Build.BOARD);
            String refined = refineSoc(match, socInfo.isEmpty() ? rawHw : socInfo);
            if (refined != null) return refined;
            if (match != null) return match;
            String manuf = Build.MANUFACTURER;
            if (!rawHw.isEmpty()) return manuf + " " + rawHw;
        }

        String hw = Build.HARDWARE != null ? Build.HARDWARE : "";
        String board = Build.BOARD != null ? Build.BOARD : "";
        String match = findSoc(hw);
        if (match == null) match = findSoc(board);
        String refined = refineSoc(match, hw);
        if (refined != null) return refined;
        if (match != null) return match;
        return hw.isEmpty() ? "N/A" : hw;
    }

    private String extractRawHw() {
        String rawHw = "";
        String cpuInfo = exec("cat /proc/cpuinfo 2>/dev/null | head -40 || echo ''");
        for (String l : cpuInfo.split("\n")) {
            String[] p = l.split(":");
            if(p.length>=2&&(l.contains("Hardware")||l.contains("model name"))) { rawHw = p[1].trim(); break; }
        }
        if (rawHw.isEmpty()) {
            String socInfo = readSysfs("/sys/devices/soc0/machine");
            if (!socInfo.isEmpty()) rawHw = socInfo;
        }
        if (rawHw.isEmpty()) rawHw = Build.HARDWARE;
        if (rawHw == null) rawHw = "";
        return rawHw;
    }

    private long parseMem(String s, String key) {
        for (String l : s.split("\n")) { String[] p = l.split("\\s+"); if(l.startsWith(key+":")&&p.length>=2) try{return Long.parseLong(p[1]);}catch(Exception e){} }
        return 0;
    }

    private String[][] lines(String raw, int max) {
        String[] l = raw.split("\n"); int n = Math.min(l.length, max); String[][] d = new String[n][1];
        for (int i = 0; i < n; i++) { String s = l[i].trim(); if(s.length()>68)s=s.substring(0,65)+"..."; d[i]=new String[]{s}; }
        return d;
    }

    private String[][] parseThermal(String raw) {
        java.util.ArrayList<String[]> r = new java.util.ArrayList<>();
        for (String l : raw.split("\n")) { if(l.trim().isEmpty()) continue;
            String[] p = l.split(":",2); if(p.length<2) continue;
            String n = p[0].length()>16 ? p[0].substring(0,13)+"..." : p[0];
            String v = p[1].trim(); try{ long x=Long.parseLong(v); v=(x/1000)+"."+(Math.abs(x)%1000)+"\u00B0C"; }catch(Exception e){}
            r.add(new String[]{n,v});
        } return r.toArray(new String[0][]);
    }

    private String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int)(Math.log(bytes)/Math.log(1024));
        return String.format(Locale.US, "%.1f %sB", bytes/Math.pow(1024,exp), "KMGTPE".charAt(exp-1));
    }

    private boolean isPowerSaveMode() {
        if (Build.VERSION.SDK_INT >= 21) { android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE); return pm != null && pm.isPowerSaveMode(); }
        return false;
    }

    private Intent getBatteryIntent() {
        if (Build.VERSION.SDK_INT >= 33) return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        return registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    // ==================== UI ====================

    private void addCard(String title, String[][] rows) {
        // Outer container with margin
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(MP, -2);
        olp.setMargins(0, 0, 0, (int)(10*density));
        outer.setLayoutParams(olp);

        // Left accent bar
        View bar = new View(this);
        bar.setBackgroundColor(ACCENT);
        bar.setLayoutParams(new LinearLayout.LayoutParams((int)(4*density), -1));
        outer.addView(bar);

        // Card body
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_background);
        card.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        card.setPadding((int)(16*density), (int)(12*density), (int)(16*density), (int)(12*density));

        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(ACCENT);
        tv.setPadding(0, 0, 0, (int)(8*density));
        card.addView(tv);

        if (rows != null) for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, (int)(3*density), 0, (int)(3*density));

            TextView k = new TextView(this);
            k.setText(r[0]);
            k.setTextSize(13);
            k.setTextColor(DIM);
            k.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));

            TextView v = new TextView(this);
            v.setText(r.length > 1 ? r[1] : "");
            v.setTextSize(13);
            v.setTextColor(0xFFFFFFFF);
            v.setTypeface(null, Typeface.BOLD);
            v.setGravity(Gravity.END);
            v.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));

            row.addView(k); row.addView(v); card.addView(row);
        }
        outer.addView(card);
        contentArea.addView(outer);
    }

    private void addProgressBar(int pct, int color) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(MP, -2);
        olp.setMargins(0, 0, 0, (int)(10*density));
        outer.setLayoutParams(olp);

        View bar = new View(this);
        bar.setBackgroundColor(color);
        bar.setLayoutParams(new LinearLayout.LayoutParams((int)(4*density), -1));
        outer.addView(bar);

        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setBackgroundResource(R.drawable.card_background);
        w.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        w.setPadding((int)(16*density), (int)(12*density), (int)(16*density), (int)(12*density));

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setLayoutParams(new LinearLayout.LayoutParams(MP, (int)(14*density)));
        pb.setMax(100); pb.setProgress(pct);
        pb.setProgressTintList(ColorStateList.valueOf(color));
        w.addView(pb);

        TextView l = new TextView(this);
        l.setText(pct + "% used");
        l.setTextSize(12); l.setTextColor(DIM); l.setGravity(Gravity.END);
        l.setPadding(0, (int)(3*density), 0, 0);
        w.addView(l);
        outer.addView(w);
        contentArea.addView(outer);
    }

    // ==================== HEADER ====================

    private void updateHeader() {
        Intent bat = getBatteryIntent(); if (bat == null) return;
        int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
        int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level * 100 / Math.max(scale, 1);
        int tempC = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10;
        int voltage = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int status = bat.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        String s = status == BatteryManager.BATTERY_STATUS_CHARGING ? "\u26A1 Charging" :
                  status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "Discharging" :
                  status == BatteryManager.BATTERY_STATUS_FULL ? "Full" : "";
        headerPercent.setText(pct + "%");
        headerPercent.setTextColor(pct > 20 ? 0xFFFFFFFF : 0xFFEF5350);
        headerStatus.setText(s);
        headerStatus.setTextColor(status == BatteryManager.BATTERY_STATUS_CHARGING ? 0xFF81C784 : 0xFFB0B0B0);
        headerMeta.setText(tempC + "\u00B0C  |  " + voltage + " mV");
    }

    // ==================== SERVICE ====================

    private void toggleService() {
        Intent si = new Intent(this, MonitorService.class);
        if (serviceRunning) {
            stopService(si); serviceRunning = false;
            btnService.setText("Monitor"); btnService.setBackgroundTintList(ColorStateList.valueOf(SURFACE));
            btnService.setTextColor(0xFFFFFFFF);
        } else {
            startForegroundService(si); serviceRunning = true;
            btnService.setText("Stop"); btnService.setBackgroundTintList(ColorStateList.valueOf(0xFFEF5350));
            btnService.setTextColor(0xFFFFFFFF);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private static int MP = ViewGroup.LayoutParams.MATCH_PARENT;
}
