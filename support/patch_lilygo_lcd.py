import re
from pathlib import Path


Import("env")


def patch_lilygo_lcd(*_args, **_kwargs):
    source_path = (
        Path(env.subst("$PROJECT_LIBDEPS_DIR"))
        / env.subst("$PIOENV")
        / "LilyGoLib"
        / "src"
        / "LilyGoDispInterface.cpp"
    )
    if not source_path.is_file():
        return

    source = source_path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"#if\s*\(ESP_ARDUINO_VERSION\s*<=\s*"
        r"ESP_ARDUINO_VERSION_VAL\(4\s*,\s*0\s*,\s*0\)\)"
        r".*?#endif\s*#endif",
        re.DOTALL,
    )
    replacement = (
        "    panel_config.color_space = ESP_LCD_COLOR_SPACE_RGB;\n"
        "    panel_config.vendor_config = nullptr;"
    )
    patched, count = pattern.subn(replacement, source, count=1)
    if count:
        source_path.write_text(patched, encoding="utf-8")


patch_lilygo_lcd()
env.AddPreAction("buildprog", patch_lilygo_lcd)