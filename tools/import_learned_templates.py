#!/usr/bin/env python
# 把设备上自举采集到的卡名字模合并进随包字模目录，并重写 manifest.txt。
#
# 用法:
#   python tools/import_learned_templates.py <从设备拉下来的目录> [--dir templates-pad]
#
# 背景：app 里的 TemplateCollector 会在玩的过程中把 OCR 成功识别的卡名攒成字模，
# 存在设备私有目录 files/templates-learned/<卡名>.bin。攒够一批后拉回本地，
# 用本脚本合并进 app/src/main/assets/<模板目录>/，重新打包即可大幅提升识别率。
#
# 合并规则（**必须遵守，否则会把错字或重复字模打进包**）：
#   1. 卡名必须在当前赛季棋池里（默认 S16，见 --set）；
#   2. 尺寸必须等于 NAME_W×NAME_H（274×55），否则跳过；
#   3. 墨迹不能太少（< 60 像素视为空/无效）；
#   4. 与已收录字模交叉相似度 ≥ 0.80 的视为重复，跳过；
#   5. 同一卡名已存在时不覆盖（随包字模优先）。
import argparse
import json
import os
import shutil
import sys

NAME_W, NAME_H = 274, 55
MIN_INK = 60
CROSS_LIMIT = 0.80
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_pool(set_id):
    path = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'champions_s%s.json' % set_id)
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    return {c['name'] for c in data['champions']}


def to_bitmap(blob):
    """15070 字节掩码 → H 行 × W 列的 bool 列表（每像素 1 字节）。"""
    return [blob[y * NAME_W:(y + 1) * NAME_W] for y in range(NAME_H)]


def score(a, b):
    """复刻 PhoneVision.score：位移容差 dx=-3..3 / dy=-2..2，取最大 IoU。"""
    if len(a) != len(b):
        return 0.0
    best = 0.0
    for dy in range(-2, 3):
        for dx in range(-3, 4):
            inter = union = 0
            for y in range(NAME_H):
                ty = y + dy
                if ty < 0 or ty >= NAME_H:
                    continue
                ra, rb = a[y], b[ty]
                for x in range(NAME_W):
                    tx = x + dx
                    if tx < 0 or tx >= NAME_W:
                        continue
                    pa = ra[x] != 0
                    pb = rb[tx] != 0
                    if pa or pb:
                        union += 1
                        if pa and pb:
                            inter += 1
            if union and inter / union > best:
                best = inter / union
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('source', help='从设备拉下来的字模目录（含 <卡名>.bin）')
    ap.add_argument('--dir', default='templates-pad', help='目标模板目录名')
    ap.add_argument('--set', default='16', help='赛季号，用于校验卡名是否在棋池')
    ap.add_argument('--dry-run', action='store_true', help='只报告，不写入')
    args = ap.parse_args()

    pool = load_pool(args.set)
    target_dir = os.path.join(ROOT, 'app', 'src', 'main', 'assets', args.dir)
    manifest_path = os.path.join(target_dir, 'manifest.txt')

    existing = {}
    if os.path.exists(manifest_path):
        with open(manifest_path, encoding='utf-8') as f:
            for line in f:
                name = line.strip()
                if name:
                    existing[name] = None

    files = sorted(f for f in os.listdir(args.source) if f.endswith('.bin'))
    added, skipped = [], []
    for fname in files:
        name = fname[:-4]
        blob = open(os.path.join(args.source, fname), 'rb').read()
        if name not in pool:
            skipped.append((name, '不在 S%s 棋池' % args.set))
            continue
        if len(blob) != NAME_W * NAME_H:
            skipped.append((name, '尺寸 %d 不是 %d' % (len(blob), NAME_W * NAME_H)))
            continue
        if sum(1 for v in blob if v) < MIN_INK:
            skipped.append((name, '墨迹过少'))
            continue
        if name in existing:
            skipped.append((name, '已存在'))
            continue
        # 与已收录字模交叉查重
        bitmap = to_bitmap(blob)
        dup = None
        for other, oblob in existing.items():
            if oblob is None:
                try:
                    oblob = open(os.path.join(target_dir, 'name%d.bin' % list(existing).index(other)), 'rb').read()
                except OSError:
                    continue
            if score(bitmap, to_bitmap(oblob)) >= CROSS_LIMIT:
                dup = other
                break
        if dup:
            skipped.append((name, '与「%s」过于相似' % dup))
            continue
        added.append((name, blob))

    print('目标目录：%s' % target_dir)
    print('待合并 %d 个，跳过 %d 个' % (len(added), len(skipped)))
    for name, reason in skipped:
        print('  跳过 %-12s %s' % (name, reason))

    if not added:
        print('没有可合并的新字模。')
        return 0
    if args.dry_run:
        print('（dry-run，未写入）将新增：%s' % ', '.join(n for n, _ in added))
        return 0

    # 现有 nameN.bin 的最大序号
    index = 0
    while os.path.exists(os.path.join(target_dir, 'name%d.bin' % index)):
        index += 1
    names = list(existing)
    for name, blob in added:
        with open(os.path.join(target_dir, 'name%d.bin' % index), 'wb') as f:
            f.write(blob)
        names.append(name)
        print('  写入 name%d.bin「%s」' % (index, name))
        index += 1
    with open(manifest_path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(names) + '\n')
    print('清单已重写，共 %d 个卡名。' % len(names))
    return 0


if __name__ == '__main__':
    sys.exit(main())
