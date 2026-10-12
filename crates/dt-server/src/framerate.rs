//! Frame pacing for the capture -> encoder path.

use std::time::{Duration, Instant};

/// Holds the newest captured frame and releases it no faster than the target
/// rate.
///
/// The client reports its display refresh rate; sending frames faster than the
/// panel can present them only grows the display queue and adds constant
/// latency. (Moonlight asks the host to encode at the client's refresh rate for
/// the same reason.) `fps == 0` disables the cap.
///
/// Pushes that arrive while the cap blocks a release replace the pending frame
/// (only the newest is ever held, so latency never grows), but a pending frame
/// is *deferred*, not dropped — otherwise the last frame of a screen change
/// could be lost forever on a damage-driven capture source.
pub struct FramePacer<T> {
    min_interval: Duration,
    next_allowed: Instant,
    pending: Option<T>,
}

impl<T> FramePacer<T> {
    /// Create an uncapped pacer.
    pub fn new() -> Self {
        Self {
            min_interval: Duration::ZERO,
            next_allowed: Instant::now(),
            pending: None,
        }
    }

    /// Set the cap in frames per second (0 = uncapped).
    pub fn set_fps(&mut self, fps: u32) {
        self.min_interval = if fps == 0 {
            Duration::ZERO
        } else {
            Duration::from_secs_f64(1.0 / fps as f64)
        };

        // Let the next frame through immediately so that applying a new cap
        // never stalls the stream.
        self.next_allowed = Instant::now();
    }

    /// Replace the pending frame with the newest one.
    pub fn push(&mut self, frame: T) {
        self.pending = Some(frame);
    }

    /// Take the pending frame if the rate cap allows releasing it now.
    pub fn take_ready(&mut self, now: Instant) -> Option<T> {
        self.pending.as_ref()?;

        if !self.min_interval.is_zero() && now < self.next_allowed {
            return None;
        }

        self.next_allowed = now + self.min_interval;
        self.pending.take()
    }

    /// Drop the pending frame (e.g. the client disconnected).
    pub fn clear(&mut self) {
        self.pending = None;
    }
}

impl<T> Default for FramePacer<T> {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uncapped_pacer_releases_the_newest_frame_immediately() {
        let mut pacer = FramePacer::new();
        assert!(pacer.take_ready(Instant::now()).is_none());

        pacer.push(1);
        assert_eq!(pacer.take_ready(Instant::now()), Some(1));
        assert!(pacer.take_ready(Instant::now()).is_none());
    }

    #[test]
    fn cap_limits_the_release_rate() {
        let mut pacer = FramePacer::new();
        pacer.set_fps(60); // ~16.67ms
        let t = Instant::now();

        pacer.push(1);
        assert_eq!(pacer.take_ready(t), Some(1));

        pacer.push(2);
        assert!(pacer.take_ready(t + Duration::from_millis(5)).is_none());
        assert_eq!(pacer.take_ready(t + Duration::from_millis(17)), Some(2));
    }

    #[test]
    fn only_the_newest_frame_is_kept() {
        let mut pacer = FramePacer::new();
        pacer.set_fps(30); // 33ms
        let t = Instant::now();

        pacer.push(1);
        pacer.push(2);
        pacer.push(3);

        assert_eq!(pacer.take_ready(t + Duration::from_millis(40)), Some(3));
    }

    #[test]
    fn a_blocked_frame_is_deferred_not_dropped() {
        // The last frame of a screen change must still be delivered (it is the
        // terminal frame) — just at the paced rate.
        let mut pacer = FramePacer::new();
        pacer.set_fps(60);
        let t = Instant::now();

        pacer.push(1);
        assert_eq!(pacer.take_ready(t), Some(1));

        pacer.push(2);
        assert!(pacer.take_ready(t + Duration::from_millis(5)).is_none());
        // No newer frame arrives, but frame 2 is still delivered later.
        assert_eq!(pacer.take_ready(t + Duration::from_millis(20)), Some(2));
    }

    #[test]
    fn polling_at_1ms_holds_the_cap_rate_for_a_slightly_faster_source() {
        // ~61 fps source, 60 fps cap. With a caller that keeps only the newest
        // frame and re-checks on every loop tick, the output rate must stay at
        // the cap instead of halving.
        let mut pacer = FramePacer::new();
        pacer.set_fps(60);

        let source_period = Duration::from_micros(16_400);
        let tick = Duration::from_millis(1);
        let start = Instant::now();
        let mut t = start;
        let mut next_source = start + source_period;
        let mut sent = 0;

        while t - start < Duration::from_millis(2000) {
            t += tick;
            if t >= next_source {
                pacer.push(sent); // newest frame replaces any pending one
                next_source += source_period;
            }
            if pacer.take_ready(t).is_some() {
                sent += 1;
            }
        }

        // ~2s at 60fps ≈ 120 frames; allow slack for tick granularity.
        assert!((115..=122).contains(&sent), "expected ~60fps, got {}", sent);
    }

    #[test]
    fn clear_drops_the_pending_frame() {
        let mut pacer = FramePacer::new();
        pacer.set_fps(120);

        pacer.push(1);
        pacer.clear();

        assert!(pacer.take_ready(Instant::now() + Duration::from_secs(1)).is_none());
    }
}
