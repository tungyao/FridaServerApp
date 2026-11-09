# FridaServerApp

打开就能启动 frida-server 的 Android 小工具 —— 不再需要 adb 命令行。

专为**已取得 root 权限**（临时 root / KernelSU / Magisk）的手机设计：安装后点开 App，
自动完成「释放 frida-server → 赋执行权限 → 以守护模式启动」，状态实时显示。

## 功能

- **打开即启动**：App 启动时自动请求 root 并启动 frida-server（`-D` 守护模式）
- **版本管理**：内置 frida-server 版本号写入 `/data/local/tmp/frida-server.version`，版本升级时自动重新释放，相同版本跳过
- **手动控制**：启动 / 停止 / 强制重新安装按钮
- **状态轮询**：每 3 秒检测进程，界面实时显示运行状态和 pid
- **免联网**：frida-server 打包在 APK 里，运行时不下载任何东西

## 使用

```powershell
# 1. 获取 frida-server 二进制（85MB，不入库，需要 Python 3）
powershell -ExecutionPolicy Bypass -File scripts\fetch-frida-server.ps1

# 2. 构建（需要 JDK 17；SDK 路径写在 local.properties 或设置 ANDROID_HOME）
.\gradlew assembleDebug

# 3. 安装到手机
adb install app\build\outputs\apk\debug\app-debug.apk
```

打开 App 即自动启动，看到 `状态：运行中 (pid xxx)` 即成功。

PC 端安装**与 server 同版本**的工具：

```powershell
pip install frida-tools==17.26.2
frida-ps -U
```

## 工作原理

1. `su -c 'id -u'` 探测 root
2. 检查 `/data/local/tmp/frida-server.version` 是否与内置版本（当前 `17.26.2`）一致
3. 不一致则把 assets 中的二进制通过 `su` 的 stdin 直接写入 `/data/local/tmp/frida-server`，`chmod 755` 并记录版本
4. `su -c '/data/local/tmp/frida-server -D'` 守护启动，轮询 `pidof frida-server` 确认就绪

## 项目结构

```
FridaServerApp/
├── scripts/fetch-frida-server.ps1   # 下载 frida-server 到 assets
├── app/src/main/
│   ├── assets/frida-server          # (gitignore) 85MB 二进制
│   ├── java/com/pingyin/fridastarter/MainActivity.java
│   └── res/layout/activity_main.xml
└── build.gradle / settings.gradle   # AGP 8.6.1 + Gradle 8.9
```

## 更换 frida 版本

1. `scripts\fetch-frida-server.ps1 -Version <新版本>`（先删掉旧的 assets/frida-server）
2. 修改 `MainActivity.java` 中的 `FRIDA_VERSION` 常量
3. 重新构建安装，App 会自动检测版本变化并重新释放

## 要求

- 手机：arm64-v8a、已获取 root（su 可用）
- 构建：JDK 17、Android SDK 34、Python 3（仅下载脚本需要）
