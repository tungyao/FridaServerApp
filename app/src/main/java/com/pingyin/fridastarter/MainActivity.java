package com.pingyin.fridastarter;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String FRIDA_VERSION = "17.26.2";
    private static final String SERVER_PATH = "/data/local/tmp/frida-server";
    private static final String VERSION_PATH = SERVER_PATH + ".version";
    private static final String ASSET_NAME = "frida-server";

    private TextView statusView;
    private TextView detailView;
    private Button startButton;
    private Button stopButton;
    private Button reinstallButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean busy = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!busy) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        final String pid = pidOfFrida();
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!busy) {
                                    renderState(pid);
                                }
                            }
                        });
                    }
                });
            }
            mainHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        detailView = findViewById(R.id.detail);
        startButton = findViewById(R.id.btn_start);
        stopButton = findViewById(R.id.btn_stop);
        reinstallButton = findViewById(R.id.btn_reinstall);

        startButton.setOnClickListener(v -> doStart(false));
        stopButton.setOnClickListener(v -> doStop());
        reinstallButton.setOnClickListener(v -> doStart(true));

        // 打开即自动启动
        doStart(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.postDelayed(pollRunnable, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(pollRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    private void doStart(final boolean forceReinstall) {
        if (busy) return;
        busy = true;
        setButtonsEnabled(false);
        statusView.setText("状态：启动中...");
        detailView.setText("正在请求 root 权限");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final StringBuilder log = new StringBuilder();
                try {
                    if (!isRootAvailable()) {
                        fail("root 不可用", "su 执行失败，请确认已获取 root");
                        return;
                    }
                    log.append("root 可用\n");

                    String pid = pidOfFrida();
                    if (!forceReinstall && pid != null) {
                        log.append("已在运行 pid=").append(pid);
                        ok(pid, log);
                        return;
                    }

                    // 停掉旧实例（如果有）
                    if (pid != null) {
                        runRoot("killall frida-server 2>/dev/null || pkill frida-server 2>/dev/null; true");
                        log.append("已停止旧实例\n");
                    }

                    // 检查是否已安装相同版本
                    String installed = runRoot("cat " + VERSION_PATH + " 2>/dev/null");
                    boolean needInstall = forceReinstall || !FRIDA_VERSION.equals(installed == null ? "" : installed.trim());
                    if (needInstall) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                detailView.setText("正在释放 frida-server（约 85MB，请稍候）");
                            }
                        });
                        if (!installServer()) {
                            fail("安装失败", "释放 frida-server 到设备失败");
                            return;
                        }
                        log.append("已安装 ").append(FRIDA_VERSION).append("\n");
                    } else {
                        log.append("版本一致，跳过安装\n");
                    }

                    // 启动（-D 守护进程模式）
                    String startOut = runRoot(SERVER_PATH + " -D 2>&1");
                    log.append("启动输出: ").append(startOut == null ? "" : startOut.trim()).append("\n");

                    // 等待就绪
                    String readyPid = null;
                    for (int i = 0; i < 10; i++) {
                        Thread.sleep(500);
                        readyPid = pidOfFrida();
                        if (readyPid != null) break;
                    }
                    if (readyPid == null) {
                        fail("启动失败", "5 秒内未检测到进程\n" + log);
                        return;
                    }
                    ok(readyPid, log);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    fail("已中断", log.toString());
                } catch (Exception e) {
                    fail("异常", e.getClass().getSimpleName() + ": " + e.getMessage() + "\n" + log);
                }
            }
        });
    }

    private void doStop() {
        if (busy) return;
        busy = true;
        setButtonsEnabled(false);
        statusView.setText("状态：停止中...");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String pid = pidOfFrida();
                if (pid != null) {
                    runRoot("killall frida-server 2>/dev/null || pkill frida-server 2>/dev/null; true");
                    for (int i = 0; i < 6; i++) {
                        if (pidOfFrida() == null) break;
                        try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                    }
                }
                final String after = pidOfFrida();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        setButtonsEnabled(true);
                        renderState(after);
                        if (after == null) {
                            statusView.setText("状态：已停止");
                        }
                    }
                });
            }
        });
    }

    // ---------- 核心逻辑 ----------

    private boolean isRootAvailable() {
        String out = runRoot("id -u");
        return out != null && out.trim().endsWith("0");
    }

    private String pidOfFrida() {
        String out = runRoot("pidof frida-server 2>/dev/null");
        if (out == null) return null;
        out = out.trim();
        if (out.isEmpty()) return null;
        // pidof 可能返回多个 pid，取第一个
        String[] parts = out.split("\\s+");
        return parts[0].replaceAll("\\D", "");
    }

    /** 通过 su 把 assets 里的 frida-server 写到 /data/local/tmp 并加执行权限 */
    private boolean installServer() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "cat > " + SERVER_PATH + " && chmod 755 " + SERVER_PATH
                            + " && echo -n " + FRIDA_VERSION + " > " + VERSION_PATH
            });
            try (InputStream in = getAssets().open(ASSET_NAME);
                 OutputStream out = p.getOutputStream()) {
                byte[] buf = new byte[1024 * 512];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            boolean finished = p.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 执行 su -c 命令，返回 stdout+stderr 文本；失败返回 null */
    private static String runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread t1 = drain(p.getInputStream(), buf);
            Thread t2 = drain(p.getErrorStream(), buf);
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            t1.join(2000);
            t2.join(2000);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0 && buf.size() == 0) {
                return null;
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static Thread drain(final InputStream in, final ByteArrayOutputStream out) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] b = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(b)) > 0) {
                        synchronized (out) {
                            out.write(b, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------- UI ----------

    private void ok(final String pid, final StringBuilder log) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                busy = false;
                setButtonsEnabled(true);
                statusView.setText("状态：运行中 (pid " + pid + ")");
                statusView.setTextColor(0xFF2E7D32);
                detailView.setText(log.toString().trim());
            }
        });
    }

    private void fail(final String title, final String detail) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                busy = false;
                setButtonsEnabled(true);
                statusView.setText("状态：" + title);
                statusView.setTextColor(0xFFC62828);
                detailView.setText(detail);
            }
        });
    }

    private void renderState(String pid) {
        if (pid != null) {
            statusView.setText("状态：运行中 (pid " + pid + ")");
            statusView.setTextColor(0xFF2E7D32);
        } else {
            statusView.setText("状态：未运行");
            statusView.setTextColor(0xFFC62828);
        }
    }

    private void setButtonsEnabled(final boolean enabled) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                startButton.setEnabled(enabled);
                stopButton.setEnabled(enabled);
                reinstallButton.setEnabled(enabled);
            }
        });
    }
}
