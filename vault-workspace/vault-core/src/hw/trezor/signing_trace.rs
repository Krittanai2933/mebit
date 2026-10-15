use std::fmt;

use zeroize::Zeroizing;

/// A plaintext signing message accepted by THP, or decrypted from it.
pub struct SigningTraceFrame {
    outbound: bool,
    message_type: u16,
    payload: Zeroizing<Vec<u8>>,
}

impl SigningTraceFrame {
    pub fn outbound(&self) -> bool {
        self.outbound
    }
    pub fn message_type(&self) -> u16 {
        self.message_type
    }
    /// Private transaction evidence. Never log this buffer.
    pub fn payload(&self) -> &[u8] {
        &self.payload
    }
}

impl fmt::Debug for SigningTraceFrame {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("SigningTraceFrame([REDACTED])")
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SigningTraceEnd {
    Completed,
    Rejected,
    ProtocolFailure,
    Interrupted,
}

/// Bounded private evidence for exactly one signing attempt.
pub struct SigningTrace {
    frames: Vec<SigningTraceFrame>,
    bytes: usize,
    incomplete: bool,
    end: SigningTraceEnd,
}

impl SigningTrace {
    pub fn frames(&self) -> &[SigningTraceFrame] {
        &self.frames
    }
    pub fn incomplete(&self) -> bool {
        self.incomplete
    }
    pub fn end(&self) -> SigningTraceEnd {
        self.end
    }
}

impl fmt::Debug for SigningTrace {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("SigningTrace")
            .field("frame_count", &self.frames.len())
            .field("incomplete", &self.incomplete)
            .field("end", &self.end)
            .field("payloads", &"[REDACTED]")
            .finish()
    }
}

#[derive(Default)]
pub(super) enum Recorder {
    #[default]
    Empty,
    Active(SigningTrace),
    Frozen(SigningTrace),
}

impl Recorder {
    pub fn start(&mut self) -> bool {
        if !matches!(self, Self::Empty) {
            return false;
        }
        *self = Self::Active(SigningTrace {
            frames: Vec::new(),
            bytes: 0,
            incomplete: false,
            end: SigningTraceEnd::Interrupted,
        });
        true
    }

    pub fn record(&mut self, outbound: bool, session: u8, message_type: u16, payload: &[u8]) {
        if session != 1 || !matches!((outbound, message_type), (true, 15 | 22) | (false, 21)) {
            return;
        }
        let Self::Active(trace) = self else {
            return;
        };
        if trace.incomplete {
            return;
        }
        if trace.frames.len() >= 4096
            || payload.len() > 1024 * 1024
            || trace.bytes + payload.len() > 4 * 1024 * 1024
        {
            trace.incomplete = true;
            return;
        }
        trace.bytes += payload.len();
        trace.frames.push(SigningTraceFrame {
            outbound,
            message_type,
            payload: Zeroizing::new(payload.to_vec()),
        });
    }

    pub fn freeze(&mut self, end: SigningTraceEnd) {
        if matches!(self, Self::Active(_)) {
            let Self::Active(mut trace) = std::mem::take(self) else {
                unreachable!()
            };
            trace.end = end;
            *self = Self::Frozen(trace);
        }
    }

    pub fn take(&mut self) -> Option<SigningTrace> {
        self.freeze(SigningTraceEnd::Interrupted);
        match std::mem::take(self) {
            Self::Frozen(trace) => Some(trace),
            Self::Empty => None,
            Self::Active(_) => unreachable!(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn trace_allowlist_caps_and_redaction() {
        let mut recorder = Recorder::default();
        recorder.record(true, 1, 15, b"private");
        assert!(recorder.take().is_none());
        assert!(recorder.start());
        for (outbound, session, kind) in [
            (true, 0, 15),
            (false, 1, 15),
            (true, 1, 21),
            (true, 1, 1001),
        ] {
            recorder.record(outbound, session, kind, b"secret");
        }
        recorder.record(true, 1, 15, b"private");
        recorder.freeze(SigningTraceEnd::Rejected);
        assert!(!recorder.start());
        recorder.record(false, 1, 21, b"later");
        let trace = recorder.take().unwrap();
        assert_eq!(trace.frames().len(), 1);
        assert_eq!(trace.frames()[0].payload(), b"private");
        assert_eq!(trace.end(), SigningTraceEnd::Rejected);
        assert!(!format!("{trace:?} {:?}", trace.frames()).contains("private"));
        assert!(recorder.start());
        for _ in 0..4097 {
            recorder.record(true, 1, 22, b"x");
        }
        let trace = recorder.take().unwrap();
        assert_eq!(trace.frames().len(), 4096);
        assert!(trace.incomplete());
        assert_eq!(trace.end(), SigningTraceEnd::Interrupted);
        for size in [1024 * 1024, 1024 * 1024 + 1] {
            assert!(recorder.start());
            let payload = vec![0x55; size];
            for _ in 0..5 {
                recorder.record(true, 1, 22, &payload);
            }
            let trace = recorder.take().unwrap();
            assert_eq!(
                trace.frames().len(),
                if size == 1024 * 1024 { 4 } else { 0 }
            );
            assert!(trace.incomplete());
        }
    }
}
