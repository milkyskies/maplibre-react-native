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

    // Marker positioning runs from a UI-thread Choreographer frame so the translation lands in the HWUI composite that pairs with the GL surface's frame; running it on the GL thread queues the View-property invalidate one vsync late, which is the visible lag.
    private val choreographer: Choreographer? =
        if (Looper.myLooper() == Looper.getMainLooper()) Choreographer.getInstance() else null

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
        scheduleFrame()
    }

    companion object {
        private const val TAG = "MLRN.MarkerLag"

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
