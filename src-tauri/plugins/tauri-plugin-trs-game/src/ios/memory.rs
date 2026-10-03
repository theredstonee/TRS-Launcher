//! Heap-Größe (`-Xmx`) für iOS. Sideload-Signierer entfernen oft die Entitlements
//! für mehr Speicher – deshalb richtet sich die Größe nach dem, was das Gerät
//! gerade wirklich erlaubt (`os_proc_available_memory`, zusammenhängender Adressraum).

use serde::Serialize;

use super::probe::{Entitlements, MemoryProbe};
use crate::{Error, Result};

/// Darunter startet Minecraft nicht sinnvoll.
pub const MIN_HEAP_MB: u32 = 512;
/// Obergrenze für die automatische Wahl.
pub const AUTO_MAX_MB: u32 = 4096;
const STEP_MB: u32 = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum HeapLimit {
    /// Wunsch aus dem Profil passte.
    Requested,
    /// Automatische Wahl.
    Auto,
    /// Gekürzt: Speicherbudget der App.
    Budget,
    /// Gekürzt: kein so großer zusammenhängender Adressbereich (fehlendes Entitlement).
    AddressSpace,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HeapPlan {
    pub xmx_mb: u32,
    pub limited_by: HeapLimit,
    /// Knapp – moderne Versionen/Modpacks können ruckeln oder abstürzen.
    pub low: bool,
}

/// Speicher außerhalb des Heaps: Metaspace, Code-Cache, Grafiktreiber, Launcher.
pub fn overhead_mb(java_major: u8) -> u64 {
    if java_major <= 8 { 400 } else { 512 }
}

fn round_down(mb: u64) -> u32 {
    let mb = u32::try_from(mb).unwrap_or(u32::MAX);
    mb - mb % STEP_MB
}

/// Plant `-Xmx`. `requested_mb == 0` = automatisch.
pub fn plan_heap(mem: &MemoryProbe, ent: &Entitlements, requested_mb: u32, java_major: u8) -> Result<HeapPlan> {
    if mem.physical_mb == 0 {
        return Err(Error::NotEnoughMemory);
    }
    // Budget: echte Restmenge, sonst grobe Schätzung wie Amethyst (0,4 bzw. 0,25 vom RAM).
    let budget = if mem.available_mb > 0 {
        mem.available_mb
    } else if ent.increased_memory_limit {
        mem.physical_mb * 2 / 5
    } else {
        mem.physical_mb / 4
    };
    let mut max = budget.saturating_sub(overhead_mb(java_major)).min(mem.physical_mb / 2);
    let mut limit = HeapLimit::Budget;
    if mem.max_contiguous_mb > 0 && mem.max_contiguous_mb < max {
        max = mem.max_contiguous_mb;
        limit = HeapLimit::AddressSpace;
    }
    let max = round_down(max);

    let (wanted, wanted_kind) = if requested_mb == 0 {
        let auto = (mem.physical_mb / 4).max(1024).min(u64::from(AUTO_MAX_MB));
        (round_down(auto), HeapLimit::Auto)
    } else {
        (round_down(u64::from(requested_mb)), HeapLimit::Requested)
    };
    let (xmx, limited_by) = if wanted <= max { (wanted, wanted_kind) } else { (max, limit) };
    if xmx < MIN_HEAP_MB {
        return Err(Error::NotEnoughMemory);
    }
    let low = xmx < if java_major <= 8 { 768 } else { 1024 };
    Ok(HeapPlan { xmx_mb: xmx, limited_by, low })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn mem(physical: u64, available: u64, contiguous: u64) -> MemoryProbe {
        MemoryProbe { physical_mb: physical, available_mb: available, max_contiguous_mb: contiguous }
    }

    #[test]
    fn auto_on_6gb_phone() {
        let plan = plan_heap(&mem(6144, 3500, 0), &Entitlements::default(), 0, 21).unwrap();
        assert_eq!(plan.xmx_mb, 1536);
        assert_eq!(plan.limited_by, HeapLimit::Auto);
        assert!(!plan.low);
    }

    #[test]
    fn requested_is_capped_by_budget() {
        let plan = plan_heap(&mem(4096, 2000, 0), &Entitlements::default(), 4096, 17).unwrap();
        // 2000 - 512 = 1488 -> 1472
        assert_eq!(plan.xmx_mb, 1472);
        assert_eq!(plan.limited_by, HeapLimit::Budget);
    }

    #[test]
    fn requested_fits() {
        let plan = plan_heap(&mem(8192, 6000, 0), &Entitlements::default(), 2000, 21).unwrap();
        assert_eq!(plan.xmx_mb, 1984);
        assert_eq!(plan.limited_by, HeapLimit::Requested);
    }

    #[test]
    fn address_space_limit_without_entitlement() {
        let plan = plan_heap(&mem(8192, 6000, 1100), &Entitlements::default(), 3072, 21).unwrap();
        assert_eq!(plan.xmx_mb, 1088);
        assert_eq!(plan.limited_by, HeapLimit::AddressSpace);
    }

    #[test]
    fn never_more_than_half_ram() {
        let plan = plan_heap(&mem(4096, 4000, 0), &Entitlements::default(), 4096, 8).unwrap();
        assert_eq!(plan.xmx_mb, 2048);
    }

    #[test]
    fn fallback_budget_uses_entitlement() {
        let with = plan_heap(&mem(6144, 0, 0), &Entitlements { increased_memory_limit: true, ..Default::default() }, 4096, 21).unwrap();
        let without = plan_heap(&mem(6144, 0, 0), &Entitlements::default(), 4096, 21).unwrap();
        // 6144*0.4=2457-512=1945 -> 1920 ; 6144/4=1536-512=1024
        assert_eq!(with.xmx_mb, 1920);
        assert_eq!(without.xmx_mb, 1024);
    }

    #[test]
    fn too_little_memory_fails() {
        assert!(matches!(plan_heap(&mem(2048, 800, 0), &Entitlements::default(), 0, 21), Err(Error::NotEnoughMemory)));
        assert!(matches!(plan_heap(&mem(0, 0, 0), &Entitlements::default(), 0, 8), Err(Error::NotEnoughMemory)));
    }

    #[test]
    fn low_flag_for_small_heaps() {
        let plan = plan_heap(&mem(3072, 1300, 0), &Entitlements::default(), 0, 8).unwrap();
        // 1300-400=900 -> 896
        assert_eq!(plan.xmx_mb, 896);
        assert!(!plan.low);
        let plan = plan_heap(&mem(3072, 1300, 0), &Entitlements::default(), 0, 17).unwrap();
        assert_eq!(plan.xmx_mb, 768);
        assert!(plan.low);
    }
}
