package com.colearn.launcher;

import android.app.Activity;
import android.os.Bundle;
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
        startServer();
        startWatchdog();
    }

    private void startServer() {
        try {
            File dir = getFilesDir();
            // 直接从 native lib 执行(已以 0755+apk_data_file 解压,可执行,无需复制到 files/)
            String binPath = getApplicationInfo().nativeLibraryDir + "/libcolearn_launcher.so";
            File bin = new File(binPath);
            if (!bin.exists() || bin.length() == 0) {
                showError("native lib 未找到: " + binPath);
                return;
            }
            // 设置 colearn_HOME 到可写的 files 目录,避免二进制默认写 /.colearn(只读)
            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(),
                    "-console", "-no-browser",
                    "-host", HOST,
                    "-port", String.valueOf(PORT));
            pb.environment().put("colearn_HOME", dir.getAbsolutePath());
            pb.directory(dir);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            serverProcess = proc;
            new Thread(new OutputReader(proc)).start();
        } catch (Exception e) {
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.loadData("<html><body style='font-family:monospace;padding:16px;color:#b00;white-space:pre-wrap'><h3>启动失败</h3><pre>" + escapeHtml(text) + "</pre></body></html>", "text/html", "utf-8");
            }
        });
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
