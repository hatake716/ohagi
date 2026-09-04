package io.github.hatake716.ohagi.data

/**
 * Keep previews at a resolution close to their actual display size. A 12dp folder
 * preview does not need the former 96px minimum. Round up so the new tiers never
 * reduce resolution below the requested size (the existing 192px cap is retained).
 */
internal fun iconRenderSize(requestedSizePx: Int): Int = when {
    requestedSizePx <= 32 -> 32
    requestedSizePx <= 48 -> 48
    requestedSizePx <= 72 -> 72
    requestedSizePx <= 96 -> 96
    requestedSizePx <= 144 -> 144
    else -> 192
}
