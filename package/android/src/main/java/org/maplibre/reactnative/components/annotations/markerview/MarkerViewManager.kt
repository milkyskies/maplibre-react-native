package org.maplibre.reactnative.components.annotations.markerview

import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.View
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

class MarkerViewManager(
    private val mapView: MapView,
    private val map: MapLibreMap,
) {
    data class MarkerInfo(
        val view: MLRNMarkerViewContent,
        val markerView: MLRNMarkerView,
        var latLng: LatLng,
        var anchorX: Float = 0f,
        var anchorY: Float = 0f,
        var offsetX: Float = 0f,
        var offsetY: Float = 0f,
    )

    private val markers = mutableListOf<MarkerInfo>()
    private var isDestroyed = false

    // UI-thread frame loop. We drive marker positioning from Choreographer instead of from maplibre's GL-thread onWillStartRenderingFrame callback. The hypothesis: HWUI composites on UI-thread vsync, so writing translationX/Y here lands the update in the same frame HWUI presents — versus the GL-thread path, where the property invalidate could miss the current frame and reappear one vsync later.
    private val choreographer: Choreographer? =
        if (Looper.myLooper() == Looper.getMainLooper()) Choreographer.getInstance() else null

    // Read/written from both the GL render thread (updateMarkers → scheduleFrame) and the UI thread (Choreographer callback). @Volatile guarantees the flag-flip happens-before subsequent reads on the other thread; no atomic ops needed because the worst case of a stale read is one extra postFrameCallback, which Choreographer dedupes via the no-op when nothing has changed.
    @Volatile private var frameCallbackScheduled = false
    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCallbackScheduled = false

                if (isDestroyed) return

                val started = System.nanoTime()

                for (marker in markers) updateMarkerPosition(marker)

                if (DEBUG_LAG_LOGS) {
                    val elapsedNs = System.nanoTime() - started
                    Log.d(
                        TAG,
                        "frame=$frameTimeNanos markers=${markers.size} elapsedMs=${"%.2f".format(elapsedNs / 1_000_000.0)}",
                    )
                }

                if (cameraIsMoving) scheduleFrame()
            }
        }

    @Volatile private var cameraIsMoving = false

    private fun scheduleFrame() {
        if (frameCallbackScheduled || isDestroyed || choreographer == null) return

        frameCallbackScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun onCameraMoveStarted() {
        cameraIsMoving = true
        scheduleFrame()
    }

    fun onCameraMoveEnded() {
        cameraIsMoving = false
        // One last tick so the resting position is committed even if the camera-idle event arrived after the last vsync we serviced.
        scheduleFrame()
    }

    companion object {
        private const val TAG = "MLRN.MarkerLag"

        // Toggle when measuring; flip off before shipping. Keeps the production path clean.
        private const val DEBUG_LAG_LOGS = false
    }

    fun addMarker(
        view: MLRNMarkerViewContent,
        markerView: MLRNMarkerView,
        latLng: LatLng,
        anchorX: Float = 0f,
        anchorY: Float = 0f,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): MarkerInfo {
        val markerInfo = MarkerInfo(view, markerView, latLng, anchorX, anchorY, offsetX, offsetY)
        markers.add(markerInfo)

        if (view.parent == null) {
            mapView.clipChildren = false
            mapView.clipToPadding = false
            mapView.clipToOutline = false
            mapView.addView(view)
        }

        updateMarkerPosition(markerInfo)

        return markerInfo
    }

    fun removeMarker(markerInfo: MarkerInfo) {
        markers.remove(markerInfo)
        mapView.removeView(markerInfo.view)
    }

    fun removeMarkerByView(view: View) {
        markers.find { it.view == view }?.let { removeMarker(it) }
    }

    fun updateMarkers() {
        if (isDestroyed) return

        // Defer to the next UI-thread vsync via Choreographer so the marker translation lands in the HWUI frame that's about to compose. Calling setX/setY synchronously here (especially from the GL render thread) can mean HWUI doesn't pick up the new value until one vsync later — that's the lag.
        scheduleFrame()
    }

    private fun updateMarkerPosition(marker: MarkerInfo) {
        val view = marker.view
        val screenPos: PointF = map.projection.toScreenLocation(marker.latLng)
        val (viewWidth, viewHeight) = view.getContentSize()
        val anchorOffsetX = viewWidth * marker.anchorX
        val anchorOffsetY = viewHeight * marker.anchorY
        view.x = screenPos.x - anchorOffsetX + marker.offsetX
        view.y = screenPos.y - anchorOffsetY + marker.offsetY
    }

    fun findMarkerByView(view: View): MarkerInfo? = markers.find { it.view == view }

    fun isPointInsideMarker(screenPoint: PointF): Boolean = findMarkerAtPoint(screenPoint) != null

    fun findMarkerAtPoint(screenPoint: PointF): MarkerInfo? {
        for (marker in markers) {
            val v = marker.view
            if (v.visibility != View.VISIBLE) continue
            val (w, h) = v.getContentSize()
            val rect = RectF(v.x, v.y, v.x + w, v.y + h)
            if (rect.contains(screenPoint.x, screenPoint.y)) return marker
        }
        return null
    }

    fun updateMarkerCoordinate(
        markerInfo: MarkerInfo,
        latLng: LatLng,
    ) {
        markerInfo.latLng = latLng
        updateMarkerPosition(markerInfo)
    }

    fun updateMarkerAnchor(
        markerInfo: MarkerInfo,
        anchorX: Float,
        anchorY: Float,
    ) {
        markerInfo.anchorX = anchorX
        markerInfo.anchorY = anchorY
        updateMarkerPosition(markerInfo)
    }

    fun updateMarkerOffset(
        markerInfo: MarkerInfo,
        offsetX: Float,
        offsetY: Float,
    ) {
        markerInfo.offsetX = offsetX
        markerInfo.offsetY = offsetY
        updateMarkerPosition(markerInfo)
    }

    fun removeViews() {
        for (marker in markers) {
            mapView.removeView(marker.view)
        }
    }

    fun restoreViews() {
        for (marker in markers) {
            if (marker.view.parent == null) {
                mapView.addView(marker.view)
            }
        }
        updateMarkers()
    }

    fun onDestroy() {
        isDestroyed = true
        choreographer?.removeFrameCallback(frameCallback)
        for (marker in markers) {
            mapView.removeView(marker.view)
        }
        markers.clear()
    }
}
