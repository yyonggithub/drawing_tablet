# Drawing Tablet

A high-performance, low-latency drawing tablet solution that turns your Android device into a professional graphics tablet for Linux (Wayland).

## 🚀 Overview

Check out the [Official website](https://lemonxah.github.io/drawing_tablet/)

This project consists of two parts:
1.  **Android Client:** A dedicated app that captures stylus input (pressure, tilt, coordinates) and renders a low-latency video stream of your PC screen.
2.  **Rust Server:** A lightweight Linux daemon that:
    *   Captures the screen using **PipeWire** (via XDG Desktop Portals).
    *   Encodes the video stream using **GStreamer** (Hardware Accelerated VA-API/NVENC).
    *   Emulates a virtual Wacom tablet using **uinput** to inject pen/touch events into the OS.

The result is a Cintiq-like experience on your Android tablet, entirely over WiFi.

## ✨ Features

*   **Millisecond Input Latency:** TCP-based protocol for instant cursor response.
*   **High-Quality Screen Mirroring:** H.264 hardware encoding (NVENC / VA-API, with an x264 software fallback).
*   **Built-in Latency Stats:** per-second network, bitrate and end-to-end frame-latency reporting (server terminal + in-app overlay).
*   **Clean Lifecycle:** start/stop the service from the GUI; closing the window shuts everything down without leaving helper processes behind.
*   **Full Wacom Emulation:** Supports Pressure, Tilt, and Hover.
*   **Gesture Support:** Pinch-to-zoom, Pan, and Rotate support in apps like Krita/GIMP (Server-side gesture handling).
*   **Wayland Native:** Built for modern Linux desktops (GNOME/KDE/Sway).
*   **Auto-Connect:** Client remembers your server and handles network interruptions gracefully.

---

## ⚡ Quick Start

1. **One-time server setup** — load the `uinput` module + udev rule, install
   `avahi` (mDNS discovery) and an encoder plugin:
   see [Configuration & Usage → 0](#-configuration--usage).
2. **Build & run the server:**
   ```bash
   cargo build --release --bin dt-server   # or: just build-release
   ./target/release/dt-server              # or: just run-release
   ```
3. In the GUI set **Port** (9999) / **Bitrate** / **Keyframe Int**, press
   **START SERVER** and pick the screen in the portal dialog.
4. **Tablet:** install the APK ([Deployment → 4](#4-build--install-the-android-app)),
   open the app, tap the discovered server (same LAN, prefer **5 GHz**) and draw.
5. **Stop:** *Stop* stops the service (the window stays open); closing the
   window exits the process.

Deploy options (Arch PKGBUILD, Nix, `cargo install`, APK signing) are in
[Deployment](#-deployment); read [Notes & Precautions](#-notes--precautions)
before exposing anything.

---

## 🛠️ Prerequisites

### Linux Server
You need a modern Linux distribution running **Wayland**.

**Build Dependencies (Debian/Ubuntu):**
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

**Build Dependencies (Fedora):**
```bash
sudo dnf install \
    clang \
    pipewire-devel \
    glib2-devel \
    gstreamer1-devel \
    gstreamer1-plugins-base-devel \
    gstreamer1-plugins-bad-free-devel
```

**Build Dependencies (Arch Linux):**
```bash
sudo pacman -S base-devel clang pipewire glib2 gstreamer \
    gst-plugins-base gst-plugins-good gst-plugins-bad gst-plugins-ugly \
    gstreamer-vaapi
```

**Build Dependencies (Gentoo):**
```bash
# Note: Ensure USE flags like 'pipewire' and 'vaapi' are enabled globally or for relevant packages.
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

**Rust Toolchain:**
Install [Rustup](https://rustup.rs/):
```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

### Nix / NixOS

A Nix flake is provided for reproducible builds and easy integration.

#### Run directly (no install)
```bash
nix run github:lemonxah/drawing_tablet
```

#### NixOS (system-wide)

Add to your `flake.nix` inputs and apply the overlay:

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

#### Development shell

```bash
# Using the flake dev shell
nix develop github:lemonxah/drawing_tablet

# Or clone and use devenv
git clone https://github.com/lemonxah/drawing_tablet && cd drawing_tablet
devenv shell
```

### Android Client
*   **Android Studio** (Koala or newer recommended).
*   **Android SDK Platform 34** (Android 14).
*   **NDK** (Side-by-side).

> Runtime extras (screen sharing via mDNS discovery, hardware/software encoders)
> are covered in [Configuration & Usage](#-configuration--usage).

---

## 📦 Deployment

We use [just](https://github.com/casey/just) as a command runner. It is
optional — every recipe maps to a plain `cargo` / `gradle` / `adb` command.

### 1. Build the server
```bash
just build            # debug
just build-release    # optimized (recommended for streaming)
# or, without `just`:
cargo build --release --bin dt-server
```

### 2. One-time system setup (Important!)

The server creates the virtual pen/touch devices through the kernel's
`uinput` module, which needs a one-time setup (load the module, add the udev
rule, join the group). See **Configuration & Usage → 0.1 `uinput`** below for
the exact commands — and for the pitfall where `/dev/uinput` exists but the
module is not loaded. The encoder, mDNS and firewall setup are in
**0.2–0.4** of the same section.

### 3. Install the server (pick one)

*   **Run from the build tree**: `./target/release/dt-server`
*   **cargo**: `cargo install --path crates/dt-server` → `~/.cargo/bin/dt-server`
*   **Arch Linux**: `makepkg -si` with the bundled `PKGBUILD` (release) or
    `PKGBUILD-git` (latest commit). This installs `/usr/bin/drawing-tablet`
    plus a "Drawing Tablet" menu entry.
*   **Nix**: `nix run github:lemonxah/drawing_tablet`, or the flake overlay /
    Home Manager module — see [Nix / NixOS](#nix--nixos) above.

### 4. Build & install the Android app

*   **Android Studio**: open the `android/` directory and press **Run**.
*   **CLI** (debug APK to a connected device):
    ```bash
    just android-deploy
    # or:
    cd android && ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```
*   **Release APKs** are signed from `android/drawing_tablet.keystore` with
    the `KEYSTORE_PASSWORD` / `KEY_PASSWORD` environment variables; without
    them, build debug APKs.

### 5. Logs

*   The server logs to the terminal (pipeline errors also appear there).
*   The tablet: `adb logcat -s NetworkClient VideoDecoder ServiceDiscovery`
    (or `just android-logs`).

---

## 🚀 Configuration & Usage

### 0. One-time setup (Linux server)

#### 0.1 `uinput` — virtual tablet (required)

Pen/touch events are injected through the kernel's `uinput` module, which must
be **loaded** and its device node **writable by your user**:

```bash
sudo modprobe uinput
echo uinput | sudo tee /etc/modules-load.d/uinput.conf   # load it at boot

sudo groupadd -f uinput
sudo tee /etc/udev/rules.d/99-drawing-tablet.rules >/dev/null <<'EOF'
KERNEL=="uinput", GROUP="uinput", MODE="0660"
EOF
sudo usermod -aG uinput "$USER"           # log out / back in afterwards
sudo udevadm control --reload-rules
sudo udevadm trigger --action=add --name-match=uinput
```

Verify (want `crw-rw---- root uinput`):

```bash
ls -l /dev/uinput
```

> **Pitfall:** if `/dev/uinput` shows `crw------- root root` **and**
> `lsmod | grep uinput` prints nothing, the module is not loaded and the node
> is a leftover — udev never sees it, so the udev rule cannot help. Run
> `sudo modprobe uinput` first, then re-trigger udev.

#### 0.2 Encoder (one of the three; the server auto-detects)

| Backend | GPU | Requirements |
| :--- | :--- | :--- |
| `nvh264enc` (NVENC) | NVIDIA | working driver (`nvidia-smi` OK) + GStreamer `nvcodec`/CUDA elements |
| `vaapih264enc` (VA-API) | Intel / AMD | `gst-plugin-va` (or `gstreamer-vaapi`) |
| `x264enc` (software) | — | `gst-plugins-ugly`; more CPU and latency |

Check what is available:

```bash
gst-inspect-1.0 nvh264enc || gst-inspect-1.0 vaapih264enc || gst-inspect-1.0 x264enc
```

The server logs the backend it chose (`Using NVENC encoder …` /
`Video encoder pipeline started (nvh264enc)`). If none can be created it
prints `no usable H.264 encoder` and the reason for each backend.

#### 0.3 mDNS discovery — `avahi`

The Android app finds servers only through the mDNS advertisement, so
`avahi-publish` (package `avahi` / `avahi-utils`) must be installed. The
server publishes `_drawingtablet._udp` on the LAN together with a TXT record
containing its IPv4 address.

#### 0.4 Firewall

Allow the port (default **9999**) for **both** TCP (input/control) and UDP
(video):

```bash
sudo ufw allow 9999/tcp && sudo ufw allow 9999/udp
```

### 1. Start the server

```bash
just run-release
# or, if `just` is not installed:
cargo run --release --bin dt-server
# or run the built binary directly:
./target/release/dt-server
```

Set the options in the GUI and press **START SERVER**:

| Field | Default | Meaning |
| :--- | :--- | :--- |
| Port | 9999 | TCP (input/control) + UDP (video) port |
| Bitrate (kbps) | 8000 | target video bitrate — lower it on a congested link |
| Keyframe Int | 120 | keyframe every N frames (smaller = more bandwidth, faster loss recovery) |

On the first start a **"Share your screen"** portal dialog appears — pick the
monitor to mirror. The encoded frame rate is capped to the refresh rate the
tablet reports (a 60 Hz panel is streamed at ≤ 60 fps).

### 2. Connect the tablet

1.  Install the APK (`just android-deploy`, or open `android/` in Android Studio).
2.  Tablet and PC on the **same LAN — prefer 5 GHz**.
3.  Open the app: the server appears in the list (mDNS). Tap it to connect.
    *   *Optional:* tick **"Remember this server"** to auto-connect later.

### 3. Draw

*   **Pen:** drawing, pressure, tilt and hover work immediately in GIMP/Krita/Blender.
*   **Touch:** 1 finger is ignored (no accidental strokes); 2+ fingers zoom/pan/rotate.
*   **Stats overlay:** tap the ⓘ button to show `frame`, `net`, `bitrate`, `dec` and FPS.

### 4. Stop

*   **STOP SERVER** stops the service; the window stays open so you can start it again.
*   **Closing the window** stops the service and exits the process. No helper
    processes are left behind (the `avahi-publish` advertisement dies with it).

### 5. Reading the latency numbers

The server prints one line per second:

```
capture->send: avg 1.1 ms, max 2.0 ms, 3.6 Mbps, fps cap/enc/sent 60/60/58, dropped 2
```

| Field | Meaning |
| :--- | :--- |
| `capture->send` | time from capture to the datagrams leaving the socket (queue + encode) |
| `Mbps` | sent video bitrate |
| `fps cap/enc/sent` | captured / encoded / sent frames per second |
| `dropped` | captured frames dropped (backpressure or the frame-rate cap) |

The tablet logs the same information it shows in the overlay:

| Field | Meaning |
| :--- | :--- |
| `frame` | **end-to-end video latency**: server capture → submitted for display |
| `net` | one-way network latency (RTT/2, from a ping every second) |
| `bitrate` | received video bitrate |
| `dec` | receive → display latency |

Rough total ≈ `frame` + one display refresh. If `frame` is much larger than
`capture->send` + `net`, the client is the bottleneck (look at `queueDrop` /
`decDrop` in the logs). If the server's `Mbps` is much higher than the
tablet's `bitrate`, the link is dropping packets (try 5 GHz, or lower the
bitrate).

### 6. Configuration reference

Configuration is done entirely in the GUI (`Port`, `Bitrate`, `Keyframe Int`).

> Note: the `--port` / `--fps` / `--bitrate` CLI flags and the `DT_PORT` /
> `DT_FPS` / `DT_BITRATE` environment variables that exist in
> `crates/dt-server/src/config.rs` are **not wired up yet** — the GUI is the
> only effective configuration path, and the encoder frame rate is fixed at
> 100 fps (further capped by the tablet's reported refresh rate).

---

## 🎨 Application Setup

### Krita Configuration

For the best drawing experience in Krita, you need to adjust the brush smoothing settings:

1. Open Krita and select any brush tool
2. In the **Tool Options** docker (usually on the right side), find the **Brush Smoothing** section at the bottom
3. Change the smoothing mode from **Basic** to **Weighted** or **None**

| Smoothing Mode | Compatibility |
|:--------------|:--------------|
| None | ✅ Works perfectly |
| Basic | ⚠️ May cause line spikes  |
| Weighted | ✅  Works perfectly |
| Stabilizer | ✅ Works (adds input lag) |

---

## 🏗️ Architecture Deep Dive

### 1. Screen Capture (`crates/dt-capture`)
We bypass the slow X11 capture methods by using **PipeWire** directly.
*   **XDG Desktop Portal:** We request a session via DBus (`ashpd` crate). The user selects a monitor securely.
*   **PipeWire Stream:** We negotiate a raw video buffer (BGRx) format with the compositor. Memory is shared via `MemFd` (Zero-copy where possible) for maximum performance.

### 2. Video Encoding (`crates/dt-encoder`)
Raw pixels are heavy (4K @ 60fps is huge). We pipe the buffer into **GStreamer**.
*   **Pipeline:** `appsrc -> cudaupload -> nvh264enc` (Nvidia), `appsrc -> vaapipostproc -> vaapih264enc` (Intel/AMD), or `appsrc -> videoconvert -> x264enc` (software fallback) — selected automatically at startup. The pixel buffer is handed to GStreamer without an extra copy.
*   **Hardware Acceleration:** H.264 only (no HEVC/AV1). NVENC uses the `p1` preset with zero-latency tuning; VA-API uses `tune=low-latency` with a single reference frame. Keyframes are sent every 120 frames by default.
*   **Network:** Encoded NAL units are split into ≤1400-byte datagrams and sent over UDP with a small custom header (sequence number, fragment index/count) — there is no RTP layer. The video socket is marked DSCP AF41 and the frame rate is capped to the refresh rate the client reports. Input and control go over TCP. We prioritize **latency over reliability**—dropped frames are better than old frames.

### 3. Input Emulation (`crates/dt-input`)
We use the Linux kernel's `uinput` module to create two virtual devices:
1.  **"Drawing Tablet Pen":** A high-resolution absolute input device. It reports `ABS_X`, `ABS_Y`, `ABS_PRESSURE`, `ABS_TILT_X`, `ABS_TILT_Y`.
2.  **"Drawing Tablet Touch":** A specialized Multitouch device.
    *   **Mode:** It acts as a "Direct" input device but **without** button capabilities (`BTN_TOUCH` removed).
    *   **Why?** This prevents GIMP from treating a single finger as a "Mouse Click" (Selection Tool) while still allowing the OS (libinput) to recognize multi-touch gestures like Pinch-to-Zoom.

---

## 📜 Justfile Commands

`just` is optional — every recipe maps to a plain `cargo`/`gradle`/`adb`
command (see [Configuration & Usage](#-configuration--usage)).

| Command | Description |
| :--- | :--- |
| `just build` | Build server (Debug) |
| `just run` | Run server (Debug) |
| `just run-release` | Run server (Release - Recommended) |
| `just android-deploy` | Build & Install APK to connected device |
| `just android-logs` | Tail filtered Android logs |
| `just check-pipewire` | Verify PipeWire status |
| `just check-vaapi` | Verify Hardware Encoder availability |

---

## ⚠️ Notes & Precautions

**Security**
*   The protocol is plaintext with **no authentication**, and the server
    injects input events on your PC. **LAN only** — do not expose the port
    (9999) beyond your local network.
*   Don't run the server as root; use the `uinput` udev rule instead.

**Network**
*   Prefer **5 GHz** WiFi. On 2.4 GHz, airtime queueing alone can add tens of
    milliseconds of latency.
*   One tablet at a time — connecting a new tablet replaces the current one.
*   The Android app has **no manual IP entry**: it only connects to servers
    found via mDNS, so `avahi-publish` must be running on the server.

**Desktop environment**
*   The server requires a **Wayland session** with XDG desktop portal and
    PipeWire (GNOME / KDE / wlroots). Start it from a graphical session, not
    from a headless SSH shell.
*   After a GPU driver update, **reboot** before expecting NVENC (the loaded
    kernel module must match the userspace library). If NVENC still
    "disappears", clear the GStreamer plugin cache — see Troubleshooting.

**App & version behaviour**
*   If connection attempts keep failing, the app disables auto-connect
    ("auto-connect disabled due to unstable connection"). Connect manually
    once and re-tick "Remember this server".
*   The encoder order is NVENC → VA-API → x264 (software). The software
    fallback works but costs CPU and latency — check the server log to see
    which one you actually got.
*   The protocol only ever appends new message types, so server and app
    versions pair loosely: an older app still streams (it just misses the
    newest stats), and an older server still works (the newer stats read 0).
*   The measured `frame` latency covers only the video half of the round trip
    (server capture → tablet display); the pen → PC → drawing-app half
    happens inside the drawing application — see [Krita Configuration](#krita-configuration).

---

## ❓ Troubleshooting

**Q: The server fails right after Start with "Cannot open /dev/uinput" / "Failed to create virtual device".**
*   The `uinput` module is not loaded or the node is not writable. See the
    one-time `uinput` setup in **Configuration & Usage → 0.1** above:
    `sudo modprobe uinput`, the udev rule, and membership in the `uinput`
    group. If `/dev/uinput` is `crw------- root root` while
    `lsmod | grep uinput` is empty, the module is simply not loaded.
    (Running the server with `sudo` also works but is not recommended.)

**Q: The server reports "no usable H.264 encoder".**
*   The error lists why each backend failed. Common causes:
    *   **NVENC**: the loaded NVIDIA kernel module does not match the
        userspace library (typical right after a driver update) — **reboot**.
        If `nvidia-smi` works but NVENC still fails, clear the GStreamer
        plugin cache (it can cache the "no encoder" state):
        `rm -f ~/.cache/gstreamer-1.0/registry.*.bin`.
    *   **x264 fallback missing**: install `gst-plugins-ugly` (`x264enc`).
    *   **VA-API missing**: install `gst-plugin-va` (or `gstreamer-vaapi`).

**Q: Connection fails immediately.**
*   Check your firewall (`sudo ufw allow 9999/tcp` and `sudo ufw allow 9999/udp`, adjusting the port if you changed it).
*   Ensure both devices are on the same LAN — preferably 5 GHz. 2.4 GHz adds
    tens of milliseconds of airtime queueing.
*   Discovery relies on mDNS: make sure `avahi-publish` is installed. With
    `avahi-browse -rt _drawingtablet._udp` you should see the service with a
    TXT record like `ip=192.168.1.20` — the app connects to that address
    (Android's NSD otherwise only reports the IPv6 link-local address).

**Q: The screen is black on Android.**
*   Check the server logs for the encoder line (`Using … encoder`). If it fell
    back to `x264enc` or failed, see the encoder question above.
*   Ensure you selected a screen in the portal dialog.

**Q: GIMP selects instead of zooming.**
*   We filtered single-touch inputs to fix this. Ensure you are running the latest server version.

**Q: Lines have random spikes/glitches in Krita.**
*   This is caused by Krita's "Weighted Smoothing" brush setting. Change it to "Basic" or "None" in the Tool Options docker. See the [Krita Configuration](#krita-configuration) section above.

**Q: The picture is still laggy even though the stats look good.**
*   The measured `frame` latency covers "server capture → tablet display"; the
    other half of the round trip (pen event → PC → drawing app renders →
    compositor produces a frame) happens inside the drawing application.
    Krita's brush smoothing is the usual culprit: set **Brush Smoothing** to
    **None** (or *Weighted*) — *Stabilizer* deliberately adds lag. See
    [Krita Configuration](#krita-configuration).
*   Lower the **Bitrate** (or raise **Keyframe Int**) if the tablet's received
    bitrate is much lower than the server's sent bitrate.

**Q: The build fails in `libspa-sys` / `libspa` (e.g. `no field 'data' on type 'spa_pod_builder'`).**
*   The `bindgen` version used by `libspa-sys` (0.69) is incompatible with libclang 22+ (e.g. libclang 23). It mis-generates structs that are forward-declared and then defined (such as `spa_pod_builder`) as opaque types, which breaks the build.
*   Point `LIBCLANG_PATH` at an older libclang (21 or earlier) before building:
    ```bash
    export LIBCLANG_PATH=/usr/lib/llvm21/lib   # adjust to your distro's path
    cargo build
    ```
    Or persist it in `.cargo/config.toml`:
    ```toml
    [env]
    LIBCLANG_PATH = "/usr/lib/llvm21/lib"
    ```
*   On Nix / devenv this is already handled by the dev shell.

---

## 📄 License

This project is licensed under the **MIT License** - see the [LICENSE](LICENSE) file for details.

### Third-Party Libraries

This software uses the following libraries at runtime:
- **GStreamer** (LGPL-2.1+) - Multimedia framework
- **PipeWire** (MIT/LGPL-2.1+) - Audio/Video routing

These libraries are dynamically linked and can be replaced by the user.
