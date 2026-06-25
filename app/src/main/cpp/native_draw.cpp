#include <jni.h>
#include <android/log.h>
#include <android/bitmap.h>
#include <vector>
#include <cstring>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "paperleaf_native", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "paperleaf_native", __VA_ARGS__)

struct NativeLayer {
    int32_t id;
    float x, y, scale, rotation;
    bool visible;
    jint width, height;
    uint32_t* pixels;
};

static std::vector<NativeLayer> nativeLayers;
static int32_t nextLayerId = 1;

extern "C" {

JNIEXPORT void JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeInit(JNIEnv *env, jobject thiz) {
    nativeLayers.clear();
    nextLayerId = 1;
    LOGI("nativeInit called - layer engine ready");
}

JNIEXPORT jint JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeCreateLayer(
    JNIEnv *env, jobject thiz, jint width, jint height) {
    NativeLayer layer;
    layer.id = nextLayerId++;
    layer.x = 0; layer.y = 0;
    layer.scale = 1.0f; layer.rotation = 0.0f;
    layer.visible = true;
    layer.width = width; layer.height = height;
    layer.pixels = new uint32_t[width * height]();
    memset(layer.pixels, 0xFFF9F0, width * height * sizeof(uint32_t));
    nativeLayers.push_back(layer);
    LOGI("nativeCreateLayer id=%d w=%d h=%d", layer.id, width, height);
    return layer.id;
}

JNIEXPORT void JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeDeleteLayer(
    JNIEnv *env, jobject thiz, jint layerId) {
    for (size_t i = 0; i < nativeLayers.size(); i++) {
        if (nativeLayers[i].id == layerId) {
            delete[] nativeLayers[i].pixels;
            nativeLayers.erase(nativeLayers.begin() + i);
            LOGI("nativeDeleteLayer id=%d", layerId);
            return;
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeDuplicateLayer(
    JNIEnv *env, jobject thiz, jint layerId) {
    for (auto& nl : nativeLayers) {
        if (nl.id == layerId) {
            NativeLayer dup;
            dup.id = nextLayerId++;
            dup.x = nl.x + 10; dup.y = nl.y + 10;
            dup.scale = nl.scale; dup.rotation = nl.rotation;
            dup.visible = true;
            dup.width = nl.width; dup.height = nl.height;
            size_t pixelCount = dup.width * dup.height;
            dup.pixels = new uint32_t[pixelCount];
            memcpy(dup.pixels, nl.pixels, pixelCount * sizeof(uint32_t));
            nativeLayers.push_back(dup);
            LOGI("nativeDuplicateLayer src=%d dst=%d", layerId, dup.id);
            return dup.id;
        }
    }
    return -1;
}

JNIEXPORT void JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeMoveLayer(
    JNIEnv *env, jobject thiz, jint layerId, jint newIndex) {
    for (size_t i = 0; i < nativeLayers.size(); i++) {
        if (nativeLayers[i].id == layerId) {
            NativeLayer layer = nativeLayers[i];
            nativeLayers.erase(nativeLayers.begin() + i);
            size_t target = (newIndex < 0) ? 0 : (newIndex > (int)nativeLayers.size()) ? nativeLayers.size() : newIndex;
            nativeLayers.insert(nativeLayers.begin() + target, layer);
            LOGI("nativeMoveLayer id=%d to idx=%d", layerId, newIndex);
            return;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeSetLayerTransform(
    JNIEnv *env, jobject thiz, jint layerId, jfloat x, jfloat y, jfloat scale, jfloat rotation) {
    for (auto& nl : nativeLayers) {
        if (nl.id == layerId) {
            nl.x = x; nl.y = y; nl.scale = scale; nl.rotation = rotation;
            return;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeSetLayerVisibility(
    JNIEnv *env, jobject thiz, jint layerId, jboolean visible) {
    for (auto& nl : nativeLayers) {
        if (nl.id == layerId) {
            nl.visible = visible;
            return;
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeCompositeLayers(
    JNIEnv *env, jobject thiz, jint targetW, jint targetH) {
    size_t totalPixels = targetW * targetH;
    uint32_t* result = new uint32_t[totalPixels]();
    memset(result, 0xFFF9F0, totalPixels * sizeof(uint32_t));

    for (auto& nl : nativeLayers) {
        if (!nl.visible) continue;

        float cosA = cos(nl.rotation * M_PI / 180.0f);
        float sinA = sin(nl.rotation * M_PI / 180.0f);
        float cx = nl.width * 0.5f;
        float cy = nl.height * 0.5f;

        int startX = (int)(nl.x * nl.scale);
        int startY = (int)(nl.y * nl.scale);
        int endX = (int)((nl.x + nl.width) * nl.scale);
        int endY = (int)((nl.y + nl.height) * nl.scale);

        startX = (startX < 0) ? 0 : startX;
        startY = (startY < 0) ? 0 : startY;
        endX = (endX > targetW) ? targetW : endX;
        endY = (endY > targetH) ? targetH : endY;

        for (int sy = startY; sy < endY; sy++) {
            for (int sx = startX; sx < endX; sx++) {
                float bx = (sx / nl.scale - nl.x);
                float by = (sy / nl.scale - nl.y);

                float rx = cosA * (bx - cx) - sinA * (by - cy) + cx;
                float ry = sinA * (bx - cx) + cosA * (by - cy) + cy;

                int px = (int)rx;
                int py = (int)ry;

                if (px >= 0 && px < nl.width && py >= 0 && py < nl.height) {
                    uint32_t srcPixel = nl.pixels[py * nl.width + px];
                    if ((srcPixel & 0xFF000000) != 0) {
                        result[sy * targetW + sx] = srcPixel;
                    }
                }
            }
        }
    }

    jint resultHandle = (jint)(intptr_t)result;
    return resultHandle;
}

JNIEXPORT void JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeFreeResult(
    JNIEnv *env, jobject thiz, jint handle) {
    uint32_t* pixels = (uint32_t*)(intptr_t)handle;
    delete[] pixels;
}

JNIEXPORT jint JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeGetLayerCount(JNIEnv *env, jobject thiz) {
    return (jint)nativeLayers.size();
}

JNIEXPORT void JNICALL
Java_com_paperleaf_sketchbook_view_DrawingView_nativeClearLayers(JNIEnv *env, jobject thiz) {
    for (auto& nl : nativeLayers) {
        delete[] nl.pixels;
    }
    nativeLayers.clear();
    LOGI("nativeClearLayers");
}

} // extern "C"
