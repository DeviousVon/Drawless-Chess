#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const android = path.join(root, "android", "app", "src", "main", "res");
const output = path.join(root, "iosApp", "DrawlessChess");

const locales = new Map([
  ["en", "values"],
  ["de", "values-de"],
  ["fr", "values-fr"],
  ["es-419", "values-b+es+419"],
  ["pt-BR", "values-pt-rBR"],
]);

const aliases = new Map([
  ["Offline decisive chess", "brand_features"],
  ["Resume Game", "home_resume_game"],
  ["Discard Saved Game", "home_discard_saved_game"],
  ["New Game", "setup_title"],
  ["Statistics", "stats_title"],
  ["Rules", "home_how_drawless_works"],
  ["License", "home_open_source_license"],
  ["Start Game", "setup_start_game"],
  ["Play as", "setup_play_as"],
  ["Drawless rules", "rules_label_drawless"],
  ["Escape rules", "rules_label_escape"],
  ["Show threatened pieces", "options_threat_indication"],
  ["Sound", "options_sound_effects"],
  ["Haptic feedback", "options_haptic_feedback"],
  ["Board coordinates", "options_board_coordinates"],
  ["Celebration effects", "options_celebration_effects"],
  ["Sound volume", "options_sound_volume"],
  ["Board theme", "theme_choose"],
  ["Games", "stats_completed_games"],
  ["Wins", "stats_record"],
  ["Average score", "stats_average_game_score"],
  ["By opponent", "stats_by_opponent"],
  ["Your move", "status_your_turn"],
  ["Finding a hint", "status_hint_thinking"],
  ["Game paused", "status_paused"],
  ["Hint", "game_hint"],
  ["Undo", "game_undo"],
  ["Pause", "game_pause"],
  ["Resume", "game_resume"],
  ["Flip", "game_flip"],
  ["Resign", "game_resign"],
  ["Play Again", "action_rematch"],
  ["Return Home", "action_home"],
  ["ios.adaptive_status_provisional", "adaptive_status_provisional"],
  ["ios.adaptive_status_matched", "adaptive_status_matched"],
  ["ios.review_beta", "review_beta"],
  ["ios.review_analyzing", "review_analyzing"],
  ["ios.review_progress", "review_progress"],
  ["ios.review_complete", "review_complete"],
  ["ios.review_complete_body", "review_complete_body"],
  ["ios.review_summary_title", "review_summary_title"],
  ["ios.review_failed", "review_failed"],
  ["ios.review_retry", "review_retry"],
  ["ios.review_move_position", "review_move_position"],
  ["ios.review_your_move_position", "review_your_move_position"],
  ["ios.review_your_moves", "review_your_moves"],
  ["ios.review_show_opponent_moves", "review_show_opponent_moves"],
  ["ios.review_no_player_moves", "review_no_player_moves"],
  ["ios.tap_anywhere_to_review", "game_tap_anywhere_to_review"],
  ["ios.game_result_accessibility", "game_result_accessibility"],
  ["ios.review_previous", "review_previous"],
  ["ios.review_next", "review_next"],
  ["ios.review_moves", "review_moves"],
  ["ios.review_waiting", "review_waiting"],
  ["ios.review_better_move", "review_better_move"],
  ["ios.review_suggested_line", "review_suggested_line"],
  ["ios.review_evaluation", "review_evaluation"],
  ["ios.review_grade_best", "review_grade_best"],
  ["ios.review_grade_good", "review_grade_good"],
  ["ios.review_grade_inaccuracy", "review_grade_inaccuracy"],
  ["ios.review_grade_mistake", "review_grade_mistake"],
  ["ios.review_grade_blunder", "review_grade_blunder"],
  ["ios.review_grade_unreviewed", "review_grade_unreviewed"],
  ["ios.review_grade_best_explanation", "review_grade_best_explanation"],
  ["ios.review_grade_good_explanation", "review_grade_good_explanation"],
  ["ios.review_grade_inaccuracy_explanation", "review_grade_inaccuracy_explanation"],
  ["ios.review_grade_mistake_explanation", "review_grade_mistake_explanation"],
  ["ios.review_grade_blunder_explanation", "review_grade_blunder_explanation"],
]);

// These Android strings are exposed to the Apple shell only through stable semantic keys.
const semanticOnlyNames = new Set([
  "game_tap_anywhere_to_review",
  "review_no_player_moves",
  "review_show_opponent_moves",
  "review_your_move_position",
  "review_your_moves",
]);

// Keep the established Apple catalog layout stable so regeneration does not create ordering-only
// diffs for native-only keys that were added after the original Android-backed catalog.
const placementGroups = new Map([
  ["Drawless Chess", ["DRAWLESS CHESS. Every game has a winner."]],
  ["ios.presentation", ["ios.options_local_storage_notice"]],
  ["ios.review_moves", [
    "ios.review_your_move_position",
    "ios.review_your_moves",
    "ios.review_show_opponent_moves",
    "ios.review_no_player_moves",
    "ios.tap_anywhere_to_review",
    "ios.game_result_accessibility",
  ]],
  ["ios.review_analyzing", [
    "Preparing game review",
    "Matching the analysis completed while you played.",
    "Checking the positions that still need analysis.",
  ]],
  ["ios.chess_board", [
    "ios.board_orientation",
    "Moving %1$s",
    "Opponent move animation",
    "Opponent moved from %1$s to %2$s",
  ]],
  ["Player stats", ["Player", "1 completed game", "%1$d completed games"]],
  ["Yuna", [
    "3 min",
    "5 min",
    "10 min",
    "Queen",
    "Rook",
    "Bishop",
    "Knight",
    "%1$s, %2$s opponent",
    "Opponent, %1$s, %2$s",
  ]],
]);

const placedKeys = new Set([...placementGroups.values()].flat());

// Copy that exists only in the native Apple shell. The ios.* keys are semantic on purpose:
// formatted values are resolved with NSLocalizedString/String(format:) instead of relying on
// SwiftUI's compiler-generated placeholder spelling. Literal SwiftUI keys are included where no
// Android catalog entry exists. Every shipped locale must provide every key.
const iosSupplement = {
  en: {
    "ios.about": "About",
    "ios.analyze_game": "Analyze game",
    "ios.assistance": "Assistance",
    "ios.chess_board": "Chess board",
    "ios.board_orientation": "%1$s at bottom",
    "ios.compare_review": "Compare every played move with the full-strength offline engine.",
    "ios.data": "Data",
    "ios.duration_3": "3 minutes",
    "ios.duration_10": "10 minutes",
    "ios.duration_30": "30 minutes",
    "ios.game_review": "Game review",
    "ios.games": "Games",
    "ios.losses": "Losses",
    "ios.options_local_storage_notice": "Saved locally by Drawless Chess and not sent to BB_Games. iCloud backup may include these settings according to your Apple device settings.",
    "ios.offline_opponent": "Offline opponent",
    "ios.presentation": "Presentation",
    "ios.quick_play_opponent": "Quick Play opponent",
    "ios.review_again": "Review again",
    "ios.review_game": "Review game",
    "ios.review_my_mistakes": "Review my mistakes",
    "ios.statistics_local_notice": "Statistics remain on this device and are recorded when a game finishes.",
    "ios.stored_only": "Stored only on this device",
    "ios.threat_score_notice": "Threat indication is assistance and reduces a winning game score.",
    "ios.version": "Version",
    "ios.you": "You",
    "ios.starting_game": "Starting game",
    "ios.moves_placeholder": "Moves will appear here",
    "Preparing game review": "Preparing game review",
    "Matching the analysis completed while you played.": "Matching the analysis completed while you played.",
    "Checking the positions that still need analysis.": "Checking the positions that still need analysis.",
    "Moving %1$s": "Moving %1$s",
    "Opponent move animation": "Opponent move animation",
    "Opponent moved from %1$s to %2$s": "Opponent moved from %1$s to %2$s",
    "ios.view_source": "View corresponding source",
    "ios.privacy_contact": "Privacy contact",
    "ios.license_body": "Drawless Chess is licensed under GNU GPL version 3 or later. Its on-device engine is modified Fairy-Stockfish, derived from Stockfish, under GPL-3.0-or-later. Sampled audio includes CC0 chess recordings by JJTaynos and mh2o, CC0 fireworks by Rudmer_Rotteveel, and MIT-licensed ion.sound recordings by Denis Ineshin. Exact corresponding source and third-party notices accompany this release.",
    "ios.privacy_body": "Drawless Chess works entirely offline. BB_Games does not collect, transmit, share, or sell personal data. Saved games, completed-game history, local statistics, and settings are stored on this device and may be included in device or iCloud backups according to your Apple settings; BB_Games cannot access those backups. Privacy questions: support@drawlesschess.com",
    "ios.quick_play_with": "Quick Play with %@",
    "ios.about_elo": "· about %ld Elo",
    "ios.approximate_elo": "Approximately %ld Elo",
    "ios.increment": "Increment: %ld seconds",
    "ios.try_move": "Try %@",
    "ios.retry_opponent": "Retry %@",
    "ios.score": "Score %ld / %ld",
    "ios.penalty_hints": "Hints: −%ld",
    "ios.penalty_undos": "Undos: −%ld",
    "ios.penalty_pauses": "Timed pauses: −%ld",
    "ios.penalty_threat": "Threat indication: −%ld",
    "ios.review_failures": "Reviewed %ld moves with %ld engine failures",
    "ios.review_matches": "%ld of %ld moves matched the engine's first choice",
    "ios.review_match": "match",
    "ios.review_unavailable": "unavailable",
    "ios.review_engine": "engine",
    "ios.opponent_record": "%ld games · %ld–%ld",
    "ios.opponent_summary": "%.1f%% wins · Avg %.1f",
    "ios.status_thinking": "%@ is thinking",
    "ios.status_you_won": "You won",
    "ios.status_you_lost": "You lost",
    "ios.status_complete": "Game complete",
    "ios.piece_on_square": "%@ %@ on %@",
    "ios.empty_square": "Empty square %@",
    "ios.name_epithet": "%@, %@",
    "Player": "Player",
    "1 completed game": "1 completed game",
    "%1$d completed games": "%1$d completed games",
    "%1$s, %2$s opponent": "%1$s, %2$s opponent",
    "Opponent, %1$s, %2$s": "Opponent, %1$s, %2$s",
  },
  de: {
    "ios.about": "Info",
    "ios.analyze_game": "Partie analysieren",
    "ios.assistance": "Hilfen",
    "ios.chess_board": "Schachbrett",
    "ios.board_orientation": "%1$s unten",
    "ios.compare_review": "Jeden gespielten Zug mit der Offline-Engine in voller Stärke vergleichen.",
    "ios.data": "Daten",
    "ios.duration_3": "3 Minuten",
    "ios.duration_10": "10 Minuten",
    "ios.duration_30": "30 Minuten",
    "ios.game_review": "Partieanalyse",
    "ios.games": "Partien",
    "ios.losses": "Niederlagen",
    "ios.options_local_storage_notice": "Wird lokal von Drawless Chess gespeichert und nicht an BB_Games gesendet. Je nach Einstellungen deines Apple-Geräts kann das iCloud-Backup diese Einstellungen enthalten.",
    "ios.offline_opponent": "Offline-Gegner",
    "ios.presentation": "Darstellung",
    "ios.quick_play_opponent": "Schnellspiel-Gegner",
    "ios.review_again": "Erneut analysieren",
    "ios.review_game": "Partie analysieren",
    "ios.review_my_mistakes": "Meine Fehler ansehen",
    "ios.statistics_local_notice": "Die Statistik bleibt auf diesem Gerät und wird nach Ende einer Partie aktualisiert.",
    "ios.stored_only": "Nur auf diesem Gerät gespeichert",
    "ios.threat_score_notice": "Die Bedrohungsanzeige gilt als Hilfe und verringert die Punktzahl eines Sieges.",
    "ios.version": "Version",
    "ios.you": "Du",
    "ios.starting_game": "Partie wird gestartet",
    "ios.moves_placeholder": "Die Züge erscheinen hier",
    "Preparing game review": "Spielanalyse wird vorbereitet",
    "Matching the analysis completed while you played.": "Die während des Spiels abgeschlossene Analyse wird abgeglichen.",
    "Checking the positions that still need analysis.": "Positionen, die noch analysiert werden müssen, werden geprüft.",
    "Moving %1$s": "%1$s wird bewegt",
    "Opponent move animation": "Animation des gegnerischen Zugs",
    "Opponent moved from %1$s to %2$s": "Der Gegner zog von %1$s nach %2$s",
    "ios.view_source": "Zugehörigen Quellcode anzeigen",
    "ios.privacy_contact": "Datenschutzkontakt",
    "ios.license_body": "Drawless Chess ist unter der GNU GPL Version 3 oder höher lizenziert. Die Engine auf dem Gerät ist eine modifizierte Version von Fairy-Stockfish, das von Stockfish abgeleitet ist und unter GPL-3.0-or-later steht. Die Audioaufnahmen umfassen CC0-Schachaufnahmen von JJTaynos und mh2o, CC0-Feuerwerk von Rudmer_Rotteveel sowie MIT-lizenzierte ion.sound-Aufnahmen von Denis Ineshin. Der genaue zugehörige Quellcode und Hinweise zu Drittanbietern begleiten diese Version.",
    "ios.privacy_body": "Drawless Chess funktioniert vollständig offline. BB_Games erhebt, überträgt, teilt oder verkauft keine personenbezogenen Daten. Gespeicherte Partien, der Verlauf abgeschlossener Partien, lokale Statistiken und Einstellungen werden auf diesem Gerät gespeichert und können gemäß deinen Apple-Einstellungen in Geräte- oder iCloud-Backups enthalten sein; BB_Games kann nicht auf diese Backups zugreifen. Datenschutzfragen: support@drawlesschess.com",
    "ios.quick_play_with": "Schnellspiel gegen %@",
    "ios.about_elo": "· etwa %ld Elo",
    "ios.approximate_elo": "Etwa %ld Elo",
    "ios.increment": "Inkrement: %ld Sekunden",
    "ios.try_move": "Versuche %@",
    "ios.retry_opponent": "Erneut gegen %@",
    "ios.score": "Punktzahl %ld / %ld",
    "ios.penalty_hints": "Hinweise: −%ld",
    "ios.penalty_undos": "Rücknahmen: −%ld",
    "ios.penalty_pauses": "Pausen mit Uhr: −%ld",
    "ios.penalty_threat": "Bedrohungsanzeige: −%ld",
    "ios.review_failures": "%ld Züge analysiert, %ld Engine-Fehler",
    "ios.review_matches": "%ld von %ld Zügen entsprachen der ersten Wahl der Engine",
    "ios.review_match": "Übereinstimmung",
    "ios.review_unavailable": "nicht verfügbar",
    "ios.review_engine": "Engine",
    "ios.opponent_record": "%ld Partien · %ld–%ld",
    "ios.opponent_summary": "%.1f%% Siege · Ø %.1f",
    "ios.status_thinking": "%@ denkt nach",
    "ios.status_you_won": "Du hast gewonnen",
    "ios.status_you_lost": "Du hast verloren",
    "ios.status_complete": "Partie beendet",
    "ios.piece_on_square": "%@ %@ auf %@",
    "ios.empty_square": "Leeres Feld %@",
    "ios.name_epithet": "%@, %@",
    "Player": "Spieler",
    "1 completed game": "1 abgeschlossene Partie",
    "%1$d completed games": "%1$d abgeschlossene Partien",
    "%1$s, %2$s opponent": "%1$s, Gegnerstufe %2$s",
    "Opponent, %1$s, %2$s": "Gegner, %1$s, %2$s",
  },
  fr: {
    "ios.about": "À propos",
    "ios.analyze_game": "Analyser la partie",
    "ios.assistance": "Assistance",
    "ios.chess_board": "Échiquier",
    "ios.board_orientation": "%1$s en bas",
    "ios.compare_review": "Comparer chaque coup joué avec le premier choix du moteur hors ligne à pleine puissance.",
    "ios.data": "Données",
    "ios.duration_3": "3 minutes",
    "ios.duration_10": "10 minutes",
    "ios.duration_30": "30 minutes",
    "ios.game_review": "Analyse de la partie",
    "ios.games": "Parties",
    "ios.losses": "Défaites",
    "ios.options_local_storage_notice": "Enregistré localement par Drawless Chess et non envoyé à BB_Games. La sauvegarde iCloud peut inclure ces réglages selon les réglages de votre appareil Apple.",
    "ios.offline_opponent": "Adversaire hors ligne",
    "ios.presentation": "Présentation",
    "ios.quick_play_opponent": "Adversaire de partie rapide",
    "ios.review_again": "Analyser à nouveau",
    "ios.review_game": "Analyser la partie",
    "ios.review_my_mistakes": "Revoir mes erreurs",
    "ios.statistics_local_notice": "Les statistiques restent sur cet appareil et sont enregistrées à la fin d’une partie.",
    "ios.stored_only": "Stocké uniquement sur cet appareil",
    "ios.threat_score_notice": "L’indication des menaces est une aide et réduit le score d’une victoire.",
    "ios.version": "Version",
    "ios.you": "Vous",
    "ios.starting_game": "Démarrage de la partie",
    "ios.moves_placeholder": "Les coups apparaîtront ici",
    "Preparing game review": "Préparation de l’analyse de la partie",
    "Matching the analysis completed while you played.": "Mise en correspondance de l’analyse effectuée pendant votre partie.",
    "Checking the positions that still need analysis.": "Vérification des positions qui nécessitent encore une analyse.",
    "Moving %1$s": "Déplacement de %1$s",
    "Opponent move animation": "Animation du coup adverse",
    "Opponent moved from %1$s to %2$s": "L’adversaire a joué de %1$s à %2$s",
    "ios.view_source": "Afficher le code source correspondant",
    "ios.privacy_contact": "Contact confidentialité",
    "ios.license_body": "Drawless Chess est distribué sous licence GNU GPL version 3 ou ultérieure. Son moteur exécuté sur l’appareil est une version modifiée de Fairy-Stockfish, dérivée de Stockfish et distribuée sous GPL-3.0-or-later. Les sons comprennent des enregistrements d’échecs CC0 de JJTaynos et mh2o, des feux d’artifice CC0 de Rudmer_Rotteveel et des enregistrements ion.sound de Denis Ineshin sous licence MIT. Le code source correspondant exact et les avis de tiers accompagnent cette version.",
    "ios.privacy_body": "Drawless Chess fonctionne entièrement hors ligne. BB_Games ne collecte, ne transmet, ne partage et ne vend aucune donnée personnelle. Les parties sauvegardées, l’historique des parties terminées, les statistiques locales et les réglages sont stockés sur cet appareil et peuvent être inclus dans les sauvegardes de l’appareil ou iCloud selon vos réglages Apple ; BB_Games ne peut pas accéder à ces sauvegardes. Questions de confidentialité : support@drawlesschess.com",
    "ios.quick_play_with": "Partie rapide contre %@",
    "ios.about_elo": "· environ %ld Elo",
    "ios.approximate_elo": "Environ %ld Elo",
    "ios.increment": "Incrément : %ld secondes",
    "ios.try_move": "Essayez %@",
    "ios.retry_opponent": "Réessayer contre %@",
    "ios.score": "Score %ld / %ld",
    "ios.penalty_hints": "Indices : −%ld",
    "ios.penalty_undos": "Annulations : −%ld",
    "ios.penalty_pauses": "Pauses chronométrées : −%ld",
    "ios.penalty_threat": "Indication des menaces : −%ld",
    "ios.review_failures": "%ld coups analysés, avec %ld échecs du moteur",
    "ios.review_matches": "%ld coups sur %ld correspondaient au premier choix du moteur",
    "ios.review_match": "identique",
    "ios.review_unavailable": "indisponible",
    "ios.review_engine": "moteur",
    "ios.opponent_record": "%ld parties · %ld–%ld",
    "ios.opponent_summary": "%.1f%% de victoires · Moy. %.1f",
    "ios.status_thinking": "%@ réfléchit",
    "ios.status_you_won": "Vous avez gagné",
    "ios.status_you_lost": "Vous avez perdu",
    "ios.status_complete": "Partie terminée",
    "ios.piece_on_square": "%@ %@ en %@",
    "ios.empty_square": "Case %@ vide",
    "ios.name_epithet": "%@, %@",
    "Player": "Joueur",
    "1 completed game": "1 partie terminée",
    "%1$d completed games": "%1$d parties terminées",
    "%1$s, %2$s opponent": "%1$s, adversaire %2$s",
    "Opponent, %1$s, %2$s": "Adversaire, %1$s, %2$s",
  },
  "es-419": {
    "ios.about": "Acerca de",
    "ios.analyze_game": "Analizar partida",
    "ios.assistance": "Asistencia",
    "ios.chess_board": "Tablero de ajedrez",
    "ios.board_orientation": "%1$s abajo",
    "ios.compare_review": "Compara cada jugada con la primera opción del motor sin conexión a máxima potencia.",
    "ios.data": "Datos",
    "ios.duration_3": "3 minutos",
    "ios.duration_10": "10 minutos",
    "ios.duration_30": "30 minutos",
    "ios.game_review": "Análisis de la partida",
    "ios.games": "Partidas",
    "ios.losses": "Derrotas",
    "ios.options_local_storage_notice": "Drawless Chess guarda estos datos localmente y no los envía a BB_Games. Las copias de seguridad de iCloud pueden incluir estos ajustes según la configuración de tu dispositivo Apple.",
    "ios.offline_opponent": "Oponente sin conexión",
    "ios.presentation": "Presentación",
    "ios.quick_play_opponent": "Oponente de partida rápida",
    "ios.review_again": "Analizar de nuevo",
    "ios.review_game": "Analizar partida",
    "ios.review_my_mistakes": "Revisar mis errores",
    "ios.statistics_local_notice": "Las estadísticas permanecen en este dispositivo y se registran al terminar una partida.",
    "ios.stored_only": "Guardado solo en este dispositivo",
    "ios.threat_score_notice": "La indicación de amenazas es una ayuda y reduce la puntuación de una victoria.",
    "ios.version": "Versión",
    "ios.you": "Tú",
    "ios.starting_game": "Iniciando partida",
    "ios.moves_placeholder": "Las jugadas aparecerán aquí",
    "Preparing game review": "Preparando el análisis de la partida",
    "Matching the analysis completed while you played.": "Comparando el análisis completado mientras jugabas.",
    "Checking the positions that still need analysis.": "Revisando las posiciones que aún necesitan análisis.",
    "Moving %1$s": "Moviendo %1$s",
    "Opponent move animation": "Animación del movimiento del oponente",
    "Opponent moved from %1$s to %2$s": "El oponente movió de %1$s a %2$s",
    "ios.view_source": "Ver código fuente correspondiente",
    "ios.privacy_contact": "Contacto de privacidad",
    "ios.license_body": "Drawless Chess se distribuye bajo la licencia GNU GPL versión 3 o posterior. Su motor en el dispositivo es una versión modificada de Fairy-Stockfish, derivada de Stockfish y distribuida bajo GPL-3.0-or-later. El audio incluye grabaciones de ajedrez CC0 de JJTaynos y mh2o, fuegos artificiales CC0 de Rudmer_Rotteveel y grabaciones ion.sound de Denis Ineshin con licencia MIT. El código fuente correspondiente exacto y los avisos de terceros acompañan a esta versión.",
    "ios.privacy_body": "Drawless Chess funciona completamente sin conexión. BB_Games no recopila, transmite, comparte ni vende datos personales. Las partidas guardadas, el historial de partidas terminadas, las estadísticas locales y la configuración se almacenan en este dispositivo y pueden incluirse en copias de seguridad del dispositivo o de iCloud según tu configuración de Apple; BB_Games no puede acceder a esas copias. Preguntas de privacidad: support@drawlesschess.com",
    "ios.quick_play_with": "Partida rápida contra %@",
    "ios.about_elo": "· cerca de %ld Elo",
    "ios.approximate_elo": "Aproximadamente %ld Elo",
    "ios.increment": "Incremento: %ld segundos",
    "ios.try_move": "Prueba %@",
    "ios.retry_opponent": "Reintentar contra %@",
    "ios.score": "Puntuación %ld / %ld",
    "ios.penalty_hints": "Pistas: −%ld",
    "ios.penalty_undos": "Jugadas deshechas: −%ld",
    "ios.penalty_pauses": "Pausas con reloj: −%ld",
    "ios.penalty_threat": "Indicación de amenazas: −%ld",
    "ios.review_failures": "%ld jugadas analizadas con %ld fallos del motor",
    "ios.review_matches": "%ld de %ld jugadas coincidieron con la primera opción del motor",
    "ios.review_match": "coincide",
    "ios.review_unavailable": "no disponible",
    "ios.review_engine": "motor",
    "ios.opponent_record": "%ld partidas · %ld–%ld",
    "ios.opponent_summary": "%.1f%% victorias · Prom. %.1f",
    "ios.status_thinking": "%@ está pensando",
    "ios.status_you_won": "Ganaste",
    "ios.status_you_lost": "Perdiste",
    "ios.status_complete": "Partida terminada",
    "ios.piece_on_square": "%@ %@ en %@",
    "ios.empty_square": "Casilla %@ vacía",
    "ios.name_epithet": "%@, %@",
    "Player": "Jugador",
    "1 completed game": "1 partida terminada",
    "%1$d completed games": "%1$d partidas terminadas",
    "%1$s, %2$s opponent": "%1$s, oponente %2$s",
    "Opponent, %1$s, %2$s": "Oponente, %1$s, %2$s",
  },
  "pt-BR": {
    "ios.about": "Sobre",
    "ios.analyze_game": "Analisar partida",
    "ios.assistance": "Assistência",
    "ios.chess_board": "Tabuleiro de xadrez",
    "ios.board_orientation": "%1$s embaixo",
    "ios.compare_review": "Compare cada lance jogado com a primeira escolha do motor off-line em força máxima.",
    "ios.data": "Dados",
    "ios.duration_3": "3 minutos",
    "ios.duration_10": "10 minutos",
    "ios.duration_30": "30 minutos",
    "ios.game_review": "Análise da partida",
    "ios.games": "Partidas",
    "ios.losses": "Derrotas",
    "ios.options_local_storage_notice": "Salvo localmente pelo Drawless Chess e não enviado à BB_Games. O backup do iCloud pode incluir estas configurações de acordo com os ajustes do seu dispositivo Apple.",
    "ios.offline_opponent": "Adversário off-line",
    "ios.presentation": "Apresentação",
    "ios.quick_play_opponent": "Adversário da Partida rápida",
    "ios.review_again": "Analisar novamente",
    "ios.review_game": "Analisar partida",
    "ios.review_my_mistakes": "Revisar meus erros",
    "ios.statistics_local_notice": "As estatísticas permanecem neste dispositivo e são registradas quando a partida termina.",
    "ios.stored_only": "Armazenado somente neste dispositivo",
    "ios.threat_score_notice": "A indicação de ameaças é uma ajuda e reduz a pontuação de uma vitória.",
    "ios.version": "Versão",
    "ios.you": "Você",
    "ios.starting_game": "Iniciando partida",
    "ios.moves_placeholder": "Os lances aparecerão aqui",
    "Preparing game review": "Preparando a análise da partida",
    "Matching the analysis completed while you played.": "Comparando a análise concluída enquanto você jogava.",
    "Checking the positions that still need analysis.": "Verificando as posições que ainda precisam de análise.",
    "Moving %1$s": "Movendo %1$s",
    "Opponent move animation": "Animação da jogada do adversário",
    "Opponent moved from %1$s to %2$s": "O adversário moveu de %1$s para %2$s",
    "ios.view_source": "Ver código-fonte correspondente",
    "ios.privacy_contact": "Contato de privacidade",
    "ios.license_body": "Drawless Chess é licenciado sob a GNU GPL versão 3 ou posterior. O motor executado no aparelho é uma versão modificada do Fairy-Stockfish, derivado do Stockfish e distribuído sob GPL-3.0-or-later. O áudio amostrado inclui gravações de xadrez CC0 de JJTaynos e mh2o, fogos de artifício CC0 de Rudmer_Rotteveel e gravações ion.sound de Denis Ineshin sob licença MIT. O código-fonte correspondente exato e os avisos de terceiros acompanham esta versão.",
    "ios.privacy_body": "Drawless Chess funciona totalmente off-line. A BB_Games não coleta, transmite, compartilha nem vende dados pessoais. Partidas salvas, histórico de partidas concluídas, estatísticas locais e configurações ficam armazenados neste dispositivo e podem ser incluídos em backups do dispositivo ou do iCloud conforme os ajustes da Apple; a BB_Games não pode acessar esses backups. Dúvidas sobre privacidade: support@drawlesschess.com",
    "ios.quick_play_with": "Partida rápida contra %@",
    "ios.about_elo": "· cerca de %ld Elo",
    "ios.approximate_elo": "Aproximadamente %ld Elo",
    "ios.increment": "Incremento: %ld segundos",
    "ios.try_move": "Tente %@",
    "ios.retry_opponent": "Tentar novamente contra %@",
    "ios.score": "Pontuação %ld / %ld",
    "ios.penalty_hints": "Dicas: −%ld",
    "ios.penalty_undos": "Lances desfeitos: −%ld",
    "ios.penalty_pauses": "Pausas com relógio: −%ld",
    "ios.penalty_threat": "Indicação de ameaças: −%ld",
    "ios.review_failures": "%ld lances analisados com %ld falhas do motor",
    "ios.review_matches": "%ld de %ld lances corresponderam à primeira escolha do motor",
    "ios.review_match": "corresponde",
    "ios.review_unavailable": "indisponível",
    "ios.review_engine": "motor",
    "ios.opponent_record": "%ld partidas · %ld–%ld",
    "ios.opponent_summary": "%.1f%% vitórias · Média %.1f",
    "ios.status_thinking": "%@ está pensando",
    "ios.status_you_won": "Você venceu",
    "ios.status_you_lost": "Você perdeu",
    "ios.status_complete": "Partida concluída",
    "ios.piece_on_square": "%@ %@ em %@",
    "ios.empty_square": "Casa %@ vazia",
    "ios.name_epithet": "%@, %@",
    "Player": "Jogador",
    "1 completed game": "1 partida concluída",
    "%1$d completed games": "%1$d partidas concluídas",
    "%1$s, %2$s opponent": "%1$s, oponente %2$s",
    "Opponent, %1$s, %2$s": "Adversário, %1$s, %2$s",
  },
};

function decodeAndroid(value) {
  return value
    .replace(/<[^>]+>/g, "")
    .replaceAll("&amp;", "&")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("\\'", "'")
    .replaceAll("\\n", "\n")
    .trim();
}

function readStrings(directory) {
  const file = path.join(android, directory, "strings.xml");
  const source = fs.readFileSync(file, "utf8");
  const result = new Map();
  const pattern = /<string\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g;
  for (const match of source.matchAll(pattern)) {
    result.set(match[1], decodeAndroid(match[2]));
  }
  return result;
}

function quote(value) {
  return value
    .replaceAll("\\", "\\\\")
    .replaceAll('"', '\\"')
    .replaceAll("\n", "\\n");
}

function appleFormat(value) {
  return value
    .replace(/%(\d+)\$s/g, "%$1$@")
    .replace(/%s/g, "%@");
}

function uppercaseFirst(value, locale) {
  const [first, ...rest] = [...value];
  return `${first.toLocaleUpperCase(locale)}${rest.join("")}`;
}

const english = readStrings("values");
for (const [locale, directory] of locales) {
  const translated = locale === "en" ? english : readStrings(directory);
  const table = new Map();
  for (const [name, englishValue] of english) {
    if (!englishValue) continue;
    table.set(englishValue, translated.get(name) || englishValue);
  }
  for (const name of semanticOnlyNames) {
    const englishValue = english.get(name);
    if (englishValue) table.delete(englishValue);
  }
  for (const [alias, name] of aliases) {
    const fallback = english.get(name);
    if (fallback) table.set(alias, appleFormat(translated.get(name) || fallback));
  }
  const supplement = iosSupplement[locale];
  if (!supplement) throw new Error(`Missing iOS supplement for ${locale}`);
  const expectedKeys = Object.keys(iosSupplement.en).sort();
  const actualKeys = Object.keys(supplement).sort();
  if (expectedKeys.join("\0") !== actualKeys.join("\0")) {
    throw new Error(`iOS supplement keys differ for ${locale}`);
  }
  for (const [key, value] of Object.entries(supplement)) table.set(key, value);

  const englishTagline = english.get("brand_tagline");
  const translatedTagline = translated.get("brand_tagline") || englishTagline;
  table.set(`DRAWLESS CHESS. ${englishTagline}`, `DRAWLESS CHESS. ${translatedTagline}`);

  const englishClockMinutes = english.get("clock_minutes");
  const translatedClockMinutes = translated.get("clock_minutes") || englishClockMinutes;
  for (const minutes of [3, 5, 10]) {
    table.set(
      englishClockMinutes.replace("%1$d", minutes),
      translatedClockMinutes.replace("%1$d", minutes),
    );
  }

  for (const name of ["piece_queen", "piece_rook", "piece_bishop", "piece_knight"]) {
    const englishPiece = english.get(name);
    const translatedPiece = translated.get(name) || englishPiece;
    table.set(uppercaseFirst(englishPiece, "en"), uppercaseFirst(translatedPiece, locale));
  }

  table.set("Review game", supplement["ios.review_game"]);
  table.set("Review my mistakes", supplement["ios.review_my_mistakes"]);

  const directoryPath = path.join(output, `${locale}.lproj`);
  fs.mkdirSync(directoryPath, { recursive: true });
  const sortedEntries = [...table.entries()]
    .filter(([key]) => !placedKeys.has(key))
    .sort(([left], [right]) => left.localeCompare(right));
  const orderedEntries = [];
  for (const entry of sortedEntries) {
    orderedEntries.push(entry);
    for (const key of placementGroups.get(entry[0]) || []) {
      const value = table.get(key);
      if (value === undefined) throw new Error(`Missing placed iOS key ${key} for ${locale}`);
      orderedEntries.push([key, value]);
    }
  }
  if (orderedEntries.length !== table.size) {
    throw new Error(`Not every iOS key was placed for ${locale}`);
  }
  const body = orderedEntries
    .map(([key, value]) => `"${quote(key)}" = "${quote(value)}";`)
    .join("\n");
  fs.writeFileSync(path.join(directoryPath, "Localizable.strings"), `${body}\n`, "utf8");
}

console.log("Synced five iOS localizations from the Android source catalog.");
