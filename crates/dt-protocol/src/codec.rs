//! Serialization and framing for network packets.

use crate::packets::*;
use thiserror::Error;

/// Errors that can occur during packet encoding/decoding.
#[derive(Debug, Error)]
pub enum CodecError {
    #[error("packet too large: {size} bytes (max {max})")]
    PacketTooLarge { size: usize, max: usize },

    #[error("packet too small: need at least 3 bytes for header")]
    PacketTooSmall,

    #[error("unknown packet type: {0}")]
    UnknownPacketType(u8),

    #[error("serialization error: {0}")]
    SerializationError(#[from] bincode::Error),

    #[error("incomplete packet: expected {expected} bytes, got {actual}")]
    IncompletePacket { expected: usize, actual: usize },
}

/// Encode a video packet into a framed packet.
pub fn encode_video(packet: &VideoPacket) -> Result<Vec<u8>, CodecError> {
    let payload = bincode::serialize(packet)?;
    encode_framed(PacketType::Video, &payload)
}

/// Borrowed view of a single video fragment.
///
/// Used for allocation-free encoding in the hot path (the server serializes
/// one of these per UDP datagram, up to hundreds per frame).
#[derive(Debug, Clone, Copy)]
pub struct VideoFragment<'a> {
    pub sequence: u32,
    pub timestamp: u64,
    pub is_keyframe: bool,
    pub fragment_index: u16,
    pub fragment_count: u16,
    pub data: &'a [u8],
}

/// Serialize a video fragment into `out`, clearing it first.
///
/// Produces exactly the same bytes as `encode_video` with an equivalent
/// `VideoPacket`, but reuses the caller's buffer instead of allocating a
/// payload (and a copy of the data) for every fragment.
pub fn encode_video_fragment_into(
    out: &mut Vec<u8>,
    fragment: &VideoFragment<'_>,
) -> Result<(), CodecError> {
    // bincode's (fixed-int, little-endian) layout for `VideoPacket`:
    // u32 sequence | u64 timestamp | u8 is_keyframe | u16 fragment_index |
    // u16 fragment_count | u64 data length | data.
    const VIDEO_HEADER_LEN: usize = 4 + 8 + 1 + 2 + 2 + 8;
    let payload_len = VIDEO_HEADER_LEN + fragment.data.len();

    if payload_len > MAX_PACKET_SIZE - 3 {
        return Err(CodecError::PacketTooLarge {
            size: payload_len + 3,
            max: MAX_PACKET_SIZE,
        });
    }

    out.clear();
    out.reserve(3 + payload_len);
    out.push(PacketType::Video as u8);
    out.extend_from_slice(&(payload_len as u16).to_le_bytes());
    out.extend_from_slice(&fragment.sequence.to_le_bytes());
    out.extend_from_slice(&fragment.timestamp.to_le_bytes());
    out.push(fragment.is_keyframe as u8);
    out.extend_from_slice(&fragment.fragment_index.to_le_bytes());
    out.extend_from_slice(&fragment.fragment_count.to_le_bytes());
    out.extend_from_slice(&(fragment.data.len() as u64).to_le_bytes());
    out.extend_from_slice(fragment.data);
    Ok(())
}

/// Encode an input packet into a framed packet.
pub fn encode_input(packet: &InputPacket) -> Result<Vec<u8>, CodecError> {
    let payload = bincode::serialize(packet)?;
    encode_framed(PacketType::Input, &payload)
}

/// Encode a control packet into a framed packet.
pub fn encode_control(packet: &ControlPacket) -> Result<Vec<u8>, CodecError> {
    let payload = bincode::serialize(packet)?;
    encode_framed(PacketType::Control, &payload)
}

/// Encode a framed packet with header: [type: u8][length: u16][payload].
fn encode_framed(packet_type: PacketType, payload: &[u8]) -> Result<Vec<u8>, CodecError> {
    if payload.len() > MAX_PACKET_SIZE - 3 {
        return Err(CodecError::PacketTooLarge {
            size: payload.len() + 3,
            max: MAX_PACKET_SIZE,
        });
    }

    let len = payload.len() as u16;
    let mut buf = Vec::with_capacity(3 + payload.len());
    buf.push(packet_type as u8);
    buf.extend_from_slice(&len.to_le_bytes());
    buf.extend_from_slice(payload);
    Ok(buf)
}

/// Decoded packet variants.
#[derive(Debug, Clone)]
pub enum DecodedPacket {
    Video(VideoPacket),
    Input(InputPacket),
    Control(ControlPacket),
}

/// Decode a framed packet from raw bytes.
pub fn decode_packet(data: &[u8]) -> Result<DecodedPacket, CodecError> {
    if data.len() < 3 {
        return Err(CodecError::PacketTooSmall);
    }

    let packet_type = PacketType::try_from(data[0])
        .map_err(CodecError::UnknownPacketType)?;
    let len = u16::from_le_bytes([data[1], data[2]]) as usize;

    if data.len() < 3 + len {
        return Err(CodecError::IncompletePacket {
            expected: 3 + len,
            actual: data.len(),
        });
    }

    let payload = &data[3..3 + len];

    match packet_type {
        PacketType::Video => {
            let packet: VideoPacket = bincode::deserialize(payload)?;
            Ok(DecodedPacket::Video(packet))
        }
        PacketType::Input => {
            let packet: InputPacket = bincode::deserialize(payload)?;
            Ok(DecodedPacket::Input(packet))
        }
        PacketType::Control => {
            let packet: ControlPacket = bincode::deserialize(payload)?;
            Ok(DecodedPacket::Control(packet))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_video_packet_roundtrip() {
        let packet = VideoPacket {
            sequence: 42,
            timestamp: 123456,
            is_keyframe: true,
            fragment_index: 2,
            fragment_count: 5,
            data: vec![0x00, 0x00, 0x01, 0x67],
        };

        let encoded = encode_video(&packet).unwrap();
        let decoded = decode_packet(&encoded).unwrap();

        match decoded {
            DecodedPacket::Video(p) => {
                assert_eq!(p.sequence, 42);
                assert_eq!(p.timestamp, 123456);
                assert!(p.is_keyframe);
                assert_eq!(p.fragment_index, 2);
                assert_eq!(p.fragment_count, 5);
                assert_eq!(p.data, vec![0x00, 0x00, 0x01, 0x67]);
            }
            _ => panic!("expected video packet"),
        }
    }

    #[test]
    fn test_input_packet_roundtrip() {
        let packet = InputPacket {
            timestamp: 999,
            event: InputEvent::StylusDown {
                x: 0.5,
                y: 0.75,
                pressure: 0.8,
                tilt_x: 15.0,
                tilt_y: -10.0,
            },
        };

        let encoded = encode_input(&packet).unwrap();
        let decoded = decode_packet(&encoded).unwrap();

        match decoded {
            DecodedPacket::Input(p) => {
                assert_eq!(p.timestamp, 999);
                match p.event {
                    InputEvent::StylusDown { x, y, pressure, tilt_x, tilt_y } => {
                        assert!((x - 0.5).abs() < 0.001);
                        assert!((y - 0.75).abs() < 0.001);
                        assert!((pressure - 0.8).abs() < 0.001);
                        assert!((tilt_x - 15.0).abs() < 0.001);
                        assert!((tilt_y - (-10.0)).abs() < 0.001);
                    }
                    _ => panic!("expected stylus down"),
                }
            }
            _ => panic!("expected input packet"),
        }
    }

    #[test]
    fn test_control_packet_roundtrip() {
        let packet = ControlPacket::ConnectAck {
            width: 1920,
            height: 1080,
        };

        let encoded = encode_control(&packet).unwrap();
        let decoded = decode_packet(&encoded).unwrap();

        match decoded {
            DecodedPacket::Control(ControlPacket::ConnectAck { width, height }) => {
                assert_eq!(width, 1920);
                assert_eq!(height, 1080);
            }
            _ => panic!("expected connect ack"),
        }
    }

    #[test]
    fn test_video_fragment_into_matches_encode_video() {
        let data: Vec<u8> = (0u8..=255).cycle().take(1400).collect();
        let packet = VideoPacket {
            sequence: 7,
            timestamp: 9_999,
            is_keyframe: false,
            fragment_index: 3,
            fragment_count: 12,
            data: data.clone(),
        };

        let expected = encode_video(&packet).unwrap();

        let fragment = VideoFragment {
            sequence: 7,
            timestamp: 9_999,
            is_keyframe: false,
            fragment_index: 3,
            fragment_count: 12,
            data: &data,
        };
        // Pre-fill so we prove the buffer is cleared, not appended to.
        let mut out = vec![0xAA; 16];
        encode_video_fragment_into(&mut out, &fragment).unwrap();
        assert_eq!(out, expected);

        // And the hand-rolled encoding must decode like the bincode encoding.
        match decode_packet(&out).unwrap() {
            DecodedPacket::Video(p) => {
                assert_eq!(p.sequence, 7);
                assert_eq!(p.timestamp, 9_999);
                assert!(!p.is_keyframe);
                assert_eq!(p.fragment_index, 3);
                assert_eq!(p.fragment_count, 12);
                assert_eq!(p.data, data);
            }
            _ => panic!("expected video packet"),
        }
    }

    #[test]
    fn test_video_fragment_into_handles_empty_data() {
        let packet = VideoPacket {
            sequence: 1,
            timestamp: 2,
            is_keyframe: true,
            fragment_index: 0,
            fragment_count: 0,
            data: Vec::new(),
        };

        let fragment = VideoFragment {
            sequence: 1,
            timestamp: 2,
            is_keyframe: true,
            fragment_index: 0,
            fragment_count: 0,
            data: &[],
        };

        let mut out = Vec::new();
        encode_video_fragment_into(&mut out, &fragment).unwrap();
        assert_eq!(out, encode_video(&packet).unwrap());
    }

    #[test]
    fn test_set_refresh_rate_roundtrip() {
        let packet = ControlPacket::SetRefreshRate { hz: 120 };

        let encoded = encode_control(&packet).unwrap();
        match decode_packet(&encoded).unwrap() {
            DecodedPacket::Control(ControlPacket::SetRefreshRate { hz }) => assert_eq!(hz, 120),
            other => panic!("expected SetRefreshRate, got {other:?}"),
        }
    }

    #[test]
    fn test_ping_roundtrip() {
        let encoded = encode_control(&ControlPacket::Ping).unwrap();
        match decode_packet(&encoded).unwrap() {
            DecodedPacket::Control(ControlPacket::Ping) => {}
            other => panic!("expected Ping, got {other:?}"),
        }
    }

    #[test]
    fn test_control_variant_indices_are_stable() {
        // Pinned so that a variant appended to the enum cannot silently
        // renumber existing packets on the wire.
        let cases: [(ControlPacket, u32); 9] = [
            (ControlPacket::Connect { version: 2 }, 0),
            (ControlPacket::ConnectAck { width: 1, height: 1 }, 1),
            (ControlPacket::ConnectNack { reason: String::new() }, 2),
            (ControlPacket::Heartbeat, 3),
            (ControlPacket::RequestKeyframe, 4),
            (ControlPacket::Disconnect, 5),
            (ControlPacket::SetUdpPort { port: 1 }, 6),
            (ControlPacket::SetRefreshRate { hz: 60 }, 7),
            (ControlPacket::Ping, 8),
        ];

        for (packet, expected) in cases {
            let payload = bincode::serialize(&packet).unwrap();
            let index = u32::from_le_bytes(payload[0..4].try_into().unwrap());
            assert_eq!(index, expected, "variant index changed for {packet:?}");
        }
    }
}
