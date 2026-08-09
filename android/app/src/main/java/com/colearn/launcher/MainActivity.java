package com.colearn.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.UUID;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 18800;
    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int POLL_INTERVAL_MS = 300;
    private static final int MAX_WAIT_MS = 45000;

    private volatile boolean loaded;
    private volatile Process serverProcess;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView wv = new WebView(this);
        webView = wv;
        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (loaded) {
                    showError("页面加载失败 (" + errorCode + "): " + description);
                }
            }
        });
        setContentView(wv);
        showStatus("正在启动 colearn 后端…");
        persistDeviceId();
        startServer();
        startWatchdog();
    }

    private void startServer() {
        try {
            logToFile("startServer called");
            File dir = getFilesDir();
            // 直接从 native lib 执行(已以 0755+apk_data_file 解压,可执行,无需复制到 files/)
            // nativeLibraryDir 在部分 ROM 上返回 .../lib/<abi>,而实际解压目录可能是
            // .../lib/<abi> 或 .../lib/arm64,这里做多路径兜底查找。
            File nativeLibRoot = new File(getApplicationInfo().nativeLibraryDir).getParentFile();
            String[] candidates = {
                    getApplicationInfo().nativeLibraryDir,
                    nativeLibRoot + "/arm64-v8a",
                    nativeLibRoot + "/arm64",
                    nativeLibRoot.getAbsolutePath(),
            };
            File bin = null;
            for (String base : candidates) {
                File f = new File(base, "libcolearn_launcher.so");
                if (f.exists() && f.length() > 0) {
                    bin = f;
                    break;
                }
            }
            if (bin == null) {
                String msg = "native lib 未找到 (nativeLibraryDir="
                        + getApplicationInfo().nativeLibraryDir + ", legacy="
                        + nativeLibRoot.getAbsolutePath() + ")";
                android.util.Log.e("COLEARN", msg);
                showError(msg);
                return;
            }
            android.util.Log.i("COLEARN", "launcher bin = " + bin.getAbsolutePath());
            // 设置 colearn_HOME 到可写的 files 目录,避免二进制默认写 /.colearn(只读)
            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(),
                    "-console", "-no-browser",
                    "-host", HOST,
                    "-port", String.valueOf(PORT));
            pb.environment().put("colearn_HOME", dir.getAbsolutePath());
            // 指向核心 agent 二进制(gateway 子命令),launcher 启动网关时使用
            File core = new File(bin.getParent(), "libcolearn.so");
            if (!core.exists()) {
                core = new File(bin.getParentFile().getParentFile(), "libcolearn.so");
            }
            if (core.exists()) {
                pb.environment().put("colearn_BINARY", core.getAbsolutePath());
            }
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            serverProcess = proc;
            new Thread(new OutputReader(proc)).start();
        } catch (Exception e) {
            android.util.Log.e("COLEARN", "startServer exception", e);
            showError("启动后端失败: " + e + "\n\n" + stackTrace(e));
        }
    }

    private void startWatchdog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < MAX_WAIT_MS) {
                    Process p = serverProcess;
                    if (p == null) {
                        return;
                    }
                    if (p.isAlive()) {
                        if (!isPortOpen()) {
                            try {
                                Thread.sleep(POLL_INTERVAL_MS);
                            } catch (InterruptedException e) {
                                return;
                            }
                        } else {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    loaded = true;
                                    webView.loadUrl("http://" + HOST + ":" + PORT + "/");
                                }
                            });
                            return;
                        }
                    } else {
                        return;
                    }
                }
                showError("等待 " + HOST + ":" + PORT + " 超时（45s）。\n后端可能启动失败。\n请用 `adb logcat | grep colearn` 查看日志。");
            }
        }).start();
    }

    private boolean isPortOpen() {
        try {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            } finally {
                socket.close();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void showStatus(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadData("<html><body style='font-family:sans-serif;padding:24px;color:#333'><h3>colearn</h3><p>" + text + "</p></body></html>", "text/html", "utf-8");
            }
        });
    }

    private void showError(final String text) {
        logToFile("ERROR: " + text);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadData("<html><body style='font-family:monospace;padding:16px;color:#b00;white-space:pre-wrap'><h3>启动失败</h3><pre>" + escapeHtml(text) + "</pre></body></html>", "text/html", "utf-8");
            }
        });
    }

    private void logToFile(String text) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "launcher-debug.log");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            fos.write(("<" + System.currentTimeMillis() + "> " + text + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String stackTrace(Exception e) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement el : e.getStackTrace()) {
            sb.append(el.toString()).append("\n");
        }
        return sb.toString();
    }

    private void persistDeviceId() {
        try {
            File cfg = new File(getFilesDir(), "launcher-config.json");
            JSONObject json = cfg.exists() ? new JSONObject(readAll(cfg)) : new JSONObject();
            if (!json.has("device_id")) {
                String id = computeDeviceId();
                json.put("device_id", id);
                writeAll(cfg, json.toString());
                logToFile("persistDeviceId: wrote new device_id=" + id);
            }
        } catch (Exception e) {
            logToFile("persistDeviceId error: " + e);
        }
    }

    private String computeDeviceId() {
        String androidId = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null
                && !androidId.isEmpty()
                && !"0000000000000000".equals(androidId)) {
            try {
                return sha256Hex(androidId);
            } catch (Exception e) {
                logToFile("sha256 failed for androidId: " + e);
            }
        }
        logToFile("androidId unavailable; falling back to random UUID");
        return UUID.randomUUID().toString();
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(input.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            int v = b & 0xff;
            if (v < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    private static String readAll(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static void writeAll(File f, String data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data.getBytes("UTF-8"));
        }
    }

    private class OutputReader implements Runnable {
        private final Process proc;

        OutputReader(Process proc) {
            this.proc = proc;
        }

        @Override
        public void run() {
            StringBuilder log = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.append(line).append("\n");
                    if (log.length() > 8000) {
                        log.delete(0, log.length() - 8000);
                    }
                }
                int exit = proc.waitFor();
                if (!loaded) {
                    showError("后端进程已退出 (exit=" + exit + ")\n\n" + log);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Process p = serverProcess;
        if (p != null) {
            p.destroy();
            serverProcess = null;
        }
    }
}
