#!/usr/bin/env python3
"""Fix the third-party MobileI2V converter to match official trained weights.

The official hybrid_371.pth checkpoint has no trained cross-attention layers,
so the export must not insert randomly initialized layers. Image conditioning
uses the source frame; free-form text-motion conditioning is unsupported.
Restore the official architecture, guide-frame mask and strict weight checking.
"""
from pathlib import Path
import sys

def patch(model_path: Path, converter_path: Path) -> None:
    source = model_path.read_text(encoding="utf-8")
    for name in ("class SanaBlock_cross(nn.Module):", "class SanaBlock_vanila(nn.Module):"):
        if name not in source:
            raise RuntimeError("Upstream architecture changed: " + name)
    # First-frame mask: original sampling sets 1 for the guide-image spatial
    # tokens and 0 for the remaining frames. An all-ones mask corrupts sampling.
    wrapper_marker = "class MobileditONNXWrapper(nn.Module):"
    idx = source.index(wrapper_marker)
    a = source[:idx]
    b = source[idx:]
    old = "        cond_mask = torch.ones(B, device=latent.device, dtype=latent.dtype)"
    new = (
        "        spatial = latent.shape[3] * latent.shape[4]\n"
        "        cond_mask = torch.zeros(B, latent.shape[2] * spatial, device=latent.device, dtype=latent.dtype)\n"
        "        cond_mask[:, :spatial] = 1.0"
    )
    if b.count(old) != 1:
        raise RuntimeError("Unexpected wrapper mask; review upstream before updating")
    b = b.replace(old, new)
    source = a + b
    old = "        cond_mask = cond_mask.unsqueeze(1).repeat(1, num_patches)"
    new = (
        "        if cond_mask.ndim == 1:\n"
        "            cond_mask = cond_mask.unsqueeze(1).repeat(1, num_patches)\n"
        "        assert cond_mask.shape[1] == num_patches, (cond_mask.shape, num_patches)"
    )
    if source.count(old) != 1:
        raise RuntimeError("Unexpected model conditioning mask implementation")
    source = source.replace(old, new)
    model_path.write_text(source, encoding="utf-8")

    converter = converter_path.read_text(encoding="utf-8")
    # Match the official MobileI2V_300M_img512.yaml training architecture.
    # The third-party converter incorrectly defaulted to 'flash', omitting
    # the trained linear-attention + GLUMBConv parameters from the checkpoint.
    wrong = "model = mobiledit_300m_P1_D16(attn_type='flash')"
    correct = (
        "model = mobiledit_300m_P1_D16("
        "attn_type='linear', linear_head_dim=32, ffn_type='glumbconv', "
        "mlp_ratio=2.5, mlp_acts=('silu', 'silu', None), "
        "qk_norm=True, y_norm=True, y_norm_scale_factor=0.01, use_pe=False)"
    )
    if converter.count(wrong) != 1:
        raise RuntimeError("Upstream model constructor changed; review official training configuration")
    converter = converter.replace(wrong, correct)

    # TorchScript tracing freezes RoPE temporal/spatial grids: the original
    # 17x32x32 ONNX graph cannot run with 17x16x16 phone latents even when
    # dynamic_axes claims it can. Export at precisely 17x16x16 for 512px
    # source photos through the 32x-spatial LTX video VAE.
    source_shape = "latent = torch.randn(1, 128, 17, 32, 32, device=device, dtype=torch_dtype)"
    target_shape = "latent = torch.randn(1, 128, 17, 16, 16, device=device, dtype=torch_dtype)"
    if converter.count(source_shape) != 1:
        raise RuntimeError("Upstream export input resolution changed")
    converter = converter.replace(source_shape, target_shape)

    verify_shape = "latent_verify = torch.randn(1, 128, 1, 8, 8, device='cpu', dtype=torch.float32)"
    new_verify_shape = "latent_verify = torch.randn(1, 128, 17, 16, 16, device='cpu', dtype=torch.float32)"
    if converter.count(verify_shape) != 1:
        raise RuntimeError("Upstream model verification shape changed")
    converter = converter.replace(verify_shape, new_verify_shape)
    # Do not claim arbitrary dynamic spatial support when RoPE coordinates
    # were actually traced with constant 16x16 token positions.
    converter = converter.replace(
        "'latent': {0: 'batch', 3: 'height', 4: 'width'},",
        "'latent': {0: 'batch'},")
    converter = converter.replace(
        "'denoised_latent': {0: 'batch', 3: 'height', 4: 'width'},",
        "'denoised_latent': {0: 'batch'},")

    old = '    if missing:\n        logger.warning("Missing keys (not in checkpoint): %s", missing)'
    new = (
        '    if missing:\n'
        '        untrained = [k for k in missing if k not in ("pos_embed", "pos_embed_temporal")]\n'
        '        if untrained:\n'
        '            raise RuntimeError("Untrained model weights: " + repr(untrained[:12]))\n'
        '        logger.warning("Only unused positional buffers missing: %s", missing)'
    )
    if converter.count(old) != 1:
        raise RuntimeError("Unexpected checkpoint loader; review before updating")
    converter_path.write_text(converter.replace(old, new), encoding="utf-8")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python scripts/patch_mobilei2v_converter.py mobilei2v-converter/scripts/convert")
    root = Path(sys.argv[1])
    patch(root / "models" / "mobiledit.py", root / "convert_mobilei2v_unet.py")
    print("Preserved checkpoint weights and traced 17x16x16 RoPE grids for 512px Android input")
