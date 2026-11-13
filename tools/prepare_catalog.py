"""生成随包棋子目录与头像：从 dataj.cc 公开接口抓取指定赛季的可见棋子。

S18 目录（champions_s18.json）用同一规则可逐条复现，因此脚本同时用于更新与新增赛季。
默认只下载缺失的头像，重复运行不会产生多余变化。
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'https://www.dataj.cc/api/web/gamedata?setId={set_id}'
# 上游把“艾瑞莉娅”写成“艾瑞利娅”，修正后与游戏内卡名一致。
NAME_FIX = {'艾瑞利娅': '艾瑞莉娅'}


def fetch(set_id: int) -> dict:
    request = urllib.request.Request(SOURCE.format(set_id=set_id), headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode('utf-8'))
    if payload.get('code') != 200 or 'data' not in payload:
        raise RuntimeError('接口返回异常：' + str(payload.get('message')))
    return payload['data']


def catalog(heroes: list[dict]) -> list[dict]:
    """与既有 S18 目录一致的筛选：可见、1~5 费、普通棋子、基础星级 ID、按名字去重。

    费用变体统计覆盖全部同赛季条目（含 2/3 星副本），因此 2 星升 3 星导致
    同名不同费的情况也会被标记，不在 OCR 缺失费用时按目录猜测。
    """
    eligible = [h for h in heroes
                if h.get('isVisible') and h.get('heroType') == 0
                and isinstance(h.get('price'), int) and 1 <= h['price'] <= 5]
    variants: dict[str, set[int]] = {}
    for hero in eligible:
        name = NAME_FIX.get(hero['name'], hero['name'])
        variants.setdefault(name, set()).add(hero['price'])
    out: list[dict] = []
    seen: set[str] = set()
    for hero in eligible:
        hero_id = str(hero.get('id', ''))
        # 同名棋子有 2/3/4 星与多地图副本，基础 ID 以 1 开头的那条对应商店费用。
        if not hero_id.startswith('1'):
            continue
        name = NAME_FIX.get(hero['name'], hero['name'])
        if name in seen:
            continue
        seen.add(name)
        row = {'id': hero_id, 'name': name, 'price': hero['price'], 'picture': hero['picture']}
        prices = sorted(variants[name])
        if len(prices) > 1:
            # 例如 S16 的波比、伊泽瑞尔在上游同时存在多个费用版本。
            row['priceVariants'] = prices
        out.append(row)
    return out


def download(url: str, destination: Path) -> str:
    digest = hashlib.sha256(url.encode('utf-8')).hexdigest()
    if destination.is_file() and destination.stat().st_size > 0:
        return 'exists'
    request = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    if not data.startswith(b'\x89PNG'):
        raise RuntimeError('头像不是 PNG：' + url)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    return 'saved:' + digest[:8]


def main() -> int:
    parser = argparse.ArgumentParser(description='生成指定赛季的随包棋子目录与头像')
    parser.add_argument('--set', dest='set_id', type=int, required=True, help='赛季编号，例如 16 或 18')
    parser.add_argument('--out', type=Path, help='目录 JSON 输出路径，默认 app/src/main/assets/champions_s<set>.json')
    parser.add_argument('--portraits', type=Path, help='头像目录，默认 app/src/main/assets/portraits/s<set>')
    parser.add_argument('--skip-portraits', action='store_true', help='只写 JSON，不下载头像')
    parser.add_argument('--check', action='store_true', help='只校验现有文件与接口是否一致，不写文件')
    args = parser.parse_args()

    out = (args.out or ROOT / f'app/src/main/assets/champions_s{args.set_id}.json').resolve()
    portraits = (args.portraits or ROOT / f'app/src/main/assets/portraits/s{args.set_id}').resolve()
    rows = catalog(fetch(args.set_id)['hero'])
    if not rows:
        raise RuntimeError('筛选后没有棋子，接口字段可能已变化')
    if args.check:
        current = json.loads(out.read_text(encoding='utf-8'))
        problems = []
        if current.get('champions') != rows:
            problems.append('目录内容与接口不一致')
        for row in rows:
            if not (portraits / f"{row['id']}.png").is_file():
                problems.append('缺少头像 ' + row['id'])
        if problems:
            print('\n'.join(problems))
            return 1
        print(f'{out.relative_to(ROOT)}: {len(rows)} 个棋子与头像齐全')
        return 0

    counts: dict[int, int] = {}
    for row in rows:
        counts[row['price']] = counts.get(row['price'], 0) + 1
    document = {
        'setId': args.set_id,
        'source': SOURCE.format(set_id=args.set_id),
        'retrieved': datetime.date.today().isoformat(),
        'portraitDir': portraits.name,
        'champions': rows,
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{out.relative_to(ROOT)}: {len(rows)} 个棋子，费用分布 ' +
          ' / '.join(f'{cost}费 {counts.get(cost, 0)}' for cost in range(1, 6)))

    if not args.skip_portraits:
        saved = 0
        for row in rows:
            status = download(row['picture'], portraits / f"{row['id']}.png")
            if status != 'exists':
                saved += 1
        print(f'{portraits.relative_to(ROOT)}: 新下载 {saved} 张，共 {len(rows)} 张')
    return 0


if __name__ == '__main__':
    sys.exit(main())
