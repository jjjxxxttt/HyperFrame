#include "frame_ahb_pool.hpp"

#include "ahb_image_bridge.hpp"
#include "lsfg_render_loop.hpp"

#include <android/hardware_buffer.h>
#include <android/log.h>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

#define LOG_TAG "padlink-lsfg-pool"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace lsfg_android {

namespace {

struct PoolSlot {
    AHardwareBuffer *ahb = nullptr;
    uint32_t stride = 0;   // 像素列数（可能大于 width，拷贝按行跳）
};

struct Pool {
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<PoolSlot> slots;
};

std::mutex g_poolMu;
Pool g_pool;

} // namespace

int poolCreate(uint32_t w, uint32_t h, uint32_t count) {
    if (w == 0 || h == 0 || count == 0) return kPoolSizeMismatch;
    std::lock_guard<std::mutex> lock(g_poolMu);
    // 重建语义：先拆旧池
    for (auto &s : g_pool.slots) {
        if (s.ahb != nullptr) AHardwareBuffer_release(s.ahb);
    }
    g_pool = Pool{};
    g_pool.width = w;
    g_pool.height = h;
    g_pool.slots.resize(count);

    AHardwareBuffer_Desc desc{};
    desc.width = w;
    desc.height = h;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
               | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
               | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
               | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;

    for (uint32_t i = 0; i < count; ++i) {
        AHardwareBuffer *ahb = nullptr;
        if (AHardwareBuffer_allocate(&desc, &ahb) != 0 || ahb == nullptr) {
            LOGE("poolCreate: AHB 分配失败（%ux%u 槽 %u/%u）", w, h, i, count);
            for (auto &s : g_pool.slots) {
                if (s.ahb != nullptr) AHardwareBuffer_release(s.ahb);
            }
            g_pool = Pool{};
            return kErrAhbAllocate;
        }
        AHardwareBuffer_Desc got{};
        AHardwareBuffer_describe(ahb, &got);
        g_pool.slots[i].ahb = ahb;
        g_pool.slots[i].stride = got.stride;
    }
    LOGI("poolCreate: %ux%u RGBA8888 × %u 个槽就绪", w, h, count);
    return 0;
}

int poolSubmit(uint32_t index, const void *src, size_t srcBytes, int64_t timestampNs) {
    std::lock_guard<std::mutex> lock(g_poolMu);
    if (g_pool.slots.empty() || g_pool.width == 0) return kPoolNotCreated;
    if (index >= g_pool.slots.size()) return kPoolBadIndex;
    const size_t need = static_cast<size_t>(g_pool.width) * g_pool.height * 4u;
    if (src == nullptr || srcBytes < need) return kPoolSizeMismatch;

    PoolSlot &slot = g_pool.slots[index];
    void *ptr = nullptr;
    if (AHardwareBuffer_lock(slot.ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                            -1, nullptr, &ptr) != 0 || ptr == nullptr) {
        LOGW("poolSubmit: 槽 %u 写锁失败", index);
        return kPoolLockFailed;
    }
    const auto *s = static_cast<const uint8_t *>(src);
    auto *d = static_cast<uint8_t *>(ptr);
    const uint32_t rowBytes = g_pool.width * 4u;
    const uint32_t dstStrideBytes = slot.stride * 4u;
    if (slot.stride == g_pool.width) {
        std::memcpy(d, s, need);
    } else {
        for (uint32_t y = 0; y < g_pool.height; ++y) {
            std::memcpy(d + static_cast<size_t>(y) * dstStrideBytes,
                        s + static_cast<size_t>(y) * rowBytes,
                        rowBytes);
        }
    }
    AHardwareBuffer_unlock(slot.ahb, nullptr);

    // 渲染循环自持引用、异步消费；这里不需要额外 acquire。
    pushFrame(slot.ahb, timestampNs);
    return 0;
}

void poolDestroy() {
    std::lock_guard<std::mutex> lock(g_poolMu);
    for (auto &s : g_pool.slots) {
        if (s.ahb != nullptr) AHardwareBuffer_release(s.ahb);
    }
    g_pool = Pool{};
    LOGI("poolDestroy: 池已销毁");
}

uint32_t poolSize() {
    std::lock_guard<std::mutex> lock(g_poolMu);
    return static_cast<uint32_t>(g_pool.slots.size());
}

} // namespace lsfg_android
