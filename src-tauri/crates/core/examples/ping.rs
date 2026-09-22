//! `cargo run -p trs-core --example ping -- play.example.org [weitere …]`

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    for address in std::env::args().skip(1) {
        let s = trs_core::servers::ping(&address).await?;
        println!(
            "{address}: online={} {}/{} {}ms version={:?} favicon={} motd={:?}",
            s.online,
            s.players_online,
            s.players_max,
            s.latency_ms,
            s.version,
            s.favicon.map_or(0, |f| f.len()),
            s.motd
        );
    }
    Ok(())
}
