package com.arhand.tracking

/**
 * G4 — Recognized gesture vocabulary.
 *
 * Each gesture is defined by a human-readable [label], a single Unicode [symbol]
 * for compact HUD display, and a [description] for settings/accessibility.
 *
 * The classifier maps smoothed landmark geometry to one of these values, or
 * returns null when no gesture is confidently recognized.
 *
 * App shortcut mapping (user-configurable — see [GestureClassifier.shortcuts]):
 *   FIST          → start scan
 *   PEACE         → switch camera
 *   OPEN_PALM     → (reserved / configurable)
 *   THUMBS_UP     → (reserved / configurable)
 *   POINT_UP      → (reserved / configurable)
 *   OK            → (reserved / configurable)
 *   ROCK          → toggle torch
 *   CALL          → toggle OSC stream
 */
enum class Gesture(
    val label: String,
    val symbol: String,
    val description: String
) {
    OPEN_PALM  ("Open Palm",   "✋", "All five fingers extended and spread"),
    FIST       ("Fist",        "✊", "All five fingers fully curled"),
    PEACE      ("Peace",       "✌", "Index and middle extended, others curled"),
    THUMBS_UP  ("Thumbs Up",   "👍", "Thumb extended upward, fingers curled"),
    POINT_UP   ("Point Up",    "☝", "Index finger extended, others curled"),
    OK         ("OK",          "👌", "Thumb and index touching, others extended"),
    ROCK       ("Rock",        "🤘", "Index and pinky extended, others curled"),
    CALL       ("Call",        "🤙", "Thumb and pinky extended, others curled"),
}
