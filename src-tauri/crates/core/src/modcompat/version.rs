//! Versionen und Versions-Bedingungen so, wie die Modloader sie auswerten:
//!
//! * Fabric/Quilt: SemVer-artig (`1.2.3-beta.1+mc1.21.11`, beliebig viele
//!   Zahlen-Teile, Build-Metadaten zählen nicht), Bedingungen mit
//!   `= > >= < <= ~ ^`, Platzhaltern `x`/`X`/`*` und Leerzeichen als UND.
//!   Nachgebaut nach `SemanticVersionImpl`, `VersionPredicateParser` und
//!   `VersionComparisonOperator` aus Fabric Loader.
//! * (Neo)Forge: Maven-Bereiche wie `[1.0,2.0)`, `(,1.5]` oder `[1.2]`.
//!
//! Im Zweifel (Version oder Bedingung nicht lesbar) wird **kein** Konflikt
//! gemeldet – lieber einmal zu wenig warnen als eine funktionierende Mod
//! grundlos austauschen.

use std::cmp::Ordering;

/// Ein Teil der Vorab-Kennung (`beta.1` → `beta`, `1`).
#[derive(Debug, Clone, PartialEq, Eq)]
enum Ident {
    Num(u64),
    Text(String),
}

/// Eine Fabric-SemVer-Version. Build-Metadaten (`+mc1.21.11`) werden beim
/// Vergleich ignoriert – genau wie im Loader.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SemVer {
    core: Vec<u64>,
    pre: Vec<Ident>,
}

/// Wie [`SemVer`], aber als Bedingungs-Teil: Die letzten Zahlen dürfen
/// Platzhalter sein (`0.8.x`).
struct WildVersion {
    /// Zahlen vor dem ersten Platzhalter.
    fixed: Vec<u64>,
    wildcard: bool,
    pre: Vec<Ident>,
}

fn is_ident_part(part: &str) -> bool {
    !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
}

/// `a.b.c` aus Buchstaben, Ziffern und `-` (leer ist erlaubt).
fn valid_dotted(text: &str) -> bool {
    text.is_empty() || text.split('.').all(is_ident_part)
}

fn parse_pre(text: &str) -> Vec<Ident> {
    if text.is_empty() {
        return Vec::new();
    }
    text.split('.')
        .map(|p| match p.parse::<u64>() {
            Ok(n) if p.bytes().all(|b| b.is_ascii_digit()) => Ident::Num(n),
            _ => Ident::Text(p.to_owned()),
        })
        .collect()
}

/// Zerlegt wie Fabric: erst `+Build`, dann `-Vorab`, dann die Zahlen.
fn split_version(text: &str) -> Option<(&str, Option<&str>)> {
    let text = text.trim();
    let (rest, build) = match text.split_once('+') {
        Some((rest, build)) => (rest, Some(build)),
        None => (text, None),
    };
    if build.is_some_and(|b| !valid_dotted(b)) {
        return None;
    }
    let (core, pre) = match rest.split_once('-') {
        Some((core, pre)) => (core, Some(pre)),
        None => (rest, None),
    };
    if pre.is_some_and(|p| !valid_dotted(p)) || core.is_empty() || core.starts_with('.') || core.ends_with('.') {
        return None;
    }
    Some((core, pre))
}

impl SemVer {
    /// Liest eine Mod-Version; `None`, wenn sie kein SemVer ist (dann
    /// behandelt Fabric sie als bloßen Text).
    pub fn parse(text: &str) -> Option<Self> {
        let (core, pre) = split_version(text)?;
        let core = core
            .split('.')
            .map(|c| if c.bytes().all(|b| b.is_ascii_digit()) { c.parse::<u64>().ok() } else { None })
            .collect::<Option<Vec<u64>>>()?;
        Some(Self { core, pre: parse_pre(pre.unwrap_or("")) })
    }

    fn component(&self, i: usize) -> u64 {
        self.core.get(i).copied().unwrap_or(0)
    }
}

impl WildVersion {
    fn parse(text: &str) -> Option<Self> {
        let (core, pre) = split_version(text)?;
        let mut fixed = Vec::new();
        let mut wildcard = false;
        for c in core.split('.') {
            if matches!(c, "x" | "X" | "*") {
                wildcard = true;
            } else if wildcard {
                // „1.x.2“ lehnt Fabric ab.
                return None;
            } else if c.bytes().all(|b| b.is_ascii_digit()) {
                fixed.push(c.parse::<u64>().ok()?);
            } else {
                return None;
            }
        }
        // Nur „x“ ist keine Version; Vorab-Kennungen mit Platzhaltern auch nicht.
        if wildcard && (fixed.is_empty() || pre.is_some()) {
            return None;
        }
        Some(Self { fixed, wildcard, pre: parse_pre(pre.unwrap_or("")) })
    }
}

fn compare_pre(a: &[Ident], b: &[Ident]) -> Ordering {
    match (a.is_empty(), b.is_empty()) {
        (true, true) => return Ordering::Equal,
        // Ohne Vorab-Kennung ist neuer als mit.
        (true, false) => return Ordering::Greater,
        (false, true) => return Ordering::Less,
        (false, false) => {}
    }
    for (x, y) in a.iter().zip(b) {
        let ord = match (x, y) {
            (Ident::Num(x), Ident::Num(y)) => x.cmp(y),
            (Ident::Num(_), Ident::Text(_)) => Ordering::Less,
            (Ident::Text(_), Ident::Num(_)) => Ordering::Greater,
            (Ident::Text(x), Ident::Text(y)) => x.cmp(y),
        };
        if ord != Ordering::Equal {
            return ord;
        }
    }
    a.len().cmp(&b.len())
}

impl Ord for SemVer {
    fn cmp(&self, other: &Self) -> Ordering {
        let len = self.core.len().max(other.core.len());
        (0..len)
            .map(|i| self.component(i).cmp(&other.component(i)))
            .find(|o| *o != Ordering::Equal)
            .unwrap_or_else(|| compare_pre(&self.pre, &other.pre))
    }
}

impl PartialOrd for SemVer {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Op {
    Eq,
    Gt,
    Ge,
    Lt,
    Le,
    /// `~1.2.3`: ab 1.2.3, gleiche Haupt- und Nebenversion.
    Tilde,
    /// `^1.2.3`: ab 1.2.3, gleiche Hauptversion.
    Caret,
}

impl Op {
    fn inclusive(self) -> bool {
        !matches!(self, Self::Gt | Self::Lt)
    }
}

/// Ein einzelner Vergleich (`>=0.8.0`).
struct Single {
    op: Op,
    reference: Reference,
}

enum Reference {
    Sem(SemVer),
    /// Keine SemVer-Version – dann zählt nur Gleichheit des Textes.
    Text(String),
}

impl Single {
    fn parse(text: &str) -> Option<Self> {
        let (op, rest) = [(">=", Op::Ge), ("<=", Op::Le), (">", Op::Gt), ("<", Op::Lt), ("=", Op::Eq), ("~", Op::Tilde), ("^", Op::Caret)]
            .into_iter()
            .find_map(|(prefix, op)| text.strip_prefix(prefix).map(|rest| (op, rest)))
            .unwrap_or((Op::Eq, text));
        match WildVersion::parse(rest) {
            Some(w) if w.wildcard => {
                // „1.x“ → „^1“, „1.2.x“ → „~1.2“ (nur ohne eigenen Operator).
                if op != Op::Eq {
                    return None;
                }
                let op = if w.fixed.len() == 1 { Op::Caret } else { Op::Tilde };
                Some(Self { op, reference: Reference::Sem(SemVer { core: w.fixed, pre: Vec::new() }) })
            }
            Some(w) => Some(Self { op, reference: Reference::Sem(SemVer { core: w.fixed, pre: w.pre }) }),
            // Kaputte Platzhalter („1.x.2“) lehnt Fabric ab – dann nicht auswertbar.
            None if rest.split(['.', '-', '+']).any(|c| matches!(c, "x" | "X" | "*")) => None,
            None if op.inclusive() && !rest.is_empty() => Some(Self { op, reference: Reference::Text(rest.to_owned()) }),
            None => None,
        }
    }

    /// `None` = nicht auswertbar.
    fn test(&self, version: &str, sem: Option<&SemVer>) -> Option<bool> {
        match (&self.reference, sem) {
            (Reference::Sem(r), Some(v)) => Some(match self.op {
                Op::Eq => v == r || v.cmp(r) == Ordering::Equal,
                Op::Gt => v > r,
                Op::Ge => v >= r,
                Op::Lt => v < r,
                Op::Le => v <= r,
                Op::Tilde => v >= r && v.component(0) == r.component(0) && v.component(1) == r.component(1),
                Op::Caret => v >= r && v.component(0) == r.component(0),
            }),
            (Reference::Text(r), _) => Some(version.trim() == r),
            // Text-Version gegen SemVer-Bedingung: Fabric vergleicht dann nur den Text.
            (Reference::Sem(_), None) => None,
        }
    }
}

/// Eine Fabric-Bedingung wie `>=0.8.0 <0.9` (Leerzeichen = UND).
pub struct Predicate {
    all: Vec<Single>,
}

impl Predicate {
    /// `None`, wenn die Bedingung nicht lesbar ist.
    pub fn parse(text: &str) -> Option<Self> {
        let mut all = Vec::new();
        for part in text.split(' ').map(str::trim).filter(|p| !p.is_empty() && *p != "*") {
            all.push(Single::parse(part)?);
        }
        Some(Self { all })
    }

    /// Passt `version`? `None` = nicht auswertbar (Text-Version o. Ä.).
    pub fn matches(&self, version: &str) -> Option<bool> {
        let sem = SemVer::parse(version);
        let mut result = true;
        for single in &self.all {
            result &= single.test(version, sem.as_ref())?;
        }
        Some(result)
    }
}

/// Passt `version` zu einer der Bedingungen (`any_of` = ODER wie ein JSON-Array
/// in `fabric.mod.json`)? `None` = nicht auswertbar.
pub fn fabric_matches(any_of: &[String], version: &str) -> Option<bool> {
    if any_of.is_empty() {
        return Some(true);
    }
    let mut unknown = false;
    for text in any_of {
        match Predicate::parse(text).and_then(|p| p.matches(version)) {
            Some(true) => return Some(true),
            Some(false) => {}
            None => unknown = true,
        }
    }
    if unknown { None } else { Some(false) }
}

// --- Maven ((Neo)Forge) ------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
enum Item {
    Num(u64),
    Text(String),
}

/// Rang bekannter Maven-Qualifier; Veröffentlichung = 5.
fn qualifier_rank(text: &str) -> (u8, &str) {
    match text {
        "alpha" | "a" => (1, ""),
        "beta" | "b" => (2, ""),
        "milestone" | "m" => (3, ""),
        "rc" | "cr" | "pre" => (4, ""),
        "snapshot" => (4, "~"),
        "" | "ga" | "final" | "release" => (5, ""),
        "sp" => (6, ""),
        other => (7, other),
    }
}

fn maven_items(version: &str) -> Vec<Item> {
    let mut items = Vec::new();
    let mut current = String::new();
    let mut digits = false;
    let flush = |current: &mut String, items: &mut Vec<Item>, digits: bool| {
        if !current.is_empty() {
            items.push(if digits {
                Item::Num(current.parse().unwrap_or(u64::MAX))
            } else {
                Item::Text(current.to_ascii_lowercase())
            });
            current.clear();
        }
    };
    for c in version.trim().chars() {
        if matches!(c, '.' | '-' | '_' | '+') {
            flush(&mut current, &mut items, digits);
            continue;
        }
        let is_digit = c.is_ascii_digit();
        if !current.is_empty() && is_digit != digits {
            flush(&mut current, &mut items, digits);
        }
        digits = is_digit;
        current.push(c);
    }
    flush(&mut current, &mut items, digits);
    items
}

/// Vergleich angelehnt an Mavens `ComparableVersion`.
pub fn maven_compare(a: &str, b: &str) -> Ordering {
    let (a, b) = (maven_items(a), maven_items(b));
    for i in 0..a.len().max(b.len()) {
        let ord = match (a.get(i), b.get(i)) {
            (Some(Item::Num(x)), Some(Item::Num(y))) => x.cmp(y),
            (Some(Item::Num(_)), Some(Item::Text(_))) => Ordering::Greater,
            (Some(Item::Text(_)), Some(Item::Num(_))) => Ordering::Less,
            (Some(Item::Text(x)), Some(Item::Text(y))) => qualifier_rank(x).cmp(&qualifier_rank(y)),
            (Some(Item::Num(x)), None) => x.cmp(&0),
            (None, Some(Item::Num(y))) => 0.cmp(y),
            (Some(Item::Text(x)), None) => qualifier_rank(x).cmp(&qualifier_rank("")),
            (None, Some(Item::Text(y))) => qualifier_rank("").cmp(&qualifier_rank(y)),
            (None, None) => Ordering::Equal,
        };
        if ord != Ordering::Equal {
            return ord;
        }
    }
    Ordering::Equal
}

/// Liegt `version` in einem Maven-Bereich wie `[1.0,2.0)` oder `[1.0,1.2),(1.3,]`?
/// Eine bloße Version („1.0“) ist bei Maven nur eine Empfehlung und passt immer.
/// `None` = nicht auswertbar.
pub fn maven_matches(range: &str, version: &str) -> Option<bool> {
    let range: String = range.chars().filter(|c| !c.is_whitespace()).collect();
    if range.is_empty() || range == "*" {
        return Some(true);
    }
    if !range.starts_with(['[', '(']) {
        return Some(true);
    }
    let mut rest = range.as_str();
    let mut any = false;
    while !rest.is_empty() {
        let end = rest.find([']', ')'])?;
        let (part, tail) = rest.split_at(end + 1);
        any |= maven_single(part, version)?;
        rest = tail.strip_prefix(',').unwrap_or(tail);
        if !rest.is_empty() && !rest.starts_with(['[', '(']) {
            return None;
        }
    }
    Some(any)
}

fn maven_single(part: &str, version: &str) -> Option<bool> {
    let lower_inclusive = part.starts_with('[');
    let upper_inclusive = part.ends_with(']');
    let inner = &part[1..part.len() - 1];
    match inner.split_once(',') {
        None => Some(!inner.is_empty() && lower_inclusive && upper_inclusive && maven_compare(version, inner) == Ordering::Equal),
        Some((low, high)) => {
            let low_ok = low.is_empty() || {
                let o = maven_compare(version, low);
                o == Ordering::Greater || (lower_inclusive && o == Ordering::Equal)
            };
            let high_ok = high.is_empty() || {
                let o = maven_compare(version, high);
                o == Ordering::Less || (upper_inclusive && o == Ordering::Equal)
            };
            Some(low_ok && high_ok)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(pred: &str, version: &str) -> Option<bool> {
        Predicate::parse(pred).and_then(|p| p.matches(version))
    }

    #[test]
    fn semver_order_like_fabric() {
        let v = |s: &str| SemVer::parse(s).unwrap();
        assert!(v("1.10.7+mc1.21.11") == v("1.10.7"));
        assert!(v("1.10.8") > v("1.10.7+mc1.21.11"));
        assert!(v("0.8.15-beta.1+mc1.21.11") < v("0.8.15"));
        assert!(v("0.8.15-beta.1") > v("0.8.14"));
        assert!(v("1.0.0-alpha") < v("1.0.0-alpha.1"));
        assert!(v("1.0.0-alpha.1") < v("1.0.0-alpha.beta"));
        assert!(v("1.0.0-beta.2") < v("1.0.0-beta.11"));
        assert!(v("1.0.0-rc.1") < v("1.0.0"));
        // Fehlende Teile zählen als 0.
        assert_eq!(v("1.2").cmp(&v("1.2.0")), Ordering::Equal);
        assert!(v("1.2.0.1") > v("1.2"));
        // Kein SemVer: Text-Version.
        for bad in ["v1.2", "1.2.", ".1", "1..2", "abc", "1.x", "", "1.0+a+b!"] {
            assert!(SemVer::parse(bad).is_none(), "{bad}");
        }
        assert!(SemVer::parse("1.21-4.5").is_some());
    }

    #[test]
    fn operators() {
        assert_eq!(m("<=1.10.7", "1.10.7+mc1.21.11"), Some(true));
        assert_eq!(m("<=1.10.6", "1.10.7+mc1.21.11"), Some(false));
        assert_eq!(m("<1.10.7", "1.10.7"), Some(false));
        assert_eq!(m(">=0.8.0", "0.8.14+mc1.21.11"), Some(true));
        assert_eq!(m(">0.8.14", "0.8.14+mc1.21.11"), Some(false));
        assert_eq!(m("=1.2.3", "1.2.3+build.7"), Some(true));
        assert_eq!(m("1.2.3", "1.2.4"), Some(false));
        assert_eq!(m("*", "irgendwas"), Some(true));
        assert_eq!(m("", "1.0"), Some(true));
        // Tilde: gleiche Neben-, Caret: gleiche Hauptversion.
        assert_eq!(m("~1.2.3", "1.2.9"), Some(true));
        assert_eq!(m("~1.2.3", "1.3.0"), Some(false));
        assert_eq!(m("~1.2.3", "1.2.2"), Some(false));
        assert_eq!(m("^1.2.3", "1.9.0"), Some(true));
        assert_eq!(m("^1.2.3", "2.0.0"), Some(false));
        // Vorab-Versionen: 2.0.2-1.21.2 < 2.0.2.
        assert_eq!(m("<2.0.2-1.21.2", "2.0.1"), Some(true));
        assert_eq!(m("<1.6.0-beta.2", "1.6.0-beta.1"), Some(true));
        assert_eq!(m("<1.6.0-beta.2", "1.6.0"), Some(false));
    }

    #[test]
    fn wildcards_and_and() {
        assert_eq!(m("0.8.x", "0.8.14+mc1.21.11"), Some(true));
        assert_eq!(m("0.8.x", "0.9.0"), Some(false));
        assert_eq!(m("0.8.X", "0.7.9"), Some(false));
        assert_eq!(m("1.x", "1.20.4"), Some(true));
        assert_eq!(m("1.*", "2.0"), Some(false));
        assert_eq!(m("0.8.x", "0.8.15-beta.1+mc1.21.11"), Some(true));
        // Platzhalter mit eigenem Operator ist ungültig → nicht auswertbar.
        assert_eq!(m(">=1.x", "2.0"), None);
        assert_eq!(m("1.x.2", "1.0.2"), None);
        // Leerzeichen = UND.
        assert_eq!(m(">=0.8.0 <0.9", "0.8.14"), Some(true));
        assert_eq!(m(">=0.8.0 <0.9", "0.9.0"), Some(false));
        assert_eq!(m(">=0.8.0  <0.9", "0.7.0"), Some(false));
    }

    #[test]
    fn arrays_are_or_and_text_versions_are_careful() {
        let any = |list: &[&str], v: &str| fabric_matches(&list.iter().map(|s| (*s).to_owned()).collect::<Vec<_>>(), v);
        assert_eq!(any(&["0.8.x"], "0.8.14"), Some(true));
        assert_eq!(any(&["<1.0", ">=2.0"], "2.1"), Some(true));
        assert_eq!(any(&["<1.0", ">=2.0"], "1.5"), Some(false));
        assert_eq!(any(&[], "1.5"), Some(true));
        // Text-Versionen: nur Gleichheit zählt, sonst unbekannt.
        assert_eq!(m("<=1.21-4.5", "1.21-4.5"), Some(true));
        assert_eq!(m("<=3.4.0-fabric", "3.4.0-fabric"), Some(true));
        assert_eq!(m(">=1.0", "custom-build"), None);
        assert_eq!(m("=custom-build", "custom-build"), Some(true));
        assert_eq!(m("<custom", "custom"), None);
    }

    #[test]
    fn maven_ranges() {
        assert_eq!(maven_matches("[1.0,2.0)", "1.5"), Some(true));
        assert_eq!(maven_matches("[1.0,2.0)", "2.0"), Some(false));
        assert_eq!(maven_matches("[1.0,2.0]", "2.0"), Some(true));
        assert_eq!(maven_matches("(1.0,)", "1.0"), Some(false));
        assert_eq!(maven_matches("[1.0,)", "1.0"), Some(true));
        assert_eq!(maven_matches("(,0.5.11]", "0.5.11+mc1.21.1"), Some(false));
        assert_eq!(maven_matches("(,0.5.11]", "0.5.10+mc1.21.1"), Some(true));
        assert_eq!(maven_matches("[1.2]", "1.2"), Some(true));
        assert_eq!(maven_matches("[1.2]", "1.2.1"), Some(false));
        assert_eq!(maven_matches("[1.0,1.2),(1.3,]", "1.4"), Some(true));
        assert_eq!(maven_matches("[1.0,1.2),(1.3,]", "1.25"), Some(true));
        assert_eq!(maven_matches("[1.0,1.2),(1.3,]", "1.2.5"), Some(false));
        assert_eq!(maven_matches("1.0", "0.1"), Some(true));
        assert_eq!(maven_matches("*", "0.1"), Some(true));
        assert_eq!(maven_matches("[1.0", "0.1"), None);
        assert!(maven_compare("1.0-beta", "1.0") == Ordering::Less);
        assert!(maven_compare("1.0-rc1", "1.0-beta2") == Ordering::Greater);
        assert!(maven_compare("1.0.1", "1.0") == Ordering::Greater);
        assert!(maven_compare("21.1.0", "21.0.167") == Ordering::Greater);
    }
}
