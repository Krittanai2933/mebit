//! Platform-side service: exposes gRPC/REST to the existing NestJS Loan
//! Service, owns the signing-request state machine, and holds the
//! loan-to-vault-descriptor mapping. See `docs/00-capstone-brief.md` §3.2.

mod api;
mod state_machine;

use api::Store;
use std::sync::{Arc, Mutex};

#[tokio::main]
async fn main() {
    let store: api::SharedStore = Arc::new(Mutex::new(Store::default()));
    let app = api::router(store);

    let listener = tokio::net::TcpListener::bind("127.0.0.1:8080")
        .await
        .expect("failed to bind 127.0.0.1:8080");
    println!("custody-service listening on http://127.0.0.1:8080");
    axum::serve(listener, app).await.expect("server error");
}
