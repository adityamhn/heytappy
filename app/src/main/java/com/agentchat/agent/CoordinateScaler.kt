package com.agentchat.agent

/**
 * Maps coordinates from the model's image space (the downscaled screenshot's
 * pixel grid, [ScreenObserver.Observation.imageWidth]x[imageHeight]) to real
 * device pixels. Both the action executor (taps/swipes) and the guide overlay
 * (annotations) need the exact same mapping, so it lives in one place.
 */
class CoordinateScaler(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val screenWidth: Int,
    private val screenHeight: Int,
) {
    fun x(value: Int): Int =
        if (imageWidth <= 0) value
        else (value.toLong() * screenWidth / imageWidth).toInt()

    fun y(value: Int): Int =
        if (imageHeight <= 0) value
        else (value.toLong() * screenHeight / imageHeight).toInt()

    companion object {
        fun of(observation: ScreenObserver.Observation): CoordinateScaler =
            CoordinateScaler(
                imageWidth = observation.imageWidth,
                imageHeight = observation.imageHeight,
                screenWidth = observation.screenWidth,
                screenHeight = observation.screenHeight,
            )
    }
}
