package com.arhand.ui

/**
 * Controls which panel (if any) is shown above the bottom toolbar.
 * SCAN and FREEFORM are no longer primary modes — they are accessed via the Calibrate overlay.
 */
enum class WorkflowMode { IDLE, STREAM, RECORD, SETTINGS }
