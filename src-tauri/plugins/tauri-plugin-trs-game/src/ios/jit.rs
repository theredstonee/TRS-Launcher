//! JIT-Prüfung vor dem Start. Ohne JIT läuft Java auf iOS nur im Interpreter –
//! Minecraft wäre unspielbar, deshalb startet die Engine dann gar nicht.

use serde::{Deserialize, Serialize};

use super::probe::JitProbe;

/// Bits aus `DeviceGetJITFlags` (Amethyst `utils.h`).
pub const FLAG_IOS_26: u32 = 1 << 0;
pub const FLAG_FORCE_MIRRORED: u32 = 1 << 1;
pub const FLAG_HAS_TXM: u32 = 1 << 2;

/// Welche Anleitung der Hilfe-Bildschirm zeigt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum JitHelp {
    /// SideStore/StikDebug, AltServer, TrollStore, Jailbreak.
    Generic,
    /// iOS 26 mit TXM: StikDebug mit Skript `UniversalJIT26.js`, Debugger bleibt angehängt.
    Txm,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", tag = "verdict")]
pub enum JitVerdict {
    Ready,
    NeedsJit { help: JitHelp },
}

/// Geräte, die den TXM-Weg brauchen (wie `requiresTXMWorkaround` in Amethyst).
pub fn needs_txm_workaround(flags: u32) -> bool {
    flags & (FLAG_FORCE_MIRRORED | FLAG_HAS_TXM) == (FLAG_FORCE_MIRRORED | FLAG_HAS_TXM)
}

pub fn evaluate(probe: &JitProbe) -> JitVerdict {
    if probe.enabled {
        return JitVerdict::Ready;
    }
    let help = if needs_txm_workaround(probe.flags) { JitHelp::Txm } else { JitHelp::Generic };
    JitVerdict::NeedsJit { help }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn probe(enabled: bool, flags: u32) -> JitProbe {
        JitProbe { enabled, cs_debugged: enabled, flags, debugger_attached: false }
    }

    #[test]
    fn ready_when_engine_says_so() {
        assert_eq!(evaluate(&probe(true, 0)), JitVerdict::Ready);
        assert_eq!(evaluate(&probe(true, FLAG_IOS_26 | FLAG_FORCE_MIRRORED | FLAG_HAS_TXM)), JitVerdict::Ready);
    }

    #[test]
    fn generic_help_without_txm() {
        assert_eq!(evaluate(&probe(false, 0)), JitVerdict::NeedsJit { help: JitHelp::Generic });
        // iOS 26 ohne TXM (ältere Chips) braucht kein Skript.
        assert_eq!(evaluate(&probe(false, FLAG_IOS_26 | FLAG_FORCE_MIRRORED)), JitVerdict::NeedsJit { help: JitHelp::Generic });
        assert_eq!(evaluate(&probe(false, FLAG_HAS_TXM)), JitVerdict::NeedsJit { help: JitHelp::Generic });
    }

    #[test]
    fn txm_help_needs_both_flags() {
        let p = JitProbe { enabled: false, cs_debugged: true, flags: FLAG_IOS_26 | FLAG_FORCE_MIRRORED | FLAG_HAS_TXM, debugger_attached: false };
        assert_eq!(evaluate(&p), JitVerdict::NeedsJit { help: JitHelp::Txm });
    }

    #[test]
    fn verdict_json_shape() {
        let v = serde_json::to_value(JitVerdict::NeedsJit { help: JitHelp::Txm }).unwrap();
        assert_eq!(v["verdict"], "needsJit");
        assert_eq!(v["help"], "txm");
    }
}
