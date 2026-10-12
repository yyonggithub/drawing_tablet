//! Drawing tablet streaming server.
//!
//! Architecture:
//! - TCP (port): Input and Control packets from client (reliable, ordered)
//! - UDP (port): Video stream to client (low latency, lossy OK)

mod config;
mod framerate;
mod gui;
mod network;

use anyhow::Result;
use config::ServerConfig;
use dt_capture::{monotonic_us, select_monitor, Frame, ScreenCapture};
use dt_encoder::{EncodedFrame, EncoderConfig, VideoEncoder};
use dt_input::{EventRetimer, VirtualTablet};
use dt_protocol::{
    decode_packet, encode_control, encode_video_fragment_into, ControlPacket, DecodedPacket,
    InputEvent, InputPacket, VideoFragment, MAX_FRAGMENT_DATA, PROTOCOL_VERSION,
};
use framerate::FramePacer;
use gui::{DtServerApp, ServerStats};
use network::{TcpMessage, TcpServer, UdpServer};
use std::net::SocketAddr;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::mpsc as tokio_mpsc;
use tracing::{debug, error, info, warn};

/// Keeps an `avahi-publish` child alive for the lifetime of the server.
/// The mDNS advertisement is removed automatically when the child is killed
/// on drop.
struct AvahiService {
    child: Child,
}

impl AvahiService {
    fn start(name: &str, port: u16) -> Option<Self> {
        Command::new("avahi-publish")
            .args(["-s", name, "_drawingtablet._udp", &port.to_string()])
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .ok()
            .map(|child| Self { child })
    }
}

impl Drop for AvahiService {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn get_hostname() -> String {
    // Read the kernel hostname directly. Shelling out to the `hostname` binary
    // is unreliable: it is not installed on every system (e.g. minimal Arch
    // installs), which previously made the server advertise itself as
    // "Drawing Tablet (Unknown)".
    std::fs::read_to_string("/proc/sys/kernel/hostname")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .or_else(|| std::env::var("HOSTNAME").ok().filter(|s| !s.is_empty()))
        .unwrap_or_else(|| "Unknown".to_string())
}

fn main() -> Result<()> {
    // Initialize logging
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::from_default_env()
                .add_directive("dt_server=info".parse()?)
                .add_directive("dt_capture=info".parse()?)
                .add_directive("dt_encoder=info".parse()?)
                .add_directive("dt_input=info".parse()?),
        )
        .init();

    info!("Drawing Tablet Server v{}", env!("CARGO_PKG_VERSION"));

    let stats = Arc::new(Mutex::new(ServerStats::default()));
    {
        let mut guard = stats.lock().unwrap();
        guard.status = "Stopped".to_string();
    }
    let running_flag = Arc::new(AtomicBool::new(false));

    let app = DtServerApp::new(stats, running_flag);

    let mut native_options = eframe::NativeOptions::default();
    native_options.viewport.inner_size = Some(eframe::egui::vec2(1000.0, 700.0));

    // Load icon
    let icon_data = include_bytes!("../assets/icon.png");
    if let Ok(image) = image::load_from_memory(icon_data) {
        let image = image.into_rgba8();
        let (width, height) = image.dimensions();
        let rgba = image.into_raw();
        native_options.viewport.icon = Some(Arc::new(eframe::egui::IconData {
            rgba,
            width,
            height,
        }));
    }

    eframe::run_native(
        "Drawing Tablet Server",
        native_options,
        Box::new(|_cc| Ok(Box::new(app))),
    )
    .map_err(|e| anyhow::anyhow!("Eframe error: {}", e))
}

pub fn run_server_pipeline(
    config: ServerConfig,
    stats: Arc<Mutex<ServerStats>>,
    running: Arc<AtomicBool>,
) {
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .unwrap();

    rt.block_on(async move {
        if let Err(e) = run_server_async(config, stats.clone(), running.clone()).await {
            error!("Server error: {}", e);
            let mut guard = stats.lock().unwrap();
            guard.status = format!("Error: {}", e);
            running.store(false, Ordering::SeqCst);
        }
    });
}

pub async fn run_server_async(
    config: ServerConfig,
    stats: Arc<Mutex<ServerStats>>,
    running: Arc<AtomicBool>,
) -> Result<()> {
    info!("Server configuration: {:?}", config);

    {
        let mut guard = stats.lock().unwrap();
        guard.status = "Selecting Screen...".to_string();
    }

    // Select monitor via portal
    info!("Opening screen selection dialog...");
    let monitor = select_monitor(None).await?;
    info!(
        "Selected monitor: {}x{} (node {})",
        monitor.width, monitor.height, monitor.node_id
    );

    {
        let mut guard = stats.lock().unwrap();
        guard.status = "Initializing...".to_string();
        guard.resolution = format!("{}x{}", monitor.width, monitor.height);
    }

    // Create virtual tablet
    info!("Creating virtual tablet device...");
    let tablet = VirtualTablet::new(monitor.width, monitor.height, &config.tablet)?;
    info!("Virtual tablet created");

    // Create encoder
    let encoder_config = EncoderConfig {
        width: monitor.width,
        height: monitor.height,
        fps: config.fps,
        bitrate: config.bitrate,
        keyframe_interval: config.keyframe_interval,
    };
    let encoder = VideoEncoder::new(encoder_config)?;

    // Start screen capture
    info!("Starting screen capture...");
    let capture = ScreenCapture::start(&monitor)?;

    // Start UDP server (for video streaming to client)
    let udp_bind_addr: SocketAddr = format!("0.0.0.0:{}", config.port).parse()?;
    info!("Starting UDP server on {} (video)", udp_bind_addr);
    let udp_server = UdpServer::bind(udp_bind_addr)?;

    // Start TCP server (for input/control from client)
    let tcp_bind_addr: SocketAddr = format!("0.0.0.0:{}", config.port).parse()?;
    info!("Starting TCP server on {} (input/control)", tcp_bind_addr);
    let tcp_server = TcpServer::bind(tcp_bind_addr).await?;

    // Advertise via mDNS so clients can discover us without a manual IP
    let service_name = format!("Drawing Tablet ({})", get_hostname());
    let _avahi = match AvahiService::start(&service_name, config.port) {
        Some(svc) => {
            info!("Advertising as '{}' via mDNS", service_name);
            Some(svc)
        }
        None => {
            warn!("avahi-publish not available – mDNS discovery disabled");
            None
        }
    };

    {
        let mut guard = stats.lock().unwrap();
        guard.status = "Running".to_string();
    }

    // Run the main server loop
    run_server(
        udp_server,
        tcp_server,
        capture,
        encoder,
        tablet,
        monitor.width,
        monitor.height,
        stats,
        running,
    )
    .await
}

/// Bookkeeping for the currently connected client.
struct ClientState {
    tcp_client_id: u64,
    /// Destination for the video stream; set once the client reports its UDP
    /// port after the handshake.
    udp_addr: Option<SocketAddr>,
    /// True once the client has sent its `Connect` packet. Nothing but the
    /// handshake reply may be sent before that: an early heartbeat used to
    /// arrive before `ConnectAck` and break the client's handshake.
    handshaked: bool,
}

/// Publish the current client's UDP destination for the video sender thread.
fn publish_video_addr(video_addr: &Mutex<Option<SocketAddr>>, client: &Option<ClientState>) {
    *video_addr.lock().unwrap() = client.as_ref().and_then(|c| c.udp_addr);
}

/// Counters updated by the encoder and sender threads and published to the
/// GUI once per second.
#[derive(Default)]
struct StreamCounters {
    encoded: AtomicU64,
    sent: AtomicU64,
    fragments: AtomicU64,
    keyframes: AtomicU64,
}

/// Accumulates "captured -> sent" latency samples (microseconds).
#[derive(Default)]
struct LatencyStats {
    sum_us: u64,
    max_us: u64,
    count: u64,
}

impl LatencyStats {
    fn record(&mut self, us: u64) {
        self.sum_us += us;
        self.max_us = self.max_us.max(us);
        self.count += 1;
    }

    fn average_us(&self) -> Option<u64> {
        (self.count > 0).then(|| self.sum_us / self.count)
    }

    fn max_us(&self) -> u64 {
        self.max_us
    }

    fn reset(&mut self) {
        *self = Self::default();
    }
}

async fn run_server(
    udp_server: UdpServer,
    tcp_server: TcpServer,
    capture: ScreenCapture,
    encoder: VideoEncoder,
    tablet: VirtualTablet,
    width: u32,
    height: u32,
    stats: Arc<Mutex<ServerStats>>,
    running: Arc<AtomicBool>,
) -> Result<()> {
    let (frame_tx, frame_rx) = mpsc::sync_channel::<Frame>(1);
    let (encoded_tx, encoded_rx) = mpsc::sync_channel(2);
    // Unbounded input channel: the event volume is tiny (bounded by the
    // client's stylus rate) and dropping the *newest* event could strand a
    // pen "down" state. The retimer's bounded buffer applies drop-oldest,
    // matching the behaviour this replaced.
    let (input_tx, input_rx) = mpsc::channel::<InputPacket>();
    let keyframe_flag = Arc::new(AtomicBool::new(false));
    let counters = Arc::new(StreamCounters::default());
    let latency_stats = Arc::new(Mutex::new(LatencyStats::default()));

    // Destination for the video stream, updated as the client connects and
    // reports its UDP port. Read by the video sender thread, one lock per
    // frame (uncontended in practice).
    let video_addr: Arc<Mutex<Option<SocketAddr>>> = Arc::new(Mutex::new(None));

    // Encoder thread: capture frames -> hardware encoder -> sender channel.
    let encoder_thread = {
        let keyframe_flag = keyframe_flag.clone();
        let counters = counters.clone();
        let mut encoder = encoder;
        std::thread::Builder::new()
            .name("dt-encoder".to_string())
            .spawn(move || {
                // Reused across iterations so draining costs no allocation.
                let mut encoded_frames: Vec<EncodedFrame> = Vec::new();
                let mut last_pts: u64 = 0;

                'encode: while let Ok(frame) = frame_rx.recv() {
                    if keyframe_flag.swap(false, Ordering::SeqCst) {
                        encoder.request_keyframe();
                    }

                    // The frame carries its capture time; it becomes the
                    // timestamp the client sees and lets the sender thread
                    // measure capture -> send latency.
                    let capture_us = frame.capture_us;

                    encoded_frames.clear();
                    match encoder.encode(frame, &mut encoded_frames) {
                        Ok(()) => {
                            for mut encoded in encoded_frames.drain(..) {
                                // Keep timestamps strictly increasing even when
                                // several outputs are drained for one capture.
                                let pts = capture_us.max(last_pts + 1);
                                last_pts = pts;
                                encoded.pts = pts;

                                counters.encoded.fetch_add(1, Ordering::Relaxed);
                                if encoded.is_keyframe {
                                    counters.keyframes.fetch_add(1, Ordering::Relaxed);
                                }

                                if encoded_tx.send(encoded).is_err() {
                                    break 'encode;
                                }
                            }
                        }
                        Err(e) => {
                            warn!("Encoding error: {}", e);
                        }
                    }
                }
                debug!("Encoder thread stopped");
            })
            .expect("failed to spawn encoder thread")
    };

    // Video sender thread: fragments each encoded frame and blasts it over
    // UDP without touching the main loop, so keyframe bursts can never stall
    // input processing.
    let video_thread = {
        let video_addr = video_addr.clone();
        let counters = counters.clone();
        let latency_stats = latency_stats.clone();
        std::thread::Builder::new()
            .name("dt-video-send".to_string())
            .spawn(move || {
                video_sender_loop(udp_server, encoded_rx, video_addr, counters, latency_stats)
            })
            .expect("failed to spawn video sender thread")
    };

    // Input thread: injects queued events into the virtual tablet with
    // bounded retiming.
    let input_thread = std::thread::Builder::new()
        .name("dt-input".to_string())
        .spawn(move || input_loop(tablet, input_rx))
        .expect("failed to spawn input thread");

    // Channel for TCP messages from clients
    let (tcp_msg_tx, mut tcp_msg_rx) = tokio_mpsc::channel::<TcpMessage>(256);

    let mut client: Option<ClientState> = None;
    let mut ticks: u64 = 0;
    let mut last_heartbeat = Instant::now();
    let mut keyframe_requested = false;
    let mut last_forced_keyframe: Option<Instant> = None;
    let mut frame_pacer: FramePacer<Frame> = FramePacer::new();

    // Per-second stats
    let mut stats_captured: u64 = 0;
    let mut stats_dropped_frames: u64 = 0;
    let mut stats_encode_errors: u64 = 0;
    let mut last_stats = Instant::now();

    info!("Waiting for client connection (TCP for input, UDP for video)...");

    while running.load(Ordering::SeqCst) {
        tokio::select! {
            // Accept new TCP connections
            result = tcp_server.accept(tcp_msg_tx.clone()) => {
                match result {
                    Ok((client_id, addr)) => {
                        // If there's already a client, disconnect them
                        if let Some(old_client) = client.take() {
                            info!("Disconnecting previous client {}", old_client.tcp_client_id);
                            tcp_server.disconnect_client(old_client.tcp_client_id).await;
                        }

                        // For now, use the TCP address as the UDP address too
                        // (client will be on the same IP, just different port for UDP)
                        let udp_addr = SocketAddr::new(addr.ip(), addr.port());

                        client = Some(ClientState {
                            tcp_client_id: client_id,
                            udp_addr: Some(udp_addr),
                            handshaked: false,
                        });
                        publish_video_addr(&video_addr, &client);

                        // Restart the heartbeat interval for this client so
                        // nothing is sent before its handshake completes.
                        last_heartbeat = Instant::now();

                        info!("New client {} connected from {}", client_id, addr);
                    }
                    Err(e) => {
                        warn!("Failed to accept TCP connection: {}", e);
                    }
                }
            }

            // Process TCP messages from clients
            Some(msg) = tcp_msg_rx.recv() => {
                // Only process messages from the current client
                if let Some(ref mut c) = client {
                    if msg.client_id == c.tcp_client_id {
                        match decode_packet(&msg.data) {
                            Ok(DecodedPacket::Control(ctrl)) => {
                                match &ctrl {
                                    ControlPacket::RequestKeyframe => {
                                        // Debounce: a recovery IDR is a large
                                        // burst, so a lossy link must not turn
                                        // "one request per lost frame" into an
                                        // IDR storm that makes loss worse.
                                        let debounce = Duration::from_millis(250);
                                        if last_forced_keyframe
                                            .map_or(true, |t| t.elapsed() >= debounce)
                                        {
                                            last_forced_keyframe = Some(Instant::now());
                                            keyframe_requested = true;
                                        }
                                    }
                                    ControlPacket::Connect { version } => {
                                        info!("Client protocol version: {}", version);
                                        if *version != PROTOCOL_VERSION {
                                            let nack = encode_control(&ControlPacket::ConnectNack {
                                                reason: format!(
                                                    "Protocol version mismatch: expected {}, got {}",
                                                    PROTOCOL_VERSION, version
                                                ),
                                            })?;
                                            let _ = tcp_server.send_to_client(c.tcp_client_id, &nack).await;
                                            tcp_server.disconnect_client(c.tcp_client_id).await;
                                            client = None;
                                            publish_video_addr(&video_addr, &client);
                                            continue;
                                        }
                                        // Send ConnectAck
                                        let ack = encode_control(&ControlPacket::ConnectAck { width, height })?;
                                        let _ = tcp_server.send_to_client(c.tcp_client_id, &ack).await;
                                        c.handshaked = true;
                                        info!("Client handshake complete");
                                    }
                                    ControlPacket::Disconnect => {
                                        info!("Client {} requested disconnect", c.tcp_client_id);
                                        tcp_server.disconnect_client(c.tcp_client_id).await;
                                        client = None;
                                        publish_video_addr(&video_addr, &client);
                                    }
                                    ControlPacket::Heartbeat => {
                                        debug!("Heartbeat from client {}", c.tcp_client_id);
                                    }
                                    ControlPacket::Ping => {
                                        // Echo immediately so the client can
                                        // estimate the network round trip.
                                        let pong = encode_control(&ControlPacket::Ping)?;
                                        let _ = tcp_server.send_to_client(c.tcp_client_id, &pong).await;
                                    }
                                    ControlPacket::SetUdpPort { port } => {
                                        // Client tells us which UDP port to send video to
                                        if let Some(ref mut state) = client {
                                            if let Some(ref mut udp) = state.udp_addr {
                                                udp.set_port(*port);
                                                info!("Client UDP port set to {}", port);
                                            }
                                        }
                                        publish_video_addr(&video_addr, &client);
                                    }
                                    ControlPacket::SetRefreshRate { hz } => {
                                        // Cap the video rate to the client's
                                        // panel refresh: anything faster just
                                        // queues up and adds latency.
                                        let fps = if *hz == 0 {
                                            0 // unknown -> no cap
                                        } else {
                                            (*hz).clamp(24, 360) as u32
                                        };
                                        frame_pacer.set_fps(fps);
                                        let cap = if fps == 0 {
                                            "none".to_string()
                                        } else {
                                            format!("{} fps", fps)
                                        };
                                        info!(
                                            "Client display refresh reported: {} Hz (frame cap: {})",
                                            hz, cap
                                        );
                                    }
                                    _ => {}
                                }
                            }
                            Ok(DecodedPacket::Input(input)) => {
                                // Update stats for buttons
                                if let InputEvent::StylusButton { button, pressed } = input.event {
                                    if button < 2 {
                                        let mut guard = stats.lock().unwrap();
                                        guard.stylus_buttons[button as usize] = pressed;
                                    }
                                }

                                // Hand the event to the input thread right away.
                                if input_tx.send(input).is_err() {
                                    // The input thread is gone; without it
                                    // there is no input at all, so stop the
                                    // server instead of streaming silently.
                                    error!("Input thread stopped; shutting down");
                                    running.store(false, Ordering::SeqCst);
                                }
                            }
                            Ok(DecodedPacket::Video(_)) => {
                                // Ignore video packets from clients
                            }
                            Err(e) => {
                                debug!("Failed to decode TCP packet: {}", e);
                            }
                        }
                    }
                }
            }

            // Periodic tasks (heartbeat, stats, liveness checks)
            _ = tokio::time::sleep(Duration::from_millis(1)) => {
                // Check if client is still connected
                if let Some(ref c) = client {
                    if !tcp_server.is_client_connected(c.tcp_client_id).await {
                        info!("Client {} disconnected", c.tcp_client_id);
                        client = None;
                        publish_video_addr(&video_addr, &client);
                    }
                }
            }
        }

        ticks = ticks.wrapping_add(1);

        // Update connected client in GUI stats
        if ticks.is_multiple_of(60) {
            let mut guard = stats.lock().unwrap();
            guard.connected_client = client
                .as_ref()
                .and_then(|c| c.udp_addr.map(|a| a.to_string()));
        }

        // Check for keyframe requests
        if keyframe_requested {
            keyframe_flag.store(true, Ordering::SeqCst);
            keyframe_requested = false;
        }

        // 1. Forward the *newest* captured frame to the encoder, paced to the
        //    client's display refresh rate. Older frames replace the pending
        //    one, and a paced frame is deferred (never dropped), so the last
        //    frame of a change still gets sent.
        if client.is_some() {
            while let Some(frame) = capture.try_recv_frame() {
                stats_captured += 1;
                frame_pacer.push(frame);
            }

            if let Some(frame) = frame_pacer.take_ready(Instant::now()) {
                match frame_tx.try_send(frame) {
                    Ok(_) => {}
                    Err(mpsc::TrySendError::Full(_)) => {
                        stats_dropped_frames += 1;
                    }
                    Err(_) => {
                        running.store(false, Ordering::SeqCst);
                    }
                }
            }
        } else {
            frame_pacer.clear();
        }

        // 2. Encoded frames are shipped to the client by the dedicated video
        //    sender thread; this loop never blocks on video bursts.

        // Send heartbeat every second via TCP, but only to a client that has
        // completed its handshake.
        if should_send_heartbeat(&client, last_heartbeat, Instant::now()) {
            if let Some(ref c) = client {
                let heartbeat = encode_control(&ControlPacket::Heartbeat)?;
                let _ = tcp_server.send_to_client(c.tcp_client_id, &heartbeat).await;
            }
            last_heartbeat = Instant::now();
        }

        // Update stats once per second
        if last_stats.elapsed() > Duration::from_secs(1) {
            {
                let mut guard = stats.lock().unwrap();
                guard.captured = stats_captured;
                guard.encoded = counters.encoded.swap(0, Ordering::Relaxed);
                guard.dropped = stats_dropped_frames;
                guard.sent = counters.sent.swap(0, Ordering::Relaxed);
                guard.keyframes = counters.keyframes.swap(0, Ordering::Relaxed);
                guard.fragments = counters.fragments.swap(0, Ordering::Relaxed);
                guard.errors = stats_encode_errors;
            }

            {
                let mut latency = latency_stats.lock().unwrap();
                if let Some(avg) = latency.average_us() {
                    info!(
                        "capture->send: avg {:.1} ms, max {:.1} ms",
                        avg as f64 / 1000.0,
                        latency.max_us() as f64 / 1000.0
                    );
                    latency.reset();
                }
            }

            stats_captured = 0;
            stats_dropped_frames = 0;
            stats_encode_errors = 0;
            last_stats = Instant::now();
        }
    }

    info!("Server stopped");

    // Closing the channels shuts down the worker threads.
    drop(input_tx);
    drop(frame_tx);

    if let Err(e) = encoder_thread.join() {
        warn!("Failed to join encoder thread: {:?}", e);
    }
    if let Err(e) = video_thread.join() {
        warn!("Failed to join video sender thread: {:?}", e);
    }
    if let Err(e) = input_thread.join() {
        warn!("Failed to join input thread: {:?}", e);
    }

    Ok(())
}

/// Send encoded video frames to the connected client.
///
/// Runs on a dedicated thread so keyframe bursts (hundreds of datagrams) can
/// never stall the main loop that services input and control packets.
fn video_sender_loop(
    udp_server: UdpServer,
    encoded_rx: mpsc::Receiver<EncodedFrame>,
    video_addr: Arc<Mutex<Option<SocketAddr>>>,
    counters: Arc<StreamCounters>,
    latency_stats: Arc<Mutex<LatencyStats>>,
) {
    // Reused for every fragment so serialization is allocation-free.
    let mut packet_buf: Vec<u8> = Vec::with_capacity(3 + 25 + MAX_FRAGMENT_DATA);
    let mut sequence: u32 = 0;

    while let Ok(encoded) = encoded_rx.recv() {
        let addr = match *video_addr.lock().unwrap() {
            Some(addr) => addr,
            None => continue, // No client connected; the frame is already stale.
        };

        // The frame's timestamp is when it was captured, so this is the full
        // capture -> send (queue + encode + send) latency.
        let now_us = monotonic_us();
        if now_us > encoded.pts {
            latency_stats.lock().unwrap().record(now_us - encoded.pts);
        }

        let data = &encoded.data;
        let fragment_count = data.len().div_ceil(MAX_FRAGMENT_DATA) as u16;
        let mut all_sent = true;

        for i in 0..fragment_count {
            let start = i as usize * MAX_FRAGMENT_DATA;
            let end = (start + MAX_FRAGMENT_DATA).min(data.len());

            let fragment = VideoFragment {
                sequence,
                timestamp: encoded.pts,
                is_keyframe: encoded.is_keyframe,
                fragment_index: i,
                fragment_count,
                data: &data[start..end],
            };

            match encode_video_fragment_into(&mut packet_buf, &fragment) {
                Ok(()) => {
                    if let Err(e) = udp_server.send(&packet_buf, addr) {
                        warn!("Failed to send video fragment: {}", e);
                        all_sent = false;
                        break;
                    }
                }
                Err(e) => {
                    warn!("Failed to encode video fragment: {}", e);
                    all_sent = false;
                    break;
                }
            }
        }

        if all_sent {
            counters.sent.fetch_add(1, Ordering::Relaxed);
            counters
                .fragments
                .fetch_add(fragment_count as u64, Ordering::Relaxed);
        }
        sequence = sequence.wrapping_add(1);
    }

    debug!("Video sender thread stopped");
}

/// Inject input events into the virtual tablet.
///
/// Runs on its own thread: events are applied as soon as they arrive instead
/// of waiting for the main loop's next tick, and video traffic cannot delay
/// them.
fn input_loop(mut tablet: VirtualTablet, input_rx: mpsc::Receiver<InputPacket>) {
    let mut retimer = EventRetimer::new();

    loop {
        // Wake up when a new event arrives or the next buffered event is due.
        // The 1 ms floor matches the retimer's emission interval and keeps
        // the loop from spinning when several events are due at once.
        let timeout = match retimer.time_until_next() {
            Some(until_next) => until_next.max(Duration::from_millis(1)),
            None => Duration::from_millis(100),
        };

        match input_rx.recv_timeout(timeout) {
            Ok(packet) => retimer.push(packet),
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }

        for packet in retimer.drain_ready() {
            if let Err(e) = tablet.process_event(&packet.event) {
                warn!("Failed to process input: {}", e);
            }
        }
    }

    debug!("Input thread stopped");
}

/// Whether a keep-alive may be sent to the current client.
///
/// Heartbeats must not be sent before the client's handshake completes: the
/// client reads its first packet expecting `ConnectAck`, and an early heartbeat
/// used to make it treat the handshake as failed.
fn should_send_heartbeat(
    client: &Option<ClientState>,
    last_heartbeat: Instant,
    now: Instant,
) -> bool {
    match client {
        Some(c) if c.handshaked => {
            now.saturating_duration_since(last_heartbeat) > Duration::from_secs(1)
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn connected(handshaked: bool) -> Option<ClientState> {
        Some(ClientState {
            tcp_client_id: 1,
            udp_addr: None,
            handshaked,
        })
    }

    #[test]
    fn no_heartbeat_before_the_handshake_completes() {
        // Regression: the client's first packet must be ConnectAck. A stale
        // heartbeat timer used to fire the moment a client connected, so the
        // client read a Heartbeat where it expected ConnectAck and gave up.
        let now = Instant::now();
        let stale = now.checked_sub(Duration::from_secs(2)).unwrap_or(now);
        assert!(!should_send_heartbeat(&connected(false), stale, now));
    }

    #[test]
    fn heartbeat_is_sent_after_the_handshake_and_interval() {
        let now = Instant::now();

        let stale = now.checked_sub(Duration::from_secs(2)).unwrap_or(now);
        assert!(should_send_heartbeat(&connected(true), stale, now));

        let recent = now.checked_sub(Duration::from_millis(500)).unwrap_or(now);
        assert!(!should_send_heartbeat(&connected(true), recent, now));
    }

    #[test]
    fn no_heartbeat_without_a_client() {
        let now = Instant::now();
        let stale = now.checked_sub(Duration::from_secs(2)).unwrap_or(now);
        assert!(!should_send_heartbeat(&None, stale, now));
    }

    #[test]
    fn latency_stats_report_average_and_max_since_reset() {
        let mut stats = LatencyStats::default();
        assert_eq!(stats.average_us(), None);
        assert_eq!(stats.max_us(), 0);

        stats.record(1_000);
        stats.record(3_000);
        assert_eq!(stats.average_us(), Some(2_000));
        assert_eq!(stats.max_us(), 3_000);

        stats.reset();
        assert_eq!(stats.average_us(), None);
        assert_eq!(stats.max_us(), 0);
    }
}
