# -*- coding: utf-8 -*-
"""
IFRNet → ONNX 导出脚本（PadLink AI 插帧原型，v0.27.0）。

做的事：
1. 加载 IFRNet（标准版，Vimeo90K 权重，MIT 协议）
2. 把 F.grid_sample 换成"Clamp+Floor+Gather 双线性"的功能等价实现 ——
   GridSample 不在 NNAPI 算子表里，直接导出会让整图在 CPU/GPU 之间来回切；
   拆成 Gather/加减乘后全图都能走 NNAPI（Adreno GPU）
3. 导出固定 1x3x540x960 输入的 ONNX（t=0.5 中间帧；1080p 的 1/2）
4. 用 onnxruntime 做数值对齐校验（与 PyTorch 原实现逐像素比对）
"""
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "IFRNet")
sys.path.insert(0, REPO)

import numpy as np
import torch
import torch.nn.functional as F

import models.IFRNet as M

W, H = 960, 540
OUT_ONNX = os.path.join(HERE, "ifrnet_vimeo90k_960x540.onnx")
PTH = os.path.join(HERE, "IFRNet_Vimeo90K.pth")


def warp_decomp(img, flow):
    """
    grid_sample(bilinear, border, align_corners=True) 的功能等价拆解（NNAPI 友好）。

    注意 grid_sample 的语义：输出尺寸跟 grid 走（= flow 尺寸 Hh×Ww），
    采样坐标 [-1,1] 按 **img 自己的尺寸** 映射到像素。IFRNet 里确实存在
    flow 与 img 尺寸不一致的调用（如 136×240 的 flow 采 135×240 的特征图），
    所以下面坐标一律映射到 img 尺寸、输出 reshape 成 flow 尺寸。
    """
    B, _, Hh, Ww = flow.shape
    Ci, Hi, Wi = img.shape[1], img.shape[2], img.shape[3]
    xx = torch.linspace(-1.0, 1.0, Ww).view(1, 1, 1, Ww).expand(B, -1, Hh, -1)
    yy = torch.linspace(-1.0, 1.0, Hh).view(1, 1, Hh, 1).expand(B, -1, -1, Ww)
    grid = torch.cat([xx, yy], 1)
    flow_ = torch.cat([flow[:, 0:1] / ((Ww - 1.0) / 2.0), flow[:, 1:2] / ((Hh - 1.0) / 2.0)], 1)
    g = (grid + flow_).permute(0, 2, 3, 1)          # [B,Hh,Ww,2] 归一化坐标（align_corners=True）
    px = (g[..., 0] + 1.0) * 0.5 * (Wi - 1.0)       # 归一化坐标 → img 像素坐标
    py = (g[..., 1] + 1.0) * 0.5 * (Hi - 1.0)
    px = px.clamp(0.0, Wi - 1.0)                    # padding_mode='border'
    py = py.clamp(0.0, Hi - 1.0)
    # 索引运算全部在 float 域做（索引值 < 2^24 可精确表示），只在 Gather 前 cast 一次：
    # 导出后整条索引链就只剩纯 Cast 节点，后处理统一把 INT64 改 INT32 时
    # 不会碰上"int64 标量常量与 int32 张量类型不匹配"的问题
    x0f = torch.floor(px)
    y0f = torch.floor(py)
    x1f = torch.clamp(x0f + 1.0, max=float(Wi - 1))
    y1f = torch.clamp(y0f + 1.0, max=float(Hi - 1))
    wx = (px - x0f).unsqueeze(1)                     # [B,1,Hh,Ww] 与四角采样值广播融合
    wy = (py - y0f).unsqueeze(1)
    # 摊平成 [N,1] 走 index_select：导出成 ONNX Gather(axis=0)，正是 NNAPI 的 GATHER。
    # torch.gather 会被导出器映射成 GatherElements，NNAPI 算子表里没有，整块子图退 CPU
    flat = img.reshape(B * Ci * Hi * Wi, 1)
    # 每个 (batch, 通道) 平面在摊平缓冲里的基址偏移，与 base 相加即全局像素下标
    ch_off = torch.arange(B * Ci, dtype=img.dtype).view(B, Ci, 1, 1) * float(Hi * Wi)

    def gather_at(xf, yf):
        base = yf * float(Wi) + xf                    # [B,Hh,Ww]
        gidx = (base + ch_off).to(torch.int64).reshape(-1)   # [B*Ci*Hh*Ww]，行序 (b,c,y,x)
        return torch.index_select(flat, 0, gidx).view(B, Ci, Hh, Ww)

    v00 = gather_at(x0f, y0f)
    v01 = gather_at(x1f, y0f)
    v10 = gather_at(x0f, y1f)
    v11 = gather_at(x1f, y1f)
    top = v00 * (1.0 - wx) + v01 * wx
    bot = v10 * (1.0 - wx) + v11 * wx
    return top * (1.0 - wy) + bot * wy


def resize_fixed(x, scale_factor):
    """scale=1.0 时是恒等（原实现 F.interpolate 同值），别导出无意义的 Resize 节点"""
    if scale_factor == 1.0:
        return x
    return F.interpolate(x, scale_factor=scale_factor, mode="bilinear", align_corners=False)


# 猴子补丁：模型内部所有 warp/resize 走等价实现
M.warp = warp_decomp
M.resize = resize_fixed

# 原始 ResBlock.forward 留底（校验 0 用它对比改写是否逐位等价）
_orig_resblock_forward = M.ResBlock.forward


def resblock_forward(self, x):
    """ResBlock.forward 的功能等价重写。

    原实现（IFRNet/models/IFRNet.py）在 forward 里做 in-place 切片赋值
    `out[:, -side_channels:, :, :] = ...`，经典 ONNX 导出器把这种 index_put_
    分解成 ScatterND + 一堆索引网格算子 —— NNAPI 算子表里没有 ScatterND，
    整个 ResBlock 会退回 CPU。改成 cat 重组，数值与原实现完全一致。"""
    sc = self.side_channels
    out = self.conv1(x)
    out = torch.cat([out[:, :-sc, :, :], self.conv2(out[:, -sc:, :, :])], dim=1)
    out = self.conv3(out)
    out = torch.cat([out[:, :-sc, :, :], self.conv4(out[:, -sc:, :, :])], dim=1)
    return self.prelu(x + self.conv5(out))


M.ResBlock.forward = resblock_forward


class ExportWrapper(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, img0, img1, embt):
        # t 固定语义由 embt 输入承载（App 恒传 0.5 做 2x），scale_factor 恒 1.0
        return self.m.inference(img0, img1, embt, scale_factor=1.0)


def main():
    torch.manual_seed(0)
    model = M.Model().eval()
    sd = torch.load(PTH, map_location="cpu")
    model.load_state_dict(sd)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"模型加载完成: 参数量 {n_params/1e6:.2f}M")

    wrapper = ExportWrapper(model).eval()

    img0 = torch.rand(1, 3, H, W)
    img1 = torch.rand(1, 3, H, W)
    embt = torch.tensor(0.5).view(1, 1, 1, 1)

    # ---- 数值校验 0：ResBlock 的 cat 改写 vs 原始切片赋值实现 ----
    rb = M.ResBlock(64, 16).eval()
    xt = torch.rand(2, 64, 33, 45)     # 奇数尺寸 + batch2，覆盖边界
    with torch.no_grad():
        M.ResBlock.forward = _orig_resblock_forward
        r1 = rb(xt)
        M.ResBlock.forward = resblock_forward
        r2 = rb(xt)
    d0 = (r1 - r2).abs().max().item()
    print(f"[校验] ResBlock cat 改写 vs 原实现 最大误差 = {d0:.2e}")
    assert d0 < 1e-6, "ResBlock 改写与原实现不一致"

    # ---- 数值校验 1：拆解 warp vs 原 grid_sample ----
    flow = (torch.rand(1, 2, H, W) - 0.5) * 40.0
    ref = F.grid_sample(img0, _grid_of(flow, H, W), mode="bilinear", padding_mode="border", align_corners=True)
    got = warp_decomp(img0, flow)
    d = (ref - got).abs().max().item()
    print(f"[校验] warp 拆解 vs grid_sample 最大误差 = {d:.2e}")
    assert d < 2e-6, "warp 拆解与 grid_sample 不一致"

    # ---- 校验 1b：flow 与 img 尺寸不一致的场景（IFRNet decoder 里真实存在） ----
    flow2 = (torch.rand(1, 2, H + 2, W) - 0.5) * 40.0
    ref2 = F.grid_sample(img0, _grid_of(flow2, H + 2, W), mode="bilinear", padding_mode="border", align_corners=True)
    got2 = warp_decomp(img0, flow2)
    d1b = (ref2 - got2).abs().max().item()
    print(f"[校验] warp 拆解（尺寸不匹配场景）最大误差 = {d1b:.2e}")
    assert d1b < 2e-6, "尺寸不匹配场景的 warp 拆解不一致"

    # ---- 导出 ONNX ----
    # 用经典 TorchScript 导出器（dynamo=False）：新 dynamo 导出器会把 torch.gather
    # 拆成 GatherElements+ScatterND 等一堆 NNAPI 不认识的算子；经典导出器直接映射 Gather
    torch.onnx.export(
        wrapper,
        (img0, img1, embt),
        OUT_ONNX,
        input_names=["img0", "img1", "embt"],
        output_names=["imgt"],
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    print(f"[导出] {OUT_ONNX}  ({os.path.getsize(OUT_ONNX)/1e6:.1f} MB)")

    # ---- 导出后处理 1：Gather 索引链 INT64 → INT32（NNAPI 算子表只有 INT32 索引） ----
    # warp 拆解里的 floor→cast→加减乘→Gather 是全图唯一的 int64 链（5 处 warp × x/y 各 1 条）。
    # ONNX 的 Gather 允许 int32 索引；保持 int64 会让 NNAPI 把 5 个 warp 子图全退回 CPU，
    # 特征图在 GPU/CPU 之间来回搬运，推理会慢到没法用。
    import onnx
    from onnx import TensorProto

    m = onnx.load(OUT_ONNX)
    converted = 0
    for node in m.graph.node:
        if node.op_type == "Cast":
            for attr in node.attribute:
                if attr.name == "to" and attr.i == TensorProto.INT64:
                    attr.i = TensorProto.INT32
                    converted += 1
    print(f"[后处理] Cast(INT64→INT32) 索引链 {converted} 条")
    assert converted > 0, "没找到索引 Cast 链，warp 拆解可能没生效"
    # 导出器把中间张量的类型声明（INT64）也写进了 value_info，改 Cast 后就过期了，
    # ORT 加载会报类型不匹配 —— 整体剥掉（value_info 是可选信息，运行时自己会推断）
    del m.graph.value_info[:]

    # ---- 导出后处理 2：double 常量 → float32 ----
    # Python 浮点字面量（1.0 / 0.5 / float(Wi-1) 等）在追踪时被导出器标成 F64 常量
    # （217 个），ONNX 类型提升会把整个网络中间张量抬成 double，PRelu 这类
    # "T 绑定单一类型"的算子直接报 Type Error。网络本身就是 float32，
    # 常量降精度不改变数值语义。
    from onnx import numpy_helper

    f64_fixed = 0

    def _to_f32(t):
        nonlocal f64_fixed
        f64_fixed += 1
        return numpy_helper.from_array(numpy_helper.to_array(t).astype(np.float32), name=t.name)

    for node in m.graph.node:
        if node.op_type == "Constant":
            for attr in node.attribute:
                if attr.name == "value" and attr.t.data_type == TensorProto.DOUBLE:
                    attr.t.CopyFrom(_to_f32(attr.t))
    for i, init in enumerate(m.graph.initializer):
        if init.data_type == TensorProto.DOUBLE:
            m.graph.initializer[i].CopyFrom(_to_f32(init))
    print(f"[后处理] double 常量降精度 float32: {f64_fixed} 个")

    # 同源问题另一半：标量运算还插了显式 Cast(x → double) 再和 double 标量算 ——
    # 一并改成 Cast(x → float)，让整条链回到 float32
    d2f = 0
    for node in m.graph.node:
        if node.op_type == "Cast":
            for attr in node.attribute:
                if attr.name == "to" and attr.i == TensorProto.DOUBLE:
                    attr.i = TensorProto.FLOAT
                    d2f += 1
    print(f"[后处理] Cast(double→float32) {d2f} 条")

    # 图的输出声明（imgt）也是按追踪时的 double 写的，一并改 float32
    io_fixed = 0
    for vi in list(m.graph.input) + list(m.graph.output):
        tt = vi.type
        if tt.HasField("tensor_type") and tt.tensor_type.elem_type == TensorProto.DOUBLE:
            tt.tensor_type.elem_type = TensorProto.FLOAT
            io_fixed += 1
    print(f"[后处理] 图输入/输出声明 double→float32: {io_fixed} 条")

    onnx.checker.check_model(m)
    # 权重合并成单文件（外部权重在 Android assets 里不好用）
    onnx.save_model(m, OUT_ONNX, save_as_external_data=False)
    if os.path.exists(OUT_ONNX + ".data"):
        os.remove(OUT_ONNX + ".data")
    # 算子清单：确认没有 GridSample/ScatterND/GatherElements（NNAPI 不支持，会退 CPU）
    from collections import Counter
    ops = Counter(n.op_type for n in m.graph.node)
    print(f"[后处理] 算子清单: {dict(ops)}")
    for bad in ("GridSample", "ScatterND", "GatherElements", "ScatterElements"):
        assert bad not in ops, f"图里还有 {bad}，NNAPI 会退回 CPU"
    assert "Gather" in ops, "预期的 Gather（index_select）没出现，warp 拆解可能没生效"

    # ---- 数值校验 2：ONNX vs PyTorch ----
    import onnxruntime as ort

    sess = ort.InferenceSession(OUT_ONNX, providers=["CPUExecutionProvider"])
    a = np.random.rand(1, 3, H, W).astype(np.float32)
    b = np.random.rand(1, 3, H, W).astype(np.float32)
    e = np.array([0.5], dtype=np.float32).reshape(1, 1, 1, 1)
    with torch.no_grad():
        pt = wrapper(torch.from_numpy(a), torch.from_numpy(b), torch.from_numpy(e)).numpy()
    onnx_out = sess.run(["imgt"], {"img0": a, "img1": b, "embt": e})[0]
    d2 = np.abs(pt - onnx_out).max()
    print(f"[校验] ONNX vs PyTorch 最大误差 = {d2:.2e}")
    assert d2 < 1e-3, "ONNX 输出与 PyTorch 不一致"

    # ---- 用官方样例图做一次端到端 sanity（有 pillow 才跑） ----
    try:
        from PIL import Image

        p0 = os.path.join(REPO, "figures", "img0.png")
        p1 = os.path.join(REPO, "figures", "img1.png")
        if os.path.exists(p0) and os.path.exists(p1):
            def load(p):
                im = Image.open(p).convert("RGB").resize((W, H), Image.LANCZOS)
                return np.asarray(im, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
            sa, sb = load(p0), load(p1)
            out = sess.run(["imgt"], {"img0": sa, "img1": sb, "embt": e})[0]
            mid = (out[0].transpose(1, 2, 0) * 255).clip(0, 255).astype(np.uint8)
            Image.fromarray(mid).save(os.path.join(HERE, "sanity_mid.png"))
            print(f"[校验] 官方样例中间帧已保存 sanity_mid.png（目视检查用）")
    except ImportError:
        print("[校验] 无 pillow，跳过样例图 sanity")

    print("全部校验通过 ✔")


def _grid_of(flow, Hh, Ww):
    """只用于校验：复现 utils.warp 的 grid 构造"""
    B = flow.shape[0]
    xx = torch.linspace(-1.0, 1.0, Ww).view(1, 1, 1, Ww).expand(B, -1, Hh, -1)
    yy = torch.linspace(-1.0, 1.0, Hh).view(1, 1, Hh, 1).expand(B, -1, -1, Ww)
    grid = torch.cat([xx, yy], 1)
    flow_ = torch.cat([flow[:, 0:1] / ((Ww - 1.0) / 2.0), flow[:, 1:2] / ((Hh - 1.0) / 2.0)], 1)
    return (grid + flow_).permute(0, 2, 3, 1)


if __name__ == "__main__":
    main()
