#!/usr/bin/env python
# 把设备上「读不出、只靠形状稳定」落盘的 unknown-*.bin 渲染成 PNG，供人工认领。
#
# 用法:
#   python tools/render_unknown_templates.py <从设备拉下来的目录> [--out 输出目录]
#
# 背景：字模缺失与 OCR 失败会互相锁死——某张卡没有字模时只能靠 ML Kit 硬读，
# 而单字卡名（如 S16 的「烬」）在商店里最容易读不出，于是「必须有名字」的采集路径
# 永远采不到它（六份真机日志里「烬」出现 0 次）。TemplateCollector.observeUnread()
# 于是改成「只靠形状稳定」把这种格子存成 unknown-<哈希>.bin，名字留空。
#
# 本脚本把这些掩码渲染成 PNG 并排拼图，人工看出是哪个字之后，
# 用 `--name <哈希>=<卡名>` 的方式重命名，再交给 import_learned_templates.py 合并。
from __future__ import annotations

import argparse
import glob
import os

NAME_W, NAME_H = 274, 55
SCALE = 4


def render(path):
    from PIL import Image
    data = open(path, 'rb').read()
    if len(data) != NAME_W * NAME_H:
        return None, f"尺寸不符（{len(data)} != {NAME_W*NAME_H}）"
    image = Image.new('L', (NAME_W, NAME_H), 0)
    pixels = image.load()
    ink = 0
    for y in range(NAME_H):
        row = y * NAME_W
        for x in range(NAME_W):
            if data[row + x]:
                pixels[x, y] = 255
                ink += 1
    box = image.getbbox()
    if not box:
        return None, '空白掩码'
    cropped = image.crop(box).resize(
        ((box[2] - box[0]) * SCALE, (box[3] - box[1]) * SCALE), Image.NEAREST)
    return cropped, f"墨迹{ink} bbox{box}"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', help='从设备拉下来的 templates-learned 目录')
    parser.add_argument('--out', default='unknown-templates', help='输出目录')
    args = parser.parse_args()

    try:
        from PIL import Image, ImageDraw
    except ImportError:
        raise SystemExit('需要 Pillow：pip install pillow')

    files = sorted(glob.glob(os.path.join(args.directory, 'unknown-*.bin')))
    if not files:
        print('没有 unknown-*.bin；说明还没有采集到「读不出」的格子。')
        return

    os.makedirs(args.out, exist_ok=True)
    tiles = []
    for path in files:
        name = os.path.basename(path)[:-4]
        image, note = render(path)
        print(f"{name}: {note}")
        if image is None:
            continue
        tiles.append((name, image))

    if not tiles:
        print('全部无效，未生成拼图。')
        return

    width = max(t[1].width for t in tiles) + 240
    height = sum(t[1].height + 34 for t in tiles)
    sheet = Image.new('L', (width, height), 0)
    draw = ImageDraw.Draw(sheet)
    y = 0
    for name, image in tiles:
        draw.text((6, y + 8), name, fill=220)
        sheet.paste(image, (230, y + 22))
        y += image.height + 34
    target = os.path.join(args.out, 'unknown_sheet.png')
    sheet.save(target)
    print(f"\n拼图已保存：{target}")
    print(f"共 {len(tiles)} 个待认领字模。认出字形后用 --name 重命名再合并：")
    print("  python tools/import_learned_templates.py <目录> --dir templates-pad --set 16")


if __name__ == '__main__':
    main()
