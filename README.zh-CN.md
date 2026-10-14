# Drawing Tablet（数位板）

一套高性能、低延迟的数位板方案：把你的 Android 设备变成 Linux（Wayland）上的专业绘图板。

[English](README.md) | **简体中文**

## 🚀 概览

欢迎访问[官方网站](https://lemonxah.github.io/drawing_tablet/)

本项目由两部分组成：
1.  **Android 客户端：** 一款专用 App，采集手写笔输入（压力、倾角、坐标），并以低延迟渲染 PC 屏幕的视频流。
2.  **Rust 服务端：** 一个轻量的 Linux 守护程序：
    *   通过 **PipeWire**（经 XDG 桌面门户）采集屏幕。
    *   使用 **GStreamer** 编码视频流（硬件加速 VA-API/NVENC）。
    *   使用 **uinput** 模拟一块虚拟 Wacom 数位板，把笔/触摸事件注入系统。

最终效果：完全通过 WiFi，在 Android 平板上获得类 Cintiq 的绘画体验。

## ✨ 功能特性

*   **毫秒级输入延迟：** 基于 TCP 的协议，光标响应即时。
*   **高质量屏幕镜像：** H.264 硬件编码（NVENC / VA-API，并有 x264 软件回退）。
*   **内置延迟统计：** 逐秒输出网络、码率与端到端画面延迟（服务端终端 + App 内悬浮层）。
*   **干净的生命周期：** 从 GUI 启动/停止服务；关闭窗口即完整退出，不留任何辅助进程。
*   **完整 Wacom 模拟：** 支持压力、倾角与悬停。
*   **手势支持：** 在 Krita/GIMP 等应用中支持双指缩放、平移、旋转（服务端处理手势）。
*   **Wayland 原生：** 面向现代 Linux 桌面（GNOME/KDE/Sway）。
*   **自动重连与自愈发现：** 客户端会记住你的服务器，重启后自动重新扫描发现（Android 的 mDNS 事件可能迟到数十秒）并以合理节奏重试连接，直到恢复。
*   **Docker 一键环境：** 开发、编译、打包（服务端二进制、Arch 包、APK）全部可在容器镜像内完成——宿主机无需安装工具链。

---

## ⚡ 快速开始

1. **服务端一次性准备** —— 加载 `uinput` 模块 + udev 规则，安装 `avahi`（mDNS 发现）和编码器插件：
   详见[配置与使用 → 0](#-配置与使用)。
2. **构建并运行服务端：**
   ```bash
   cargo build --release --bin dt-server   # 或：just build-release
   ./target/release/dt-server              # 或：just run-release
   ```
3. 在 GUI 中设置 **端口**（9999）/ **码率** / **关键帧间隔**，点击 **START SERVER**，
   然后在门户（portal）对话框中选择要共享的屏幕。
4. **平板：** 安装 APK（见[部署 → 4](#4-构建并安装-android-应用)），
   打开 App，点击发现的服务器（同一局域网，优先 **5 GHz**）即可开始绘制。
5. **停止：** *Stop* 只停止服务（窗口保留）；关闭窗口才会退出进程。

部署方式（Arch PKGBUILD、Nix、`cargo install`、APK 签名、
[Docker 一键环境](#6-docker-一键环境开发--编译--打包)）见[部署](#-部署)；
在任何对外暴露之前，请先阅读[注意事项](#-注意事项)。

---

## 🛠️ 前置依赖

> **宿主机不想装工具链？** 下面这些东西（Rust、GStreamer 头文件、
> Android SDK）都在 [Docker 一键环境](#6-docker-一键环境开发--编译--打包)
> 里——直接看[部署 → 6](#6-docker-一键环境开发--编译--打包) 即可。

### Linux 服务端
你需要一个运行 **Wayland** 的现代 Linux 发行版。

**构建依赖（Debian/Ubuntu）：**
```bash
sudo apt install \
    build-essential \
    pkg-config \
    libclang-dev \
    libpipewire-0.3-dev \
    libglib2.0-dev \
    libgstreamer1.0-dev \
    libgstreamer-plugins-base1.0-dev \
    libgstreamer-plugins-bad1.0-dev \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-plugins-ugly \
    gstreamer1.0-vaapi \
    udev
```

**构建依赖（Fedora）：**
```bash
sudo dnf install \
    clang \
    pipewire-devel \
    glib2-devel \
    gstreamer1-devel \
    gstreamer1-plugins-base-devel \
    gstreamer1-plugins-bad-free-devel
```

**构建依赖（Arch Linux）：**
```bash
sudo pacman -S base-devel clang pipewire glib2 gstreamer \
    gst-plugins-base gst-plugins-good gst-plugins-bad gst-plugins-ugly \
    gstreamer-vaapi
```

**构建依赖（Gentoo）：**
```bash
# 注意：确保 'pipewire' 和 'vaapi' 等 USE 标志已在全局或相关包上启用。
sudo emerge --ask \
    dev-util/pkgconf \
    sys-devel/clang \
    media-video/pipewire \
    media-libs/gstreamer \
    media-libs/gst-plugins-base \
    media-libs/gst-plugins-good \
    media-libs/gst-plugins-bad \
    media-libs/gst-plugins-ugly \
    media-plugins/gst-plugins-vaapi
```

**Rust 工具链：**
安装 [Rustup](https://rustup.rs/)：
```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

### Nix / NixOS

项目提供 Nix flake，支持可复现构建与便捷集成。

#### 直接运行（无需安装）
```bash
nix run github:lemonxah/drawing_tablet
```

#### NixOS（系统级）

添加到你的 `flake.nix` inputs 并应用 overlay：

```nix
{
  inputs.drawing-tablet.url = "github:lemonxah/drawing_tablet";

  outputs = { nixpkgs, drawing-tablet, ... }: {
    nixosConfigurations.myhost = nixpkgs.lib.nixosSystem {
      modules = [{
        nixpkgs.overlays = [ drawing-tablet.overlays.default ];
        environment.systemPackages = [ pkgs.drawing-tablet ];
      }];
    };
  };
}
```

#### Home Manager

```nix
{
  inputs.drawing-tablet.url = "github:lemonxah/drawing_tablet";

  outputs = { nixpkgs, home-manager, drawing-tablet, ... }: {
    homeConfigurations.myuser = home-manager.lib.homeManagerConfiguration {
      modules = [{
        nixpkgs.overlays = [ drawing-tablet.overlays.default ];
        home.packages = [ pkgs.drawing-tablet ];
      }];
    };
  };
}
```

#### 开发环境

```bash
# 使用 flake dev shell
nix develop github:lemonxah/drawing_tablet

# 或克隆仓库后使用 devenv
git clone https://github.com/lemonxah/drawing_tablet && cd drawing_tablet
devenv shell
```

### Android 客户端
*   **Android Studio**（推荐 Koala 或更新版本）。
*   **Android SDK Platform 34**（Android 14）。
*   **NDK**（Side-by-side 方式安装）。

> 运行期依赖（屏幕共享所需的 mDNS 发现、硬件/软件编码器）
> 见[配置与使用](#-配置与使用)。

---

## 📦 部署

我们使用 [just](https://github.com/casey/just) 作为命令运行器。它是可选的——
每条 recipe 都对应一条普通的 `cargo` / `gradle` / `adb` 命令。

### 1. 构建服务端
```bash
just build            # 调试版
just build-release    # 优化版（推流建议使用）
# 不装 just 的话：
cargo build --release --bin dt-server
```

### 2. 一次性系统准备（重要！）

服务端通过内核的 `uinput` 模块创建虚拟笔/触摸设备，需要一次性准备
（加载模块、添加 udev 规则、加入用户组）。具体命令见下文
**配置与使用 → 0.1 uinput**——其中还包括"`/dev/uinput` 存在但模块其实没加载"的坑。
编码器、mDNS 与防火墙的准备在同一章节的 **0.2–0.4**。

### 3. 安装服务端（任选其一）

*   **直接运行构建产物**：`./target/release/dt-server`
*   **cargo**：`cargo install --path crates/dt-server` → `~/.cargo/bin/dt-server`
*   **Arch Linux**：用仓库自带的 `PKGBUILD`（发行版）或 `PKGBUILD-git`
    （最新提交）执行 `makepkg -si`。会安装 `/usr/bin/drawing-tablet`
    以及"Drawing Tablet"菜单项。
*   **Nix**：`nix run github:lemonxah/drawing_tablet`，或使用 flake overlay /
    Home Manager 模块——见上文 [Nix / NixOS](#nix--nixos)。

### 4. 构建并安装 Android 应用

*   **Android Studio**：打开 `android/` 目录并点击 **Run**。
*   **命令行**（构建调试 APK 并安装到已连接设备）：
    ```bash
    just android-deploy
    # 或：
    cd android && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```
*   **Release APK** 使用 `android/drawing_tablet.keystore` 签名，并需要
    `KEYSTORE_PASSWORD` / `KEY_PASSWORD` 环境变量；没有它们请构建调试版。

### 5. 日志

*   服务端输出到终端（管线错误也会打到那里）。
*   平板端：`adb logcat -s NetworkClient VideoDecoder ServiceDiscovery`
    （或 `just android-logs`）。

### 6. Docker 一键环境（开发 / 编译 / 打包）

不想在宿主机装工具链的话，全部构建都可以放进 Docker。镜像只负责
**开发、编译和打包**——服务端本身仍必须跑在宿主机上（容器里没有
Wayland 门户、PipeWire 和 uinput）。

```bash
./docker/dt.sh image [server|android|all]   # 按需（重）构建镜像
./docker/dt.sh dev   [server|android]       # 交互式开发 shell，源码挂载在 /src
./docker/dt.sh build [server|android]       # 容器内编译（产物在工作区）
./docker/dt.sh package                      # 服务端 → dist/
./docker/dt.sh apk                          # Android APK → dist/
```

等价的 `just` recipe（需安装 [`just`](https://github.com/casey/just)）：
`just docker-image [server|android|all]`、`just docker-dev …`、
`just docker-build …`、`just docker-package`、`just docker-apk`。

*   **两个镜像：** `drawing-tablet-server:local`（Arch，Rust + GStreamer，
    固定 `clang21` 规避 bindgen/libclang 坑）和
    `drawing-tablet-android:local`（Arch，JDK 21 + Android SDK
    platform-35——不装 NDK，应用是纯 Kotlin/Compose）。
*   **缓存：** `dt-cargo-registry`、`dt-gradle-home` 为命名卷；
    `target/` 与 Gradle 构建目录留在工作区里，重复构建走增量、
    产物在宿主机直接可见。
*   `package` 在 `dist/` 产出三件套：release 二进制、带版本号的 tarball
    （二进制 + `.desktop` + 图标 + 许可证 + 双语 README）、以及用仓库
    PKGBUILD 基于**当前工作区代码**构建的 Arch 包（`source=` 已切换为
    本地 tarball）。
*   `apk` 产出 `dist/drawing-tablet-<版本>-debug.apk`；导出
    `KEYSTORE_PASSWORD`/`KEY_PASSWORD` 并提供
    `android/drawing_tablet.keystore` 则出 release APK。

---

## 🚀 配置与使用

### 0. 一次性准备（Linux 服务端）

#### 0.1 `uinput` —— 虚拟平板（必需）

笔/触摸事件通过内核的 `uinput` 模块注入，该模块必须**已加载**，
且设备节点**对你的用户可写**：

```bash
sudo modprobe uinput
echo uinput | sudo tee /etc/modules-load.d/uinput.conf   # 开机自动加载

sudo groupadd -f uinput
sudo tee /etc/udev/rules.d/99-drawing-tablet.rules >/dev/null <<'EOF'
KERNEL=="uinput", GROUP="uinput", MODE="0660"
EOF
sudo usermod -aG uinput "$USER"           # 之后需要重新登录
sudo udevadm control --reload-rules
sudo udevadm trigger --action=add --name-match=uinput
```

验证（应显示 `crw-rw---- root uinput`）：

```bash
ls -l /dev/uinput
```

> **坑：** 如果 `/dev/uinput` 显示 `crw------- root root`，
> **同时** `lsmod | grep uinput` 没有输出，说明模块并未加载、这个节点只是
> 一个残留——udev 根本看不到它，udev 规则救不了你。先执行
> `sudo modprobe uinput`，再重新触发 udev。

#### 0.2 编码器（三选一；服务端自动探测）

| 后端 | GPU | 依赖要求 |
| :--- | :--- | :--- |
| `nvh264enc`（NVENC） | NVIDIA | 驱动正常（`nvidia-smi` 可用）+ GStreamer `nvcodec`/CUDA 元件 |
| `vaapih264enc`（VA-API） | Intel / AMD | `gst-plugin-va`（或 `gstreamer-vaapi`） |
| `x264enc`（软件） | — | `gst-plugins-ugly`；CPU 与延迟更高 |

检查你有哪些：

```bash
gst-inspect-1.0 nvh264enc || gst-inspect-1.0 vaapih264enc || gst-inspect-1.0 x264enc
```

服务端会打印它选用的后端（`Using NVENC encoder …` /
`Video encoder pipeline started (nvh264enc)`）。如果全都创建失败，会打印
`no usable H.264 encoder` 以及每个后端失败的原因。

#### 0.3 mDNS 发现 —— `avahi`

Android App 只能通过 mDNS 广播发现服务器，因此必须安装
`avahi-publish`（软件包 `avahi` / `avahi-utils`）。服务端会在局域网上发布
`_drawingtablet._udp`，并在 TXT 记录中带上自己的 IPv4 地址。

#### 0.4 防火墙

为端口（默认 **9999**）同时放行 **TCP**（输入/控制）和 **UDP**（视频）：

```bash
sudo ufw allow 9999/tcp && sudo ufw allow 9999/udp
```

### 1. 启动服务端

```bash
just run-release
# 没装 just 的话：
cargo run --release --bin dt-server
# 或直接运行构建产物：
./target/release/dt-server
```

在 GUI 中设置选项并点击 **START SERVER**：

| 字段 | 默认值 | 含义 |
| :--- | :--- | :--- |
| Port | 9999 | TCP（输入/控制）+ UDP（视频）端口 |
| Bitrate (kbps) | 8000 | 目标视频码率——链路拥挤时调低 |
| Keyframe Int | 120 | 每 N 帧一个关键帧（越小越抗丢包、带宽越高） |

首次启动会弹出 **"Share your screen"** 门户对话框——选择要镜像的显示器。
编码帧率会被限制在平板上报的刷新率以内（60 Hz 面板以 ≤ 60 fps 推流）。

### 2. 连接平板

1.  安装 APK（`just android-deploy`，或在 Android Studio 中打开 `android/`）。
2.  平板与 PC 处于**同一局域网——优先 5 GHz**。
3.  打开 App：服务器会出现在列表中（mDNS），点击即可连接。
    *   *可选：* 勾选 **"Remember this server"** 以便以后自动连接。
    *   之后重启服务端的话，App 会在几秒内自动重新发现并重连。

### 3. 绘制

*   **笔：** 绘制、压力、倾角、悬停在 GIMP/Krita/Blender 中立即可用。
*   **触摸：** 单指被忽略（避免误触）；双指及以上可缩放/平移/旋转。
*   **统计悬浮层：** 点击 ⓘ 按钮显示 `frame`、`net`、`bitrate`、`dec` 与 FPS。

### 4. 停止

*   **STOP SERVER** 只停止服务；窗口保留，可随时再次启动。
*   **关闭窗口** 会停止服务并退出进程。不会留下任何辅助进程
    （`avahi-publish` 广播进程随之结束）。

### 5. 怎么看延迟数字

服务端每秒打印一行：

```
capture->send: avg 1.1 ms, max 2.0 ms, 3.6 Mbps, fps cap/enc/sent 60/60/58, dropped 2
```

| 字段 | 含义 |
| :--- | :--- |
| `capture->send` | 从采集到数据报离开套接字的时间（排队 + 编码） |
| `Mbps` | 已发送的视频码率 |
| `fps cap/enc/sent` | 采集 / 编码 / 发送 的每秒帧数 |
| `dropped` | 被丢弃的采集帧（背压或帧率上限所致） |

平板端会打印与悬浮层相同的信息：

| 字段 | 含义 |
| :--- | :--- |
| `frame` | **端到端画面延迟**：服务端采集 → 提交显示 |
| `net` | 单向网络延迟（RTT/2，每秒一次 ping） |
| `bitrate` | 收到的视频码率 |
| `dec` | 接收 → 显示 的延迟 |

总延迟大致 ≈ `frame` + 一次显示刷新。如果 `frame` 远大于
`capture->send` + `net`，瓶颈在客户端（看日志里的 `queueDrop` /
`decDrop`）。如果服务端的 `Mbps` 远高于平板的 `bitrate`，说明链路在丢包
（试试 5 GHz，或调低码率）。

### 6. 配置参考

所有配置都在 GUI 中完成（`Port`、`Bitrate`、`Keyframe Int`）。

> 注意：`crates/dt-server/src/config.rs` 中的 `--port` / `--fps` / `--bitrate`
> 命令行参数与 `DT_PORT` / `DT_FPS` / `DT_BITRATE` 环境变量**尚未接线**——
> 目前只有 GUI 是有效的配置途径；编码帧率固定为 100 fps
> （再受平板上报的刷新率进一步限制）。

---

## 🎨 应用配置

### Krita 配置

要在 Krita 中获得最佳绘制体验，需要调整笔刷平滑设置：

1. 打开 Krita 并选择任意笔刷工具
2. 在 **Tool Options** 面板（通常在右侧）底部找到 **Brush Smoothing** 区域
3. 将平滑模式从 **Basic** 改为 **Weighted** 或 **None**

| 平滑模式 | 兼容性 |
|:--------------|:--------------|
| None | ✅ 完全正常 |
| Basic | ⚠️ 可能出现线条尖刺 |
| Weighted | ✅ 完全正常 |
| Stabilizer | ✅ 可用（但会增加输入延迟） |

---

## 🏗️ 架构深入

### 1. 屏幕采集（`crates/dt-capture`）
我们绕过缓慢的 X11 采集方式，直接使用 **PipeWire**。
*   **XDG 桌面门户：** 通过 DBus（`ashpd` crate）请求会话，由用户安全地选择显示器。
*   **PipeWire 流：** 与合成器协商原始视频缓冲（BGRx）格式。内存通过 `MemFd` 共享（尽可能零拷贝）以获得最大性能。

### 2. 视频编码（`dt-encoder`）
原始像素非常庞大（4K @ 60fps 体量巨大）。我们将缓冲送入 **GStreamer**。
*   **管线：** `appsrc -> cudaupload -> nvh264enc`（Nvidia）、`appsrc -> vaapipostproc -> vaapih264enc`（Intel/AMD），或 `appsrc -> videoconvert -> x264enc`（软件回退）——启动时自动选择。像素缓冲不经过额外拷贝直接交给 GStreamer。
*   **硬件加速：** 仅 H.264（不支持 HEVC/AV1）。NVENC 使用 `p1` 预设与零延迟调优；VA-API 使用 `tune=low-latency` 与单参考帧。默认每 120 帧发送一个关键帧。
*   **网络：** 编码后的 NAL 单元被切分为 ≤1400 字节的数据报，通过 UDP 发送，带一个很小的自定义头（序列号、分片序号/总数）——没有 RTP 层。视频套接字标记为 DSCP AF41，帧率被限制在客户端上报的刷新率以内。输入与控制走 TCP。我们**优先考虑延迟而非可靠性**——丢掉一帧比显示旧帧更好。

### 3. 输入模拟（`crates/dt-input`）
我们使用 Linux 内核的 `uinput` 模块创建两个虚拟设备：
1.  **"Drawing Tablet Pen"：** 高分辨率绝对定位输入设备。上报 `ABS_X`、`ABS_Y`、`ABS_PRESSURE`、`ABS_TILT_X`、`ABS_TILT_Y`。
2.  **"Drawing Tablet Touch"：** 一个专用的多点触控设备。
    *   **模式：** 它作为"Direct"输入设备，但**没有**按钮能力（移除了 `BTN_TOUCH`）。
    *   **为什么？** 这可以防止 GIMP 把单指当作"鼠标点击"（选择工具），同时仍让系统（libinput）识别双指缩放等多点触控手势。

---

## 📜 Justfile 命令

`just` 是可选的——每条 recipe 都对应普通的 `cargo`/`gradle`/`adb`
命令（见[配置与使用](#-配置与使用)）；`docker-*` 系列 recipe 封装的是
[Docker 一键环境](#6-docker-一键环境开发--编译--打包)（没装 `just`
就直接跑 `./docker/dt.sh …`）。

| 命令 | 说明 |
| :--- | :--- |
| `just build` | 构建服务端（调试版） |
| `just run` | 运行服务端（调试版） |
| `just run-release` | 运行服务端（发布版 - 推荐） |
| `just android-deploy` | 构建并安装 APK 到已连接设备 |
| `just android-logs` | 实时查看过滤后的 Android 日志 |
| `just check-pipewire` | 检查 PipeWire 状态 |
| `just check-vaapi` | 检查硬件编码器可用性 |
| `just docker-image [server\|android\|all]` | 构建 Docker 镜像 |
| `just docker-dev [server\|android]` | 进入 Docker 开发 shell（源码挂载 `/src`） |
| `just docker-build [server\|android]` | 容器内编译 |
| `just docker-package` | 容器内打包服务端 → `dist/`（二进制 + tarball + Arch 包） |
| `just docker-apk` | 容器内出 APK → `dist/` |

---

## ⚠️ 注意事项

**安全**
*   协议为明文且**没有鉴权**，而服务端可以在你的 PC 上注入输入事件。
    **仅限局域网**——不要把端口（9999）暴露到本地网络之外。
*   不要用 root 运行服务端；请使用 `uinput` udev 规则。

**网络**
*   优先使用 **5 GHz** WiFi。在 2.4 GHz 上，仅空口排队就会增加数十毫秒延迟。
*   同一时间只支持一台平板——新平板连接会顶掉当前连接。
*   Android App **没有手动输入 IP**：它只连接通过 mDNS 发现的服务器，
    因此服务端必须有 `avahi-publish` 在运行。

**桌面环境**
*   服务端需要带 XDG 桌面门户与 PipeWire 的 **Wayland 会话**
    （GNOME / KDE / wlroots）。请从图形会话启动，不要在无头 SSH
    shell 里运行。
*   GPU 驱动更新后请**重启**再期待 NVENC 可用（已加载的内核模块必须与
    用户态库匹配）。如果 NVENC 仍然"消失"，清除 GStreamer 插件缓存——
    见故障排查。

**App 与版本行为**
*   App 会自行重新扫描服务器（找不到时每几秒一次），因为 Android 的
    mDNS 事件在服务端重启后可能迟到数十秒或彻底丢失。连接失败会带冷却
    地重试——App 不会进入"放弃重试"的闩锁状态；如果它一直停在
    "Looking for drawing tablet server"，那说明服务端确实在没广播
    （检查 `avahi-publish`）。
*   编码器顺序为 NVENC → VA-API → x264（软件）。软件回退可用，但更吃
    CPU 且延迟更高——看服务端日志确认你实际用的是哪个。
*   协议只在枚举末尾追加新消息类型，因此服务端与 App 版本可以宽松配对：
    旧 App 仍能推流（只是没有最新的统计），旧服务端也能继续工作
    （新统计字段读作 0）。
*   实测的 `frame` 延迟只覆盖回程的视频一半（服务端采集 → 平板显示）；
    笔 → PC → 绘图应用这一半发生在绘图应用内部——见 [Krita 配置](#krita-配置)。

---

## ❓ 故障排查

**Q：服务端一按 Start 就失败，报 "Cannot open /dev/uinput" / "Failed to create virtual device"。**
*   `uinput` 模块未加载或节点不可写。见上文 **配置与使用 → 0.1** 的一次性
    准备：`sudo modprobe uinput`、udev 规则、加入 `uinput` 用户组。
    如果 `/dev/uinput` 是 `crw------- root root` 而 `lsmod | grep uinput`
    为空，就是模块没加载。（用 `sudo` 跑服务端也能通，但不推荐。）

**Q：服务端报告 "no usable H.264 encoder"。**
*   错误信息里列出了每个后端失败的原因。常见原因：
    *   **NVENC**：已加载的 NVIDIA 内核模块与用户态库不匹配（典型场景是
        刚更新驱动后）——**重启**。如果 `nvidia-smi` 正常但 NVENC 仍失败，
        清除 GStreamer 插件缓存（它可能缓存了"没有编码器"的状态）：
        `rm -f ~/.cache/gstreamer-1.0/registry.*.bin`。
    *   **缺少 x264 回退**：安装 `gst-plugins-ugly`（提供 `x264enc`）。
    *   **缺少 VA-API**：安装 `gst-plugin-va`（或 `gstreamer-vaapi`）。

**Q：连接立即失败。**
*   检查防火墙（`sudo ufw allow 9999/tcp` 与 `sudo ufw allow 9999/udp`，端口改过的话相应调整）。
*   确保两台设备在同一局域网——最好 5 GHz。2.4 GHz 会带来数十毫秒的空口排队。
*   发现依赖 mDNS：确认已安装 `avahi-publish`。执行
    `avahi-browse -rt _drawingtablet._udp` 应能看到该服务，并带有类似
    `ip=192.168.1.20` 的 TXT 记录——App 就是连接这个地址（否则 Android 的
    NSD 只会报告 IPv6 链路本地地址）。

**Q：Android 上画面全黑。**
*   检查服务端日志中的编码器行（`Using … encoder`）。如果回退到了
    `x264enc` 或失败了，见上面的编码器问题。
*   确认你在门户对话框中选择了屏幕。

**Q：GIMP 里变成了选择而不是缩放。**
*   我们已过滤单指输入来修复此问题。请确认你运行的是最新版服务端。

**Q：Krita 里线条出现随机尖刺/毛刺。**
*   这是 Krita 的"Weighted Smoothing"笔刷设置导致的。在 Tool Options
    面板中把它改为"Basic"或"None"。见上文 [Krita 配置](#krita-配置)。

**Q：统计数字看起来正常，但画面还是很卡。**
*   实测 `frame` 延迟只覆盖"服务端采集 → 平板显示"；回程的另一半
    （笔事件 → PC → 绘图应用渲染 → 合成器输出帧）发生在绘图应用内部。
    Krita 的笔刷平滑通常是罪魁祸首：把 **Brush Smoothing** 设为
    **None**（或 *Weighted*）——*Stabilizer* 是刻意增加延迟的。见
    [Krita 配置](#krita-配置)。
*   如果平板收到的码率远低于服务端发送的码率，调低 **Bitrate**
    （或调大 **Keyframe Int**）。

**Q：构建在 `libspa-sys` / `libspa` 失败（例如 `no field 'data' on type 'spa_pod_builder'`）。**
*   `libspa-sys`（0.69）使用的 `bindgen` 版本与 libclang 22+（如 libclang 23）
    不兼容。它会把先声明后定义的结构体（如 `spa_pod_builder`）错误地生成为
    不透明类型，导致构建失败。
*   构建前把 `LIBCLANG_PATH` 指向较旧的 libclang（21 或更早）：
    ```bash
    export LIBCLANG_PATH=/usr/lib/llvm21/lib   # 按你的发行版路径调整
    cargo build
    ```
    或在 `.cargo/config.toml` 中持久化：
    ```toml
    [env]
    LIBCLANG_PATH = "/usr/lib/llvm21/lib"
    ```
*   在 Nix / devenv 下，dev shell 已经处理好了这件事。

---

## 📄 许可证

本项目以 **MIT 许可证**发布——详情见 [LICENSE](LICENSE) 文件。

### 第三方库

本软件在运行时使用以下库：
- **GStreamer**（LGPL-2.1+）—— 多媒体框架
- **PipeWire**（MIT/LGPL-2.1+）—— 音视频路由

这些库以动态链接方式使用，可由用户替换。
