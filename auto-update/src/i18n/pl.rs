//! Polish string bundle.

use super::Strings;

pub const STRINGS: &Strings = &Strings {
    checking: "Sprawdzanie aktualizacji...",
    verifying: "Weryfikacja plików...",
    installing: "Instalowanie aktualizacji...",
    starting: "Uruchamianie SingularityMC...",
    no_internet: "Sprawdź swoje połączenie z internetem",
    download_failed: "Aktualizacja nieudana",
    help: "Pomoc",
    offline_mode: "TRYB OFFLINE",
    retry: "Ponów",
    close: "Zamknij",
    no_offline_install:
        "Pobieranie nieudane, a lokalna instalacja nie jest dostępna do trybu offline.",
    unhandled_flow_outcome:
        "Nieoczekiwany stan aktualizacji. Zgłoś problem na Discordzie.",
};
