//! Integration tests for the state machine in `app::run_update_flow_with_config`.
//!
//! Covers the paths that `src/app.rs` unit tests cannot reach (because
//! they require real HTTP + filesystem + an async mpsc driven by the UI):
//!
//! 1. **Happy path** — API returns release + manifest + file, flow completes
//!    with `FlowOutcome::Updated(launcher_rel)`.
//! 2. **Retry → Offline** — API fails 4× (initial + 3 retries), a local
//!    manifest exists on disk, the UI sends `UserAction::Offline`, flow
//!    returns `FlowOutcome::UserRequestedOffline(old_launcher_rel)`.
//! 3. **Retry → channel closed** — API fails, `user_tx` is dropped before
//!    sending any action, flow returns `Err(UpdaterError::NotFound)`.
//! 4. **Retry-succeeds-mid-loop** — first call fails, second succeeds; the
//!    inner retry-success arm of `handle_api_failure` completes without
//!    parking on user action.
//! 5. **Manifest parse error** — 200 response with malformed manifest
//!    JSON surfaces as `Err(UpdaterError::Json)` (happy-path error
//!    bubble tested end-to-end).
//! 6. **404 → NotFound** — 404 on `/releases/latest` maps to
//!    `UpdaterError::NotFound`, distinct from the 500 → `Network` path.
//! 7. **Retry → Fatal** — API fails 4×, no local manifest, flow returns
//!    `Err(UpdaterError::Network)` and leaves `UiState::FatalError` set.
//!
//! (Pre-T2.11f6 the suite also had `user_retry_from_offline_screen`
//! and `user_retry_from_download_failed` scenarios exercising an outer
//! loop driven by `UserAction::Retry`. That enum variant was removed —
//! the state machine auto-retries on the cooldown ladder now, so no
//! user "Retry" click exists to test.)
//!
//! Time is driven by a per-test `RunUpdateFlowConfig` with
//! `retry_interval_secs: 0` and `checking_min_ms: 0` — the flow's sleeps
//! are still exercised (`tokio::time::sleep(ZERO).await` yields to the
//! scheduler), but they resolve in a handful of runtime ticks instead of
//! waiting real 30 s. Paused-clock (`#[tokio::test(start_paused = true)]`
//! + `tokio::time::advance`) was tried and reverted: it raced wiremock's
//! real HTTP I/O unpredictably.
//!
//! All tests use `flavor = "current_thread"` so scheduling is
//! deterministic — the alternative `multi_thread` default could
//! interleave the bg task and the polling loop differently across runs.

use sha2::{Digest, Sha256 as Sha256Hasher};
use singularitymc_auto_update::{
    Channel, OsTarget, UpdaterError,
    app::{
        FlowOutcome, REPO_NAME, REPO_OWNER, RunUpdateFlowConfig, UserAction,
        run_update_flow_with_config,
    },
    manifest::{self, Manifest},
    ui::states::UiState,
};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;
use wiremock::matchers::{method, path};
use wiremock::{Mock, MockServer, ResponseTemplate};

/// Test-tuned config: `retry_interval_secs=0` skips every auto-retry
/// sleep so scenarios resolve in a handful of runtime ticks rather than
/// real 30 s waits. `checking_min_ms=0` skips the "Checking..." UX
/// floor. Everything else matches the production `Default`.
///
/// The 3-failures-before-offline-prompt threshold used to be a config
/// knob (`max_api_retries`); T2.11f7 hardcoded it as the crate-private
/// `API_FAILURES_BEFORE_OFFLINE_PROMPT` const, so tests observe the
/// same threshold as production without a builder call.
fn test_config(github_base_url: String) -> RunUpdateFlowConfig {
    RunUpdateFlowConfig::new(github_base_url)
        .with_retry_interval_secs(0)
        .with_checking_min_ms(0)
}

// ---------- helpers ----------

fn sha256_hex(content: &[u8]) -> String {
    hex::encode(Sha256Hasher::digest(content))
}

/// Build a minimal but schema-valid GitHub Release JSON referencing exactly
/// one `manifest-windows.json` asset at `manifest_url`.
fn release_json(tag: &str, manifest_url: &str) -> String {
    format!(
        r#"{{
            "tag_name":"{tag}","name":"Release {tag}","body":null,
            "prerelease":false,"html_url":"https://github.com/test/test",
            "assets":[{{"name":"manifest-windows.json","browser_download_url":"{manifest_url}","size":100}}]
        }}"#
    )
}

/// Build a valid per-OS manifest JSON with the given `files` list. The first
/// entry's `path` is also used as `launcherExecutable` so tests can assert the
/// returned launcher path. Fields match `manifest::Manifest`'s Serde schema
/// (pinned by `manifest.rs` tests — don't drift).
fn manifest_json(version: &str, files: &[(&str, &str, u64, &str)]) -> String {
    let launcher_rel = files
        .first()
        .map_or("launcher/app.jar", |(p, _, _, _)| *p);
    let files_json: Vec<String> = files
        .iter()
        .map(|(p, url, size, sha)| {
            format!(
                r#"{{"path":"{p}","url":"{url}","size":{size},"sha256":"{sha}"}}"#
            )
        })
        .collect();
    format!(
        r#"{{
            "version":"{version}","os":"windows","releasedAt":"2026-04-15T10:00:00Z",
            "minAutoUpdateVersion":"0.1.0","launcherExecutable":"{launcher_rel}",
            "changelog":"- test",
            "files":[{}]
        }}"#,
        files_json.join(",")
    )
}

/// Drop a pre-existing `local-manifest.json` in `install_dir` so the
/// OfflineAvailable path becomes reachable. `launcher_rel` is echoed back
/// by the flow as `FlowOutcome::UserRequestedOffline(...)`.
fn seed_local_manifest(install_dir: &std::path::Path, launcher_rel: &str) {
    let json = format!(
        r#"{{
            "version":"0.0.5","os":"windows","releasedAt":"2026-01-01T00:00:00Z",
            "minAutoUpdateVersion":"0.1.0","launcherExecutable":"{launcher_rel}",
            "changelog":"- initial",
            "files":[]
        }}"#
    );
    let parsed: Manifest =
        Manifest::parse(&json).expect("seed manifest must be valid for the test");
    manifest::save_local(install_dir, &parsed).expect("save_local in tempdir");
}

/// GitHub stable-release endpoint path for the pinned repo. Built from the
/// `app::REPO_OWNER` / `REPO_NAME` constants so any rename flows through
/// automatically — no drift risk between the wiremock matcher and the
/// production URL.
fn latest_release_path() -> String {
    format!("/repos/{REPO_OWNER}/{REPO_NAME}/releases/latest")
}

// ---------- scenarios ----------

#[tokio::test(flavor = "current_thread")]
async fn happy_path_transitions_to_updated() {
    let install_dir = tempfile::tempdir().unwrap();
    let server = MockServer::start().await;

    let file_bytes: &[u8] = b"fake launcher jar content";
    let file_sha = sha256_hex(file_bytes);
    let file_url = format!("{}/download/launcher-app.jar", server.uri());
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    Mock::given(method("GET"))
        .and(path("/download/launcher-app.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(file_bytes.to_vec()))
        .mount(&server)
        .await;

    let m_json = manifest_json(
        "0.1.0",
        &[(
            "launcher/app.jar",
            &file_url,
            file_bytes.len() as u64,
            &file_sha,
        )],
    );
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(ResponseTemplate::new(200).set_body_string(m_json))
        .mount(&server)
        .await;

    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);

    let result = run_update_flow_with_config(
        install_dir.path().to_path_buf(),
        Channel::Stable,
        OsTarget::Windows,
        Arc::clone(&state),
        &mut user_rx,
        test_config(server.uri()),
    )
    .await;

    match result {
        Ok(FlowOutcome::Updated(launcher_rel)) => {
            assert_eq!(launcher_rel.as_str(), "launcher/app.jar");
        }
        other => panic!("expected Updated(launcher/app.jar), got {other:?}"),
    }

    // Positive terminal-pre-main state: `process_release` leaves state
    // on `Installing` before returning Ok (it's the last `set_state`
    // call in the install phase). Anything else means either the flow
    // short-circuited (e.g. diff empty, but scenario seeds one file) or
    // a missing transition. main.rs flips it to `Starting` after this.
    let final_state = state.lock().unwrap().clone();
    assert!(
        matches!(final_state, UiState::Installing),
        "happy path must terminate with Installing (process_release's final \
         set_state before Ok return), got {final_state:?}"
    );
}

#[tokio::test(flavor = "current_thread")]
async fn exhausted_retries_with_local_manifest_offers_offline() {
    let install_dir = tempfile::tempdir().unwrap();
    seed_local_manifest(install_dir.path(), "launcher/old-app.jar");

    let server = MockServer::start().await;
    // Every call to /releases/latest fails — drives the retry loop to
    // exhaustion (initial call + 3 retries = 4 total 500s).
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);

    let state_bg = Arc::clone(&state);
    let install_path = install_dir.path().to_path_buf();
    let cfg = test_config(server.uri());

    let flow_handle = tokio::spawn(async move {
        run_update_flow_with_config(
            install_path,
            Channel::Stable,
            OsTarget::Windows,
            state_bg,
            &mut user_rx,
            cfg,
        )
        .await
    });

    // Poll until the bg task parks in OfflineAvailable AND we've seen
    // NoInternet pass through the state first — proves the UX journey
    // (user saw "Sprawdź połączenie" before being offered offline mode),
    // not just the terminal state. Without the `seen_no_internet` pin a
    // future refactor that skips straight to OfflineAvailable would
    // pass the happy-path-lite check silently.
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    let mut seen_no_internet = false;
    loop {
        {
            let cur = state.lock().unwrap().clone();
            if matches!(cur, UiState::NoInternet { .. }) {
                seen_no_internet = true;
            }
            if matches!(cur, UiState::OfflineAvailable { .. }) {
                break;
            }
        }
        if std::time::Instant::now() > deadline {
            panic!(
                "never reached OfflineAvailable; last state was {:?}",
                *state.lock().unwrap()
            );
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert!(
        seen_no_internet,
        "state must pass through NoInternet before OfflineAvailable — retry UX contract"
    );

    // Now drive the UI side: click "Tryb offline".
    user_tx.send(UserAction::Offline).await.unwrap();

    let result = flow_handle.await.unwrap();
    match result {
        Ok(FlowOutcome::UserRequestedOffline(launcher_rel)) => {
            assert_eq!(launcher_rel.as_str(), "launcher/old-app.jar");
        }
        other => panic!(
            "expected UserRequestedOffline(launcher/old-app.jar), got {other:?}"
        ),
    }
}

#[tokio::test(flavor = "current_thread")]
async fn exhausted_retries_without_local_manifest_is_fatal() {
    let install_dir = tempfile::tempdir().unwrap();
    // NO seed_local_manifest — first-run scenario.

    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);

    let state_bg = Arc::clone(&state);
    let install_path = install_dir.path().to_path_buf();
    let cfg = test_config(server.uri());

    let result = tokio::time::timeout(
        Duration::from_secs(10),
        run_update_flow_with_config(
            install_path,
            Channel::Stable,
            OsTarget::Windows,
            state_bg,
            &mut user_rx,
            cfg,
        ),
    )
    .await
    .expect("flow must finish within 10 s with zero retry interval");

    assert!(
        matches!(result, Err(UpdaterError::Network(_))),
        "expected Err(Network), got {result:?}"
    );

    // handle_api_failure sets FatalError on the no-local-manifest branch.
    let final_state = state.lock().unwrap().clone();
    assert!(
        matches!(final_state, UiState::FatalError { .. }),
        "expected FatalError terminal state, got {final_state:?}"
    );
}

/// Helper: poll `state` until it reaches `target_state` matcher,
/// panicking if `deadline` elapses. Returns early if the matcher hits.
async fn wait_for_state_matching<F: Fn(&UiState) -> bool>(
    state: &Arc<Mutex<UiState>>,
    matches_fn: F,
    label: &str,
) {
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    loop {
        if matches_fn(&state.lock().unwrap()) {
            return;
        }
        if std::time::Instant::now() > deadline {
            panic!(
                "never reached {label}; last state was {:?}",
                *state.lock().unwrap()
            );
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
}

#[tokio::test(flavor = "current_thread")]
async fn channel_closed_before_user_decision_is_notfound() {
    // Covers the `None` arm in handle_api_failure: if the UI is torn
    // down while the worker is parked in OfflineAvailable, the worker
    // must surface NotFound (not silently park forever).
    let install_dir = tempfile::tempdir().unwrap();
    seed_local_manifest(install_dir.path(), "launcher/unused.jar");

    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);
    let state_bg = Arc::clone(&state);
    let install_path = install_dir.path().to_path_buf();
    let cfg = test_config(server.uri());

    let flow_handle = tokio::spawn(async move {
        run_update_flow_with_config(
            install_path,
            Channel::Stable,
            OsTarget::Windows,
            state_bg,
            &mut user_rx,
            cfg,
        )
        .await
    });

    wait_for_state_matching(
        &state,
        |s| matches!(s, UiState::OfflineAvailable { .. }),
        "OfflineAvailable",
    )
    .await;

    // Drop sender without sending — simulates the eframe loop tearing
    // down and releasing the UI's cloned user_tx handles.
    drop(user_tx);

    let result = flow_handle.await.unwrap();
    assert!(
        matches!(result, Err(UpdaterError::NotFound(_))),
        "expected NotFound on channel close, got {result:?}"
    );
}

#[tokio::test(flavor = "current_thread")]
async fn retry_succeeds_on_second_attempt_in_inner_loop() {
    // Covers the `Ok(release)` arm INSIDE handle_api_failure's retry
    // loop: first API call fails, second (after the retry_interval sleep)
    // succeeds. Previously untested — mutation deleting that arm would
    // have passed the other scenarios.
    let install_dir = tempfile::tempdir().unwrap();

    let server = MockServer::start().await;
    let file_bytes: &[u8] = b"mid-loop recovery payload";
    let file_sha = sha256_hex(file_bytes);
    let file_url = format!("{}/download/launcher-app.jar", server.uri());
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    // One failure then success — exercises inner-loop recovery.
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(500))
        .up_to_n_times(1)
        .mount(&server)
        .await;
    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;

    let m_json = manifest_json(
        "0.1.0",
        &[(
            "launcher/app.jar",
            &file_url,
            file_bytes.len() as u64,
            &file_sha,
        )],
    );
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(ResponseTemplate::new(200).set_body_string(m_json))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/download/launcher-app.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(file_bytes.to_vec()))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);
    let cfg = test_config(server.uri());

    let result = tokio::time::timeout(
        Duration::from_secs(10),
        run_update_flow_with_config(
            install_dir.path().to_path_buf(),
            Channel::Stable,
            OsTarget::Windows,
            Arc::clone(&state),
            &mut user_rx,
            cfg,
        ),
    )
    .await
    .expect("flow must finish within 10 s");

    match result {
        Ok(FlowOutcome::Updated(launcher_rel)) => {
            assert_eq!(launcher_rel.as_str(), "launcher/app.jar");
        }
        other => panic!("expected Updated after mid-loop retry, got {other:?}"),
    }
}

#[tokio::test(flavor = "current_thread")]
async fn manifest_parse_error_surfaces_as_download_failed_then_fatal() {
    // Malformed manifest body (200 OK with bad JSON) → UpdaterError::Json.
    // That now flows through park_on_download_failure → DownloadFailed;
    // with no local manifest + user "clicks" Offline (via channel), the
    // flow surfaces FatalError + NotFound per the park logic.
    let install_dir = tempfile::tempdir().unwrap();

    let server = MockServer::start().await;
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;
    // Broken manifest — missing required fields. `Manifest::parse` fails.
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_string(r#"{"version":"0.1.0"}"#),
        )
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);
    let state_bg = Arc::clone(&state);
    let install_path = install_dir.path().to_path_buf();
    let cfg = test_config(server.uri());

    let flow_handle = tokio::spawn(async move {
        run_update_flow_with_config(
            install_path,
            Channel::Stable,
            OsTarget::Windows,
            state_bg,
            &mut user_rx,
            cfg,
        )
        .await
    });

    wait_for_state_matching(
        &state,
        |s| matches!(s, UiState::DownloadFailed { .. }),
        "DownloadFailed",
    )
    .await;

    user_tx.send(UserAction::Offline).await.unwrap();

    let result = flow_handle.await.unwrap();
    // Offline fallback with no local manifest → NotFound; terminal
    // state must be FatalError (park_on_download_failure sets it).
    assert!(
        matches!(result, Err(UpdaterError::NotFound(_))),
        "expected NotFound after Offline without local manifest, got {result:?}"
    );
    let final_state = state.lock().unwrap().clone();
    assert!(
        matches!(final_state, UiState::FatalError { .. }),
        "expected FatalError terminal state, got {final_state:?}"
    );
}

#[tokio::test(flavor = "current_thread")]
async fn latest_404_surfaces_as_notfound_not_network() {
    // Status-code mapping coverage: `/releases/latest` 404 (repo exists
    // but has no stable release yet) is `NotFound`, whereas 500 is
    // `Network`. Without this mutation flipping the mapping passes.
    let install_dir = tempfile::tempdir().unwrap();

    let server = MockServer::start().await;
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(404))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);
    let cfg = test_config(server.uri());

    let result = tokio::time::timeout(
        Duration::from_secs(10),
        run_update_flow_with_config(
            install_dir.path().to_path_buf(),
            Channel::Stable,
            OsTarget::Windows,
            Arc::clone(&state),
            &mut user_rx,
            cfg,
        ),
    )
    .await
    .expect("flow must finish within 10 s");

    // 404 → NotFound bubbles straight through (no retry loop — the
    // handle_api_failure retries don't distinguish status codes, so
    // after exhaustion no-local-manifest → FatalError with the
    // NotFound message).
    assert!(
        matches!(result, Err(UpdaterError::NotFound(_))),
        "expected NotFound on /releases/latest 404, got {result:?}"
    );
}

// (user_retry_from_download_failed_loops_back_to_success removed in
// T2.11f6 — UserAction::Retry is gone. Auto-retry integration tests
// arrive in T2.11f7.)

#[tokio::test(flavor = "current_thread")]
async fn download_failed_without_local_hides_offline_flag() {
    // Fresh install + manifest 500 → DownloadFailed MUST carry
    // has_offline=false so the UI hides the Offline button (no usable
    // local install to fall back on). Pins the new `has_offline` flag
    // contract end-to-end; a mutation flipping false→true here would
    // re-introduce the Offline→FatalError UX bug this field was added
    // to prevent.
    let install_dir = tempfile::tempdir().unwrap();
    // NO seed_local_manifest — first-install case.

    let server = MockServer::start().await;
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(ResponseTemplate::new(500))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);
    let state_bg = Arc::clone(&state);
    let install_path = install_dir.path().to_path_buf();
    let cfg = test_config(server.uri());

    let flow_handle = tokio::spawn(async move {
        run_update_flow_with_config(
            install_path,
            Channel::Stable,
            OsTarget::Windows,
            state_bg,
            &mut user_rx,
            cfg,
        )
        .await
    });

    wait_for_state_matching(
        &state,
        |s| matches!(s, UiState::DownloadFailed { has_offline: false, .. }),
        "DownloadFailed{has_offline:false}",
    )
    .await;

    // Close the channel to let the flow terminate cleanly.
    drop(user_tx);
    let result = flow_handle.await.unwrap();
    assert!(
        matches!(result, Err(UpdaterError::NotFound(_))),
        "expected NotFound on channel close while parked, got {result:?}"
    );
}

#[tokio::test(flavor = "current_thread")]
async fn offline_available_auto_recovers_when_api_returns(
) {
    // f7 contract: after 3 failed API attempts with a local install on
    // disk, state goes to OfflineAvailable AND the retry ladder keeps
    // ticking in the background. If the API comes back during any
    // subsequent cooldown, the flow resumes to Downloading → Updated
    // *without* any user click. A mutation that breaks the `select!`'s
    // sleep arm (e.g. park on `user_rx.recv()` only) would make the
    // fallthrough 200 mock never hit — this test fails.
    let install_dir = tempfile::tempdir().unwrap();
    seed_local_manifest(install_dir.path(), "launcher/old-app.jar");

    let server = MockServer::start().await;
    let file_bytes: &[u8] = b"bg-recovery payload";
    let file_sha = sha256_hex(file_bytes);
    let file_url = format!("{}/download/launcher-app.jar", server.uri());
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    // First 3 API calls fail → threshold reached → OfflineAvailable.
    // 4th (auto-retry tick) returns 200 → flow continues.
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(500))
        .up_to_n_times(3)
        .mount(&server)
        .await;
    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;
    let m_json = manifest_json(
        "0.1.0",
        &[(
            "launcher/app.jar",
            &file_url,
            file_bytes.len() as u64,
            &file_sha,
        )],
    );
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(ResponseTemplate::new(200).set_body_string(m_json))
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/download/launcher-app.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(file_bytes.to_vec()))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);
    let cfg = test_config(server.uri());

    // No user click — the flow must auto-recover on its own tick.
    let result = tokio::time::timeout(
        Duration::from_secs(15),
        run_update_flow_with_config(
            install_dir.path().to_path_buf(),
            Channel::Stable,
            OsTarget::Windows,
            Arc::clone(&state),
            &mut user_rx,
            cfg,
        ),
    )
    .await
    .expect("auto-recovery must complete within 15 s of wall time");

    match result {
        Ok(FlowOutcome::Updated(launcher_rel)) => {
            assert_eq!(launcher_rel.as_str(), "launcher/app.jar");
        }
        other => panic!(
            "expected Updated after background auto-retry, got {other:?}"
        ),
    }
}

#[tokio::test(flavor = "current_thread")]
async fn download_failed_auto_recovers_when_retry_succeeds() {
    // f7 contract for the download side: process_release failure sets
    // DownloadFailed, state machine keeps ticking in the background,
    // when the next tick's re-fetch succeeds the flow resumes to
    // Updated without a user click. Seed local install so has_offline
    // is true (off-path matching visible in the state), but the flow
    // should reach Updated before user has any reason to click
    // "Tryb offline".
    let install_dir = tempfile::tempdir().unwrap();
    seed_local_manifest(install_dir.path(), "launcher/old-app.jar");

    let server = MockServer::start().await;
    let file_bytes: &[u8] = b"download-recovery payload";
    let file_sha = sha256_hex(file_bytes);
    let file_url = format!("{}/download/launcher-app.jar", server.uri());
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    // Release + manifest always return 200 (API is fine).
    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;
    let m_json = manifest_json(
        "0.1.0",
        &[(
            "launcher/app.jar",
            &file_url,
            file_bytes.len() as u64,
            &file_sha,
        )],
    );
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(ResponseTemplate::new(200).set_body_string(m_json))
        .mount(&server)
        .await;
    // File download fails exactly DEFAULT_MAX_ATTEMPTS times inside the
    // Downloader (which itself retries 3× with its own backoff), then
    // the 4th hit — the second outer-flow iteration kicked off by
    // park_on_download_failure's auto-tick — lands on the 200 mock.
    Mock::given(method("GET"))
        .and(path("/download/launcher-app.jar"))
        .respond_with(ResponseTemplate::new(500))
        .up_to_n_times(3)
        .mount(&server)
        .await;
    Mock::given(method("GET"))
        .and(path("/download/launcher-app.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(file_bytes.to_vec()))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);
    let cfg = test_config(server.uri());

    let result = tokio::time::timeout(
        Duration::from_secs(15),
        run_update_flow_with_config(
            install_dir.path().to_path_buf(),
            Channel::Stable,
            OsTarget::Windows,
            Arc::clone(&state),
            &mut user_rx,
            cfg,
        ),
    )
    .await
    .expect("download auto-recovery must complete within 15 s of wall time");

    match result {
        Ok(FlowOutcome::Updated(launcher_rel)) => {
            assert_eq!(launcher_rel.as_str(), "launcher/app.jar");
        }
        other => panic!(
            "expected Updated after download auto-retry, got {other:?}"
        ),
    }
}

#[tokio::test(flavor = "current_thread")]
async fn temp_dir_pre_clean_wipes_stale_content_on_entry() {
    // Pre-existing `.tmp-update/` content from a crashed prior run
    // must be wiped when `process_release` starts.
    //
    // Previous iterations of this test seeded either (a) an unrelated
    // sentinel file at a path the downloader never touches, or (b)
    // stale bytes at the download target. Both FALSELY passed: (a)
    // TempDirGuard::drop wipes on exit regardless; (b) `File::create`
    // truncates, so stale bytes get overwritten before hash-verify.
    //
    // The reliable signal: the `Downloader` writes to
    // `.tmp-update/<basename>` (downloader.rs:134 — file_name is the
    // basename of `file.path`, NOT the full relative path). Seed a
    // DIRECTORY at that exact path. With pre-clean: remove_dir_all
    // wipes `.tmp-update/` entirely, Downloader::new recreates it
    // empty, `File::create(".tmp-update/app.jar")` succeeds because
    // there's no directory in the way. WITHOUT pre-clean: the
    // directory survives process_release entry, `File::create` fails
    // with "Is a directory"/"Access is denied", and the flow errors
    // out. Mutation finally catches.
    let install_dir = tempfile::tempdir().unwrap();
    let stale_temp = install_dir.path().join(".tmp-update");
    // Create a directory exactly where the downloader will try to
    // `File::create` the JAR (basename = "app.jar", per
    // downloader.rs:134 basename-only logic).
    let blocking_dir = stale_temp.join("app.jar");
    std::fs::create_dir_all(&blocking_dir).unwrap();
    std::fs::write(blocking_dir.join("junk.bin"), b"survives without pre-clean")
        .unwrap();
    assert!(
        blocking_dir.is_dir(),
        "pre-condition: directory obstruction at download target"
    );

    let server = MockServer::start().await;
    let file_bytes: &[u8] = b"fresh payload";
    let file_sha = sha256_hex(file_bytes);
    let file_url = format!("{}/download/launcher-app.jar", server.uri());
    let manifest_url = format!("{}/download/manifest-windows.json", server.uri());

    Mock::given(method("GET"))
        .and(path("/download/launcher-app.jar"))
        .respond_with(ResponseTemplate::new(200).set_body_bytes(file_bytes.to_vec()))
        .mount(&server)
        .await;
    let m_json = manifest_json(
        "0.1.0",
        &[(
            "launcher/app.jar",
            &file_url,
            file_bytes.len() as u64,
            &file_sha,
        )],
    );
    Mock::given(method("GET"))
        .and(path("/download/manifest-windows.json"))
        .respond_with(ResponseTemplate::new(200).set_body_string(m_json))
        .mount(&server)
        .await;
    let r_json = release_json("v0.1.0", &manifest_url);
    Mock::given(method("GET"))
        .and(path(latest_release_path()))
        .respond_with(ResponseTemplate::new(200).set_body_string(r_json))
        .mount(&server)
        .await;

    let state = Arc::new(Mutex::new(UiState::Checking));
    let (_user_tx, mut user_rx) = mpsc::channel::<UserAction>(8);

    let result = run_update_flow_with_config(
        install_dir.path().to_path_buf(),
        Channel::Stable,
        OsTarget::Windows,
        Arc::clone(&state),
        &mut user_rx,
        test_config(server.uri()),
    )
    .await;

    // With the entry pre-clean active, the blocking directory is
    // removed before the download starts, `File::create` succeeds,
    // and the flow reports Updated. Without the pre-clean (mutation:
    // delete `fs::remove_dir_all(&temp_dir)` at process_release top),
    // the directory remains and `File::create` fails → flow returns
    // Err. This is the assertion that actually gates the pre-clean.
    assert!(
        matches!(result, Ok(FlowOutcome::Updated(_))),
        "pre-clean must remove the blocking directory so File::create \
         can create the fresh download file; got {result:?}"
    );
}
