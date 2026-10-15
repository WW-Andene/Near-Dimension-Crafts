package com.arhand.render

/**
 * 5-light model — exact match to HTML prototype lighting setup.
 *
 * Ambient   0x1a1208  intensity 4.5
 * Key       0xffe8cc  dir(1.5,  2.5,  3)    intensity 2.8
 * Rim       0x002255  dir(-2,  -1,   1)     intensity 1.4
 * SSS       0xff6030  dir(0,   -3,  -1)     intensity 0.55
 * Fill      0xffd0a0  dir(2,    1,   2)     intensity 0.5
 * Torch     0xffc850  point light at index fingertip
 */
object LightingModel {
    // Ambient
    val ambientColor   = floatArrayOf(0.102f, 0.071f, 0.031f)   // 0x1a1208
    val ambientIntensity = 4.5f

    // Key light
    val keyDir         = floatArrayOf(1.5f,  2.5f,  3.0f)
    val keyColor       = floatArrayOf(1.0f,  0.91f, 0.8f)       // 0xffe8cc
    val keyIntensity   = 2.8f

    // Rim light
    val rimDir         = floatArrayOf(-2.0f, -1.0f,  1.0f)
    val rimColor       = floatArrayOf(0.0f,  0.133f, 0.333f)    // 0x002255
    val rimIntensity   = 1.4f

    // Subsurface scattering approximation
    val sssDir         = floatArrayOf(0.0f, -3.0f, -1.0f)
    val sssColor       = floatArrayOf(1.0f,  0.376f, 0.188f)    // 0xff6030
    val sssIntensity   = 0.55f

    // Fill light
    val fillDir        = floatArrayOf(2.0f,  1.0f,  2.0f)
    val fillColor      = floatArrayOf(1.0f,  0.816f, 0.627f)    // 0xffd0a0
    val fillIntensity  = 0.5f

    // Torch — point light, position set dynamically from index fingertip landmark
    val torchColor     = floatArrayOf(1.0f,  0.784f, 0.314f)    // 0xffc850
    val torchIntensity = 1.2f
    val torchAttenuation = floatArrayOf(1.0f, 0.5f, 0.1f)       // constant, linear, quadratic
}
