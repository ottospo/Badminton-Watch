package com.ottospo.tripcompanion;

/** Start-centered viewport for a trail, with a 1000-unit initial square. */
final class TrailViewport {
    private static final float INITIAL_SPAN_M = 1000f;
    private static final float MIN_ZOOM = 0.0001f;
    private static final float MAX_ZOOM = 1000f;

    private float zoom = 1f;
    private float centerX;
    private float centerY;
    private boolean automatic = true;

    void reset() {
        zoom = 1f;
        centerX = 0f;
        centerY = 0f;
        automatic = true;
    }

    void fit(float width, float height, float margin, float maxX, float maxY) {
        if (!automatic || width <= 0f || height <= 0f) return;
        float base = Math.min(width, height) / INITIAL_SPAN_M;
        float availableWidth = Math.max(1f, width - 2f * margin);
        float availableHeight = Math.max(1f, height - 2f * margin);
        if (maxX > 0f) zoom = Math.min(zoom, availableWidth / (2f * maxX * base));
        if (maxY > 0f) zoom = Math.min(zoom, availableHeight / (2f * maxY * base));
    }

    float scale(float width, float height) {
        return Math.min(width, height) / INITIAL_SPAN_M * zoom;
    }

    float screenX(float x, float width, float height) {
        return width / 2f + (x - centerX) * scale(width, height);
    }

    float screenY(float y, float width, float height) {
        return height / 2f - (y - centerY) * scale(width, height);
    }

    void pan(float dx, float dy, float width, float height) {
        float scale = scale(width, height);
        if (scale <= 0f) return;
        centerX -= dx / scale;
        centerY += dy / scale;
        automatic = false;
    }

    void zoom(float factor, float focusX, float focusY, float width, float height) {
        float oldScale = scale(width, height);
        if (oldScale <= 0f || factor <= 0f || Float.isNaN(factor)) return;
        float worldX = centerX + (focusX - width / 2f) / oldScale;
        float worldY = centerY - (focusY - height / 2f) / oldScale;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        float newScale = scale(width, height);
        centerX = worldX - (focusX - width / 2f) / newScale;
        centerY = worldY + (focusY - height / 2f) / newScale;
        automatic = false;
    }
}
