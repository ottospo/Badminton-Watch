package com.ottospo.tripcompanion;

/** Run with javac/java; no Android runtime or test dependencies required. */
public final class TrailViewportTest {
    private static void close(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.01f) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        TrailViewport viewport = new TrailViewport();
        viewport.fit(400, 300, 10, 0, 0);
        close(viewport.screenX(0, 400, 300), 200);
        close(viewport.screenY(0, 400, 300), 150);
        close(viewport.scale(400, 300), 0.3f);

        // Fit the whole outbound trail, and do not zoom in when returning home.
        viewport.fit(400, 300, 10, 2000, 1000);
        close(viewport.screenX(2000, 400, 300), 390);
        float fittedScale = viewport.scale(400, 300);
        viewport.fit(400, 300, 10, 20, 20);
        close(viewport.scale(400, 300), fittedScale);

        viewport.pan(30, -20, 400, 300);
        close(viewport.screenX(0, 400, 300), 230);
        close(viewport.screenY(0, 400, 300), 130);
        viewport.fit(400, 300, 10, 10000, 10000);
        close(viewport.scale(400, 300), fittedScale);

        // Pinching keeps the point under the fingers stationary.
        float worldX = 500f;
        float worldY = 250f;
        float focusX = viewport.screenX(worldX, 400, 300);
        float focusY = viewport.screenY(worldY, 400, 300);
        viewport.zoom(2f, focusX, focusY, 400, 300);
        close(viewport.screenX(worldX, 400, 300), focusX);
        close(viewport.screenY(worldY, 400, 300), focusY);

        viewport.reset();
        viewport.fit(400, 300, 10, 2000, 1000);
        close(viewport.screenX(0, 400, 300), 200);
        close(viewport.screenY(0, 400, 300), 150);
        close(viewport.scale(400, 300), fittedScale);
        System.out.println("TrailViewport tests passed");
    }
}
