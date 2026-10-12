//! Event retiming buffer for smooth input delivery.
//!
//! This module buffers incoming input events and emits them with proper
//! timing based on their original timestamps, preventing bursts from
//! network jitter that can confuse applications like Krita.

use dt_protocol::InputPacket;
use std::collections::VecDeque;
use std::time::{Duration, Instant};
use tracing::debug;

/// Maximum events to buffer (prevents unbounded memory usage).
const MAX_BUFFER_SIZE: usize = 64;

/// Maximum delay to wait for an event (prevents excessive lag).
///
/// Anything scheduled further in the future than this is treated as a stale
/// clock offset and re-synced instead of buffered — a drawing session cares
/// much more about responsiveness than about millisecond-level pacing.
const MAX_DELAY_MS: u64 = 3;

/// Minimum time between events (prevents too-fast emission).
const MIN_INTERVAL_US: u64 = 1000; // 1ms = 1000Hz max

/// Buffers input events and emits them with proper timing.
pub struct EventRetimer {
    /// Buffered events with their target emission time.
    buffer: VecDeque<(Instant, InputPacket)>,
    /// Timestamp offset: maps client timestamps to local Instant.
    /// Set on first event to sync clocks.
    clock_offset: Option<(u64, Instant)>, // (client_timestamp_us, local_instant)
    /// Last emission time to enforce minimum interval.
    last_emit: Option<Instant>,
}

impl EventRetimer {
    pub fn new() -> Self {
        Self {
            buffer: VecDeque::with_capacity(MAX_BUFFER_SIZE),
            clock_offset: None,
            last_emit: None,
        }
    }

    /// Reset the retimer state (e.g., on disconnect).
    pub fn reset(&mut self) {
        self.buffer.clear();
        self.clock_offset = None;
        self.last_emit = None;
    }

    /// Add an event to the buffer.
    /// Returns the event immediately if retiming is not beneficial.
    pub fn push(&mut self, packet: InputPacket) {
        let now = Instant::now();

        // Initialize clock sync on first event
        if self.clock_offset.is_none() {
            self.clock_offset = Some((packet.timestamp, now));
            debug!(
                "Event retimer: clock sync initialized, client_ts={}",
                packet.timestamp
            );
        }

        // Calculate when this event should be emitted
        let mut target_time = self.client_ts_to_instant(packet.timestamp, now);

        // Don't buffer events too far in the future. When the mapping puts an
        // event further ahead than that, the clock offset itself is stale
        // (e.g. it was established from a packet that suffered an unusually
        // long network delay). Re-sync the offset so the extra latency is
        // paid once instead of being re-applied to every following event.
        let max_future = now + Duration::from_millis(MAX_DELAY_MS);
        if target_time > max_future {
            self.clock_offset = Some((packet.timestamp, now));
            target_time = now;
        }

        // Add to buffer
        if self.buffer.len() >= MAX_BUFFER_SIZE {
            // Drop oldest event if buffer is full
            self.buffer.pop_front();
            debug!("Event retimer: buffer full, dropped oldest event");
        }
        self.buffer.push_back((target_time, packet));
    }

    /// Get the next event that's ready to be emitted, if any.
    /// Returns None if no events are ready yet.
    pub fn pop_ready(&mut self) -> Option<InputPacket> {
        let now = Instant::now();

        // Check minimum interval
        if let Some(last) = self.last_emit {
            if now.duration_since(last) < Duration::from_micros(MIN_INTERVAL_US) {
                return None;
            }
        }

        // Check if front event is ready
        if let Some((target_time, _)) = self.buffer.front() {
            if now >= *target_time {
                self.last_emit = Some(now);
                return self.buffer.pop_front().map(|(_, packet)| packet);
            }
        }

        None
    }

    /// Get all events that are ready to be emitted.
    pub fn drain_ready(&mut self) -> Vec<InputPacket> {
        let mut ready = Vec::new();
        while let Some(packet) = self.pop_ready() {
            ready.push(packet);
        }
        ready
    }

    /// Time until the next event is ready, or None if buffer is empty.
    pub fn time_until_next(&self) -> Option<Duration> {
        let now = Instant::now();

        self.buffer.front().map(|(target_time, _)| {
            if *target_time > now {
                *target_time - now
            } else {
                Duration::ZERO
            }
        })
    }

    /// Check if there are buffered events.
    pub fn has_pending(&self) -> bool {
        !self.buffer.is_empty()
    }

    /// Convert a client timestamp (microseconds, monotonic) to a local Instant.
    fn client_ts_to_instant(&self, client_ts: u64, now: Instant) -> Instant {
        if let Some((base_client_ts, base_instant)) = self.clock_offset {
            // Calculate delta from first event
            if client_ts >= base_client_ts {
                let delta_us = client_ts - base_client_ts;
                base_instant + Duration::from_micros(delta_us)
            } else {
                // Client timestamp wrapped or went backwards, use now
                now
            }
        } else {
            now
        }
    }
}

impl Default for EventRetimer {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use dt_protocol::InputEvent;

    fn event(ts: u64) -> InputPacket {
        InputPacket {
            timestamp: ts,
            event: InputEvent::StylusMove {
                x: 0.5,
                y: 0.5,
                pressure: 0.5,
                tilt_x: 0.0,
                tilt_y: 0.0,
            },
        }
    }

    #[test]
    fn large_client_timestamp_jump_is_not_buffered_for_long() {
        let mut retimer = EventRetimer::new();

        // First event establishes the clock offset.
        retimer.push(event(0));
        assert_eq!(retimer.drain_ready().len(), 1);

        // Clear the minimum-interval throttle.
        std::thread::sleep(Duration::from_millis(2));

        // The client timestamp is far ahead of the server-side clock mapping
        // (e.g. the offset was established from a delayed packet). The event
        // must not be withheld for tens of milliseconds.
        retimer.push(event(500_000)); // +500ms on the client clock

        std::thread::sleep(Duration::from_millis(4));
        let ready = retimer.drain_ready();
        assert_eq!(
            ready.len(),
            1,
            "event with a huge timestamp jump was buffered too long"
        );
        assert_eq!(ready[0].timestamp, 500_000);
    }

    #[test]
    fn events_after_a_jump_are_paced_against_the_fresh_offset() {
        let mut retimer = EventRetimer::new();

        retimer.push(event(0));
        assert_eq!(retimer.drain_ready().len(), 1);
        std::thread::sleep(Duration::from_millis(2));

        // Huge jump: the offset is stale, so this event must be emitted now
        // and the offset re-synced.
        retimer.push(event(500_000));
        std::thread::sleep(Duration::from_millis(4));
        assert_eq!(retimer.drain_ready().len(), 1);

        // The next event is 30ms later on the client clock. Against the fresh
        // offset that is ~26ms in the future, so it must not be emitted
        // immediately (pacing preserved) and must be emitted once that time
        // has passed (no stale-offset tax).
        retimer.push(event(530_000));
        assert_eq!(
            retimer.drain_ready().len(),
            0,
            "future event must be paced, not emitted instantly"
        );

        std::thread::sleep(Duration::from_millis(40));
        assert_eq!(retimer.drain_ready().len(), 1);
    }
}
