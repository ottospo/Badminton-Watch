#pragma once

#include <math.h>

class TrailViewport {
public:
    void reset()
    {
        scale_ = MAP_SIZE / INITIAL_SPAN_M;
        center_x_ = 0.0f;
        center_y_ = 0.0f;
        automatic_ = true;
    }

    void fit(float max_x, float max_y)
    {
        if (!automatic_) return;
        float extent = fmaxf(max_x, max_y);
        if (extent > 0.0f) {
            scale_ = fminf(scale_, (MAP_SIZE - 2.0f * MAP_MARGIN) / (2.0f * extent));
        }
    }

    void pan(float dx, float dy)
    {
        center_x_ -= dx / scale_;
        center_y_ += dy / scale_;
        automatic_ = false;
    }

    void zoom(float factor, float focus_x, float focus_y)
    {
        float world_x = center_x_ + (focus_x - MAP_SIZE / 2.0f) / scale_;
        float world_y = center_y_ - (focus_y - MAP_SIZE / 2.0f) / scale_;
        scale_ = fmaxf(MIN_SCALE, fminf(MAX_SCALE, scale_ * factor));
        center_x_ = world_x - (focus_x - MAP_SIZE / 2.0f) / scale_;
        center_y_ = world_y + (focus_y - MAP_SIZE / 2.0f) / scale_;
        automatic_ = false;
    }

    void project(float x, float y, float &px, float &py) const
    {
        px = MAP_SIZE / 2.0f + (x - center_x_) * scale_;
        py = MAP_SIZE / 2.0f - (y - center_y_) * scale_;
    }

    static bool clip_line(float &x1, float &y1, float &x2, float &y2)
    {
        float dx = x2 - x1, dy = y2 - y1;
        float first = 0.0f, last = 1.0f;
        if (!clip_edge(-dx, x1 + 2.0f, first, last)
                || !clip_edge(dx, MAP_SIZE + 2.0f - x1, first, last)
                || !clip_edge(-dy, y1 + 2.0f, first, last)
                || !clip_edge(dy, MAP_SIZE + 2.0f - y1, first, last)) return false;
        x2 = x1 + last * dx;
        y2 = y1 + last * dy;
        x1 += first * dx;
        y1 += first * dy;
        return true;
    }

private:
    static bool clip_edge(float p, float q, float &first, float &last)
    {
        if (p == 0.0f) return q >= 0.0f;
        float t = q / p;
        if (p < 0.0f) {
            if (t > last) return false;
            first = fmaxf(first, t);
        } else {
            if (t < first) return false;
            last = fminf(last, t);
        }
        return true;
    }

    static constexpr float MAP_SIZE = 240.0f;
    static constexpr float MAP_MARGIN = 14.0f;
    static constexpr float INITIAL_SPAN_M = 1000.0f;
    static constexpr float MIN_SCALE = 0.000024f;
    static constexpr float MAX_SCALE = 240.0f;
    float scale_ = MAP_SIZE / INITIAL_SPAN_M;
    float center_x_ = 0.0f;
    float center_y_ = 0.0f;
    bool automatic_ = true;
};
