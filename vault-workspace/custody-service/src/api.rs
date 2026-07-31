//! gRPC/REST surface consumed by the NestJS Loan Service. Currently a small
//! REST API over an in-memory store — swap `Store` for a `sqlx` Postgres
//! pool once persistence is needed (see Cargo.toml).

use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::{get, post},
    Json, Router,
};
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::state_machine::{SigningRequest, SigningRequestState};
use vault_core::policy::SigningReason;

#[derive(Default)]
pub struct Store {
    requests: HashMap<u64, SigningRequest>,
    next_id: u64,
}

pub type SharedStore = Arc<Mutex<Store>>;

#[derive(Deserialize)]
pub struct CreateSigningRequest {
    pub loan_id: String,
    pub reason: SigningReason,
}

pub fn router(store: SharedStore) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/signing-requests", post(create_signing_request))
        .route("/signing-requests/{id}", get(get_signing_request))
        .route("/signing-requests/{id}/advance", post(advance_signing_request))
        .with_state(store)
}

async fn health() -> &'static str {
    "ok"
}

async fn create_signing_request(
    State(store): State<SharedStore>,
    Json(body): Json<CreateSigningRequest>,
) -> Json<SigningRequest> {
    let mut store = store.lock().unwrap();
    store.next_id += 1;
    let id = store.next_id;
    let request = SigningRequest {
        id,
        loan_id: body.loan_id,
        reason: body.reason,
        state: SigningRequestState::Created,
    };
    store.requests.insert(id, request.clone());
    Json(request)
}

async fn get_signing_request(
    State(store): State<SharedStore>,
    Path(id): Path<u64>,
) -> Result<Json<SigningRequest>, StatusCode> {
    let store = store.lock().unwrap();
    store.requests.get(&id).cloned().map(Json).ok_or(StatusCode::NOT_FOUND)
}

/// Idempotent: calling this after a request has already reached `Confirmed`
/// just returns the confirmed request again, per `state_machine`'s contract.
async fn advance_signing_request(
    State(store): State<SharedStore>,
    Path(id): Path<u64>,
) -> Result<Json<SigningRequest>, StatusCode> {
    let mut store = store.lock().unwrap();
    let request = store.requests.get_mut(&id).ok_or(StatusCode::NOT_FOUND)?;
    request.state = request.state.advance();
    Ok(Json(request.clone()))
}
