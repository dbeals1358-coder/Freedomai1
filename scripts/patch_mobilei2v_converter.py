#!/usr/bin/env python3
"""Repair the third-party MobileI2V ONNX converter before exporting models.

The upstream MobileI2VConsole vendor copy dropped the trained cross-attention
layers from SanaBlock_cross and SanaBlock_vanila. With those layers missing,
text_emb is pruned and the exported model cannot follow a motion prompt.
This patch restores the architecture instead of introducing a dummy input.
We keep this patch in FreedomAI so it can be reviewed and tested independently.
"""
from pathlib import Path
import sys

def patch(model_path: Path, converter_path: Path) -> None:
    source = model_path.read_text(encoding="utf-8")
    for class_name, next_marker in (
        ("class SanaBlock_cross(nn.Module):", "class SanaBlock_vanila(nn.Module):"),
        ("class SanaBlock_vanila(nn.Module):", "# ============================================================\n# Position embedding helpers"),
    ):
        start = source.index(class_name)
        end = source.index(next_marker, start)
        section = source[start:end]
        if "self.cross_attn =" in section:
            raise RuntimeError(f"{class_name} already contains cross attention; review upstream changes")
        # Training architecture includes a separate learned text cross-attention.
        anchor = "        self.norm2 = nn.LayerNorm(hidden_size, elementwise_affine=False, eps=1e-6)"
        if section.count(anchor) != 1:
            raise RuntimeError(f"Could not patch cross-attention constructor in {class_name}")
        section = section.replace(
            anchor,
            "        self.cross_attn = MultiHeadCrossAttention(hidden_size, num_heads, qk_norm=qk_norm, **block_kwargs)\n" + anchor,
            1,
        )
        anchor = "        x = x + self.drop_path(x_s)"
        if section.count(anchor) != 1:
            raise RuntimeError(f"Could not patch cross-attention forward in {class_name}")
        section = section.replace(anchor, anchor + "\n        x = x + self.cross_attn(x, y, mask)", 1)
        source = source[:start] + section + source[end:]
    # First-frame mask: original sampling sets 1 for the guide-image spatial
    # tokens and 0 for the remaining frames. An all-ones mask corrupts sampling.
    wrapper_marker = "class MobileditONNXWrapper(nn.Module):"
    idx = source.index(wrapper_marker)
    a = source[:idx]
    b = source[idx:]
    old = "        cond_mask = torch.ones(B, device=latent.device, dtype=latent.dtype)"
    new = (
        "        spatial = (latent.shape[3] // 2) * (latent.shape[4] // 2)\n"
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
    old = '    if missing:\n        logger.warning("Missing keys (not in checkpoint): %s", missing)'
    new = (
        '    if missing:\n'
        '        cross_missing = [key for key in missing if ".cross_attn." in key]\n'
        '        if cross_missing:\n'
        '            raise RuntimeError("Checkpoint lacks trained cross-attention weights: " + repr(cross_missing[:8]))\n'
        '        logger.warning("Missing keys (not in checkpoint): %s", missing)'
    )
    if converter.count(old) != 1:
        raise RuntimeError("Unexpected checkpoint loader; review before updating")
    converter_path.write_text(converter.replace(old, new), encoding="utf-8")

if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python scripts/patch_mobilei2v_converter.py mobilei2v-converter/scripts/convert")
    root = Path(sys.argv[1])
    patch(root / "models" / "mobiledit.py", root / "convert_mobilei2v_unet.py")
    print("Restored trained cross-attention, guide-frame mask, and strict checkpoint checks")
