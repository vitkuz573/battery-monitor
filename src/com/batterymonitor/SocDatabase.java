package com.batterymonitor;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SocDatabase {

    private static final String INDEX_URL = "https://vitkuz573.github.io/soc-db/data/index.json";
    private static final String DATA_URL = "https://vitkuz573.github.io/soc-db/data/";
    private static final String CACHE_FILE = "soc_db_cache.json";

    private HashMap<String, SocInfo> lookup = new HashMap<>();
    private HashMap<String, SocInfo> fallback = new HashMap<>();
    private boolean remoteLoaded = false;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public static class SocInfo {
        public String name;
        public String vendor;
        public String model;
        public String architecture;
        public int cores;

        public SocInfo(String name, String vendor, String model) {
            this.name = name;
            this.vendor = vendor;
            this.model = model;
            this.architecture = "";
            this.cores = 0;
        }
    }

    public SocDatabase() {
        buildFallback();
        lookup.putAll(fallback);
    }

    public void loadRemote(final Context context, final Runnable callback) {
        executor.execute(() -> {
            if (loadCache(context)) {
                remoteLoaded = true;
                if (callback != null) callback.run();
                return;
            }
            if (fetchRemote(context)) {
                remoteLoaded = true;
                saveCache(context);
            }
            if (callback != null) callback.run();
        });
    }

    public boolean isRemoteLoaded() {
        return remoteLoaded;
    }

    public SocInfo findSoc(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String lc = raw.toLowerCase(Locale.US);

        for (HashMap.Entry<String, SocInfo> e : lookup.entrySet()) {
            if (lc.contains(e.getKey())) return e.getValue();
        }

        for (HashMap.Entry<String, SocInfo> e : lookup.entrySet()) {
            String m = e.getValue().model.toLowerCase(Locale.US);
            if (!m.isEmpty() && lc.contains(m)) return e.getValue();
        }

        return null;
    }

    private boolean loadCache(Context context) {
        try {
            File f = new File(context.getFilesDir(), CACHE_FILE);
            if (!f.exists()) return false;
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            parseJson(sb.toString());
            if (!lookup.isEmpty()) {
                remoteLoaded = true;
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void saveCache(Context context) {
        try {
            JSONArray arr = new JSONArray();
            for (SocInfo info : lookup.values()) {
                if (info.vendor == null) continue;
                JSONObject obj = new JSONObject();
                obj.put("name", info.name);
                obj.put("vendor", info.vendor);
                obj.put("model", info.model);
                obj.put("architecture", info.architecture);
                obj.put("cores", info.cores);
                arr.put(obj);
            }
            File f = new File(context.getFilesDir(), CACHE_FILE);
            FileWriter w = new FileWriter(f);
            w.write(arr.toString(2));
            w.close();
        } catch (Exception ignored) {}
    }

    private boolean fetchRemote(Context context) {
        try {
            String indexJson = fetchUrl(INDEX_URL);
            JSONObject index = new JSONObject(indexJson);
            JSONObject vendors = index.getJSONObject("vendors");
            JSONArray allChips = new JSONArray();

            java.util.Iterator<String> keys = vendors.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject vinfo = vendors.getJSONObject(key);
                String vfile = vinfo.getString("file");
                String vname = vinfo.optString("name", key);
                String vendorJson = fetchUrl(DATA_URL + vfile);
                JSONArray chips = new JSONArray(vendorJson);
                for (int i = 0; i < chips.length(); i++) {
                    JSONObject chip = chips.getJSONObject(i);
                    chip.put("_vendor_name", vname);
                }
                allChips = mergeArrays(allChips, chips);
            }

            parseJson(allChips.toString());
            return !lookup.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private JSONArray mergeArrays(JSONArray a, JSONArray b) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < a.length(); i++) result.put(a.get(i));
        for (int i = 0; i < b.length(); i++) result.put(b.get(i));
        return result;
    }

    private void parseJson(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String name = obj.optString("name", "");
                String vendor = obj.optString("vendor", obj.optString("_vendor_name", ""));
                String model = obj.optString("model", "");
                String architecture = obj.optString("architecture", "");
                int cores = obj.optInt("cores", 0);

                if (name.isEmpty() && model.isEmpty()) continue;

                SocInfo info = new SocInfo(name, vendor, model);
                info.architecture = architecture;
                info.cores = cores;

                String key = model.toLowerCase(Locale.US);
                if (!key.isEmpty()) lookup.put(key, info);

                String idKey = obj.optString("id", "").toLowerCase(Locale.US);
                if (!idKey.isEmpty() && !idKey.equals(key)) lookup.put(idKey, info);
            }
        } catch (Exception ignored) {}
    }

    private String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "BatteryMonitor/1.0");
        BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String l;
        while ((l = r.readLine()) != null) sb.append(l);
        r.close();
        conn.disconnect();
        return sb.toString();
    }

    private void buildFallback() {
        SocInfo[] chips = {
            new SocInfo("Snapdragon 870", "Qualcomm", "SM8250"),
            new SocInfo("Snapdragon 865/865+", "Qualcomm", "SM8150"),
            new SocInfo("Snapdragon 888/888+", "Qualcomm", "SM8350"),
            new SocInfo("Snapdragon 8 Gen 1", "Qualcomm", "SM8450"),
            new SocInfo("Snapdragon 8+ Gen 1", "Qualcomm", "SM8475"),
            new SocInfo("Snapdragon 8 Gen 2", "Qualcomm", "SM8550"),
            new SocInfo("Snapdragon 8 Gen 3", "Qualcomm", "SM8650"),
            new SocInfo("Snapdragon 8 Elite", "Qualcomm", "SM8750"),
            new SocInfo("Snapdragon 765/768G", "Qualcomm", "SM7250"),
            new SocInfo("Snapdragon 730/732G", "Qualcomm", "SM7150"),
            new SocInfo("Snapdragon 690/695", "Qualcomm", "SM6350"),
            new SocInfo("Snapdragon 695 5G", "Qualcomm", "SM6375"),
            new SocInfo("Snapdragon 6 Gen 1", "Qualcomm", "SM6450"),
            new SocInfo("Snapdragon 778G/782G", "Qualcomm", "SM7325"),
            new SocInfo("Snapdragon 7 Gen 1", "Qualcomm", "SM7450"),
            new SocInfo("Snapdragon 7 Gen 3", "Qualcomm", "SM7550"),
            new SocInfo("Snapdragon 780G", "Qualcomm", "SM8355"),
            new SocInfo("Snapdragon 860", "Qualcomm", "SM8525"),
            new SocInfo("Snapdragon 845", "Qualcomm", "SDM845"),
            new SocInfo("Snapdragon 835", "Qualcomm", "SDM835"),
            new SocInfo("Snapdragon 820/821", "Qualcomm", "SDM820"),
            new SocInfo("Snapdragon 660", "Qualcomm", "SDM660"),
            new SocInfo("Snapdragon 636", "Qualcomm", "SDM636"),
            new SocInfo("Snapdragon 632", "Qualcomm", "SDM632"),
            new SocInfo("Snapdragon 630", "Qualcomm", "SDM630"),
            new SocInfo("Snapdragon 625/626", "Qualcomm", "SDM625"),
            new SocInfo("Snapdragon 435", "Qualcomm", "MSM8940"),
            new SocInfo("Snapdragon 430", "Qualcomm", "MSM8937"),
            new SocInfo("Snapdragon 425", "Qualcomm", "MSM8917"),
            new SocInfo("Snapdragon 810", "Qualcomm", "MSM8994"),
            new SocInfo("Snapdragon 808", "Qualcomm", "MSM8992"),
            new SocInfo("Dimensity 9000", "MediaTek", "MT6983"),
            new SocInfo("Dimensity 9200", "MediaTek", "MT6985"),
            new SocInfo("Dimensity 9300", "MediaTek", "MT6991"),
            new SocInfo("Dimensity 1200", "MediaTek", "MT6893"),
            new SocInfo("Dimensity 1100", "MediaTek", "MT6879"),
            new SocInfo("Dimensity 920", "MediaTek", "MT6877"),
            new SocInfo("Dimensity 820", "MediaTek", "MT6873"),
            new SocInfo("Dimensity 800U/810", "MediaTek", "MT6853"),
            new SocInfo("Dimensity 700/720", "MediaTek", "MT6833"),
            new SocInfo("Helio G90/G95", "MediaTek", "MT6785"),
            new SocInfo("Helio G80/G85", "MediaTek", "MT6768"),
            new SocInfo("Helio P90", "MediaTek", "MT6779"),
            new SocInfo("Helio P70", "MediaTek", "MT6771"),
            new SocInfo("Helio P35", "MediaTek", "MT6765"),
            new SocInfo("Helio A22", "MediaTek", "MT6761"),
            new SocInfo("Exynos 2200", "Samsung", "Exynos2200"),
            new SocInfo("Exynos 2100", "Samsung", "Exynos2100"),
            new SocInfo("Exynos 990", "Samsung", "Exynos990"),
            new SocInfo("Exynos 9825", "Samsung", "Exynos9825"),
            new SocInfo("Exynos 9820", "Samsung", "Exynos9820"),
            new SocInfo("Exynos 9810", "Samsung", "Exynos9810"),
            new SocInfo("Exynos 9611", "Samsung", "Exynos9611"),
            new SocInfo("Kirin 9000", "HiSilicon", "Kirin9000"),
            new SocInfo("Kirin 990", "HiSilicon", "Kirin990"),
            new SocInfo("Kirin 985", "HiSilicon", "Kirin985"),
            new SocInfo("Kirin 980", "HiSilicon", "Kirin980"),
            new SocInfo("Kirin 810", "HiSilicon", "Kirin810"),
            new SocInfo("Kirin 710", "HiSilicon", "Kirin710"),
            new SocInfo("Google Tensor G4", "Google", "Tensor G4"),
            new SocInfo("Google Tensor G3", "Google", "Tensor G3"),
            new SocInfo("Google Tensor G2", "Google", "Tensor G2"),
            new SocInfo("Google Tensor", "Google", "Tensor"),
            new SocInfo("Apple A18", "Apple", "A18"),
            new SocInfo("Apple A17 Pro", "Apple", "A17"),
            new SocInfo("Apple A16 Bionic", "Apple", "A16"),
            new SocInfo("Apple A15 Bionic", "Apple", "A15"),
            new SocInfo("Apple A14 Bionic", "Apple", "A14"),
            new SocInfo("Apple A13 Bionic", "Apple", "A13"),
            new SocInfo("Apple A12 Bionic", "Apple", "A12"),
        };
        for (SocInfo c : chips) {
            String k = c.model.toLowerCase(Locale.US);
            if (!k.isEmpty()) fallback.put(k, c);
        }
    }
}
