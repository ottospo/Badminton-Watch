#include "../src/trail_viewport.h"
#include <assert.h>
#include <math.h>

static void near(float actual, float expected)
{
    assert(fabsf(actual - expected) < 0.02f);
}

int main()
{
    TrailViewport viewport;
    float x, y;
    viewport.project(0, 0, x, y);
    near(x, 120);
    near(y, 120);
    viewport.project(500, 500, x, y);
    near(x, 240);
    near(y, 0);

    viewport.fit(2000, 500);
    viewport.project(2000, 0, x, y);
    near(x, 226);
    viewport.fit(10, 10);
    viewport.project(2000, 0, x, y);
    near(x, 226);

    viewport.pan(10, -20);
    viewport.project(0, 0, x, y);
    near(x, 130);
    near(y, 100);
    viewport.fit(10000, 10000);
    viewport.project(2000, 0, x, y);
    near(x, 236);

    viewport.project(500, 300, x, y);
    float focus_x = x, focus_y = y;
    viewport.zoom(2.0f, focus_x, focus_y);
    viewport.project(500, 300, x, y);
    near(x, focus_x);
    near(y, focus_y);
    viewport.zoom(0.5f, focus_x, focus_y);
    viewport.project(500, 300, x, y);
    near(x, focus_x);
    near(y, focus_y);

    viewport.reset();
    viewport.fit(2000, 500);
    viewport.project(0, 0, x, y);
    near(x, 120);
    near(y, 120);
    viewport.project(2000, 0, x, y);
    near(x, 226);

    float x1 = -100, y1 = 120, x2 = 400, y2 = 120;
    assert(TrailViewport::clip_line(x1, y1, x2, y2));
    near(x1, -2);
    near(x2, 242);
    near(y1, 120);
    near(y2, 120);
    x1 = -100; y1 = -100; x2 = -10; y2 = -10;
    assert(!TrailViewport::clip_line(x1, y1, x2, y2));
    x1 = -100; y1 = -100; x2 = 340; y2 = 340;
    assert(TrailViewport::clip_line(x1, y1, x2, y2));
    near(x1, -2);
    near(y1, -2);
    near(x2, 242);
    near(y2, 242);
    return 0;
}
