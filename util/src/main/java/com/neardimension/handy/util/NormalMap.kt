package com.neardimension.handy.util

interface NormalMap {
    val width: Int
    val height: Int
    val isEmpty: Boolean
    fun normalAt(x: Int, y: Int): Triple<Float, Float, Float>?
}
