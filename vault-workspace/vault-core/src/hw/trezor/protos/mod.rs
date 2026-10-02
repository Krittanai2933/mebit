//! Trezor's protobuf messages: Trezor's own Rust bindings, copied unmodified.
//! See `README.md` in this directory for where they come from and why.

/// The six generated files reference each other as `super::messages_common`
/// and `super::options`, so they stay siblings under one parent, as in
/// `trezor-client`.
#[allow(unreachable_pub)]
pub(crate) mod generated {
    pub mod messages;
    pub mod messages_bitcoin;
    pub mod messages_common;
    pub mod messages_management;
    pub mod messages_thp;
    pub mod options;
}

pub(crate) use generated::{
    messages::MessageType, messages_bitcoin as bitcoin, messages_common as common,
    messages_management as management, messages_thp as thp,
};
