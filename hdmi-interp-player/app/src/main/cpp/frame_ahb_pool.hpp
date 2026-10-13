#pragma once

// 解码帧 → LSFG 的 AHardwareBuffer 喂帧池（PadLink 直连通路专用）。
//
// 背景：lsfg_render_loop 的 pushFrame() 只吃 AHardwareBuffer（RGBA8/RGBX8），
// 而 PadLink 的 MJPEG 解码产物是 JVM 直接缓冲里的 RGBA 字节。这里维护一小把
// 复用的 RGBA8888 AHB（默认 6 个轮转），每次 submit 把像素拷进下一个空位并
// 交给 pushFrame，避免 4K 每帧 33MB 的重分配风暴。
//
// 轮转安全边界：pushFrame 对入队的 AHB 自持引用、由工作线程异步消费完才放，
// 而我们没有消费完成回调。6 个槽按 60fps 轮转 ≈ 100ms 一循环，远大于工作线程
// 单帧的 import+copy 耗时（毫秒级）；配合渲染循环自带的丢最旧背压，重写到
// "仍在被读"缓冲的概率可以忽略。这是与 LSFG-Android 参考实现（ImageReader
// 每帧独立 buffer）等效的最小代价折衷。

#include <cstddef>
#include <cstdint>

namespace lsfg_android {

constexpr int kPoolNotCreated   = -50;
constexpr int kPoolBadIndex     = -51;
constexpr int kPoolSizeMismatch = -52;
constexpr int kPoolLockFailed   = -53;

// 分配 count 个 w×h 的 RGBA8888 AHB（CPU 读写 + GPU 采样/输出用途）。
// 已有池存在时先整体销毁重建。返回 0 成功，kErrAhbAllocate(-30) 失败。
int poolCreate(uint32_t w, uint32_t h, uint32_t count);

// 把 src（w*h*4 字节、行紧密排列的 RGBA）拷进 index 号槽位，并 pushFrame
// 给渲染循环（渲染循环自持引用）。srcBytes 不足返回 kPoolSizeMismatch。
int poolSubmit(uint32_t index, const void *src, size_t srcBytes, int64_t timestampNs);

// 销毁整池（幂等）。
void poolDestroy();

// 池内槽位数（0 = 未创建）。
uint32_t poolSize();

} // namespace lsfg_android
