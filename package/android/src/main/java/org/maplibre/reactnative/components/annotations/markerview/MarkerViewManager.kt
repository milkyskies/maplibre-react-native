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

    // Last child-list order applied via bringToFront. Used to skip the reorder pass when projected screen-Y ranking hasn't changed; bringToFront is a no-op when a view is already last, but the comparison work itself is wasted otherwise.
    private var lastTouchOrder: List<MarkerInfo> = emptyList()

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameCallbackScheduled = false

                if (isDestroyed) return

                val started = System.nanoTime()

                for (marker in markers) updateMarkerPosition(marker)

                reorderForTouchPriority()

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

    // Sort markers by screen-Y (larger Y = lower on screen = closer to the viewer when the map is pitched), then bringToFront() each in ascending order. The marker with the largest Y ends up at the end of mapView's child list, which makes it:
    //
    //   - drawn on top (default FrameLayout draw order = index order), and
    //   - the first child tested by dispatchTouchEvent (ViewGroup iterates `childCount - 1` down to 0).
    //
    // Without this, mapView's child order is whatever order markers were first registered in, so `onPress` can fire on a marker that's visually behind another. Sorting in the Choreographer frame keeps touch priority synced to the visual stack as the camera rotates / pans.
    private fun reorderForTouchPriority() {
        if (markers.size < 2) {
            lastTouchOrder = markers.toList()
            return
        }

        val sorted = markers.sortedBy { it.view.y }

        if (sorted == lastTouchOrder) return

        for (marker in sorted) marker.view.bringToFront()

        lastTouchOrder = sorted
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

        // Refresh touch priority on the next vsync; without this a marker added while the camera is idle keeps the registration-order touch priority instead of its screen-Y rank.
        scheduleFrame()

        return markerInfo
    }

    fun removeMarker(markerInfo: MarkerInfo) {
        markers.remove(markerInfo)
        mapView.removeView(markerInfo.view)
        scheduleFrame()
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

    // Iterate by the marker's anchor screen-Y, closest-to-viewer first (largest Y at bearing=0). view.y is the top of the bounding box, which conflates marker height with proximity for variable-height markers; the anchor point (latLng projected) is what actually determines proximity. Touches don't go through Android's normal dispatch chain — MLRNMapView intercepts and calls this directly, so bringToFront / translationZ / style.zIndex on the View have no effect on which marker is returned here.
    fun findMarkerAtPoint(screenPoint: PointF): MarkerInfo? {
        val candidates =
            markers.sortedByDescending { map.projection.toScreenLocation(it.latLng).y }

        for (marker in candidates) {
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
