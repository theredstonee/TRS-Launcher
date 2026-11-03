//! Rangliste der Minispiele (API §42): Startwert holen, Partie einreichen, Liste lesen, Eintrag entfernen.
//!
//! Gerechnet wird nur auf dem Server: Er spielt die eingereichten Eingaben nach und zählt das Ergebnis.

use serde_json::{Value, json};

use super::Req;
use crate::{Error, Launcher, Result, validate};

const GAMES: [&str; 2] = ["stapler", "loren"];
/// Gleiche Grenze wie `MAX_INPUTS` in `app/utils/minigames/replay.ts` und auf dem Server.
const MAX_INPUTS: usize = 36_000;
const MAX_REASON_LEN: usize = 300;

fn game_arg(game: &str) -> Result<&'static str> {
    GAMES
        .iter()
        .copied()
        .find(|known| *known == game)
        .ok_or_else(|| Error::validation(crate::msg!("minigames.unknownGame", "Dieses Minispiel gibt es nicht.")))
}

fn run_arg(run: &str) -> Result<&str> {
    let ok = !run.is_empty() && run.len() <= 64 && run.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-');
    if ok { Ok(run) } else { Err(Error::validation(crate::msg!("minigames.invalidRun", "Diese Partie ist ungültig."))) }
}

impl Launcher {
    /// Startwert einer gewerteten Partie.
    pub async fn trs_minigame_start(&self, game: &str) -> Result<Value> {
        let game = game_arg(game)?;
        self.trs_get(Req::post_empty(format!("/v1/minigames/{game}/start"))).await
    }

    /// Reicht eine beendete Partie ein. Der Server rechnet sie nach.
    pub async fn trs_minigame_submit(&self, game: &str, run: &str, inputs: Vec<u32>, score: u64) -> Result<Value> {
        let game = game_arg(game)?;
        let run = run_arg(run)?;
        if inputs.len() > MAX_INPUTS {
            return Err(Error::validation(crate::msg!("minigames.invalidRun", "Diese Partie ist ungültig.")));
        }
        self.trs_get(Req::post(format!("/v1/minigames/{game}/runs"), json!({ "runId": run, "inputs": inputs, "score": score }))).await
    }

    /// Bestenliste eines Spiels samt eigenem Platz.
    pub async fn trs_minigame_leaderboard(&self, game: &str) -> Result<Value> {
        let game = game_arg(game)?;
        self.trs_get(Req::get(format!("/v1/minigames/{game}/leaderboard"))).await
    }

    /// Team: falschen Eintrag entfernen (Recht `minigames.manage`).
    pub async fn trs_minigame_remove(&self, game: &str, uuid: &str, reason: &str) -> Result<()> {
        let game = game_arg(game)?;
        let uuid = validate::uuid(uuid).ok_or_else(|| Error::validation(crate::msg!("trsOps.invalidPlayerId", "Ungültige Spieler-ID.")))?;
        let reason = reason.trim();
        if reason.is_empty() || reason.chars().count() > MAX_REASON_LEN {
            return Err(Error::validation(crate::msg!("minigames.invalidReason", "Bitte gib einen kurzen Grund an.")));
        }
        self.trs_do(Req::post(format!("/v1/admin/minigames/{game}/scores/{uuid}/remove"), json!({ "reason": reason }))).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_known_games_reach_the_url() {
        assert_eq!(game_arg("stapler").unwrap(), "stapler");
        assert_eq!(game_arg("loren").unwrap(), "loren");
        assert!(game_arg("../admin").is_err());
        assert!(game_arg("").is_err());
    }

    #[test]
    fn run_ids_are_checked() {
        assert!(run_arg("0f3a-77").is_ok());
        assert!(run_arg("").is_err());
        assert!(run_arg("a/b").is_err());
        assert!(run_arg(&"a".repeat(65)).is_err());
    }
}
