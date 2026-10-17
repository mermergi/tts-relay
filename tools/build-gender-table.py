#!/usr/bin/env python3
"""从开源中文人名语料库算「字 / 双字」性别倾向表，生成 assets/gender_table.txt。

数据来源：wainshine/Chinese-Names-Corpus 的 Chinese_Names_Corpus_Gender（120W）.txt
          https://github.com/wainshine/Chinese-Names-Corpus   Apache-2.0
          （114 万条「名字,性别」，不是手写词表 —— 我们只是统计每个字在男/女
          名字里出现的比例，算一个对数比，当判据）

用法：
    python3 tools/build-gender-table.py <语料.txt> assets/gender_table.txt

输出格式（一行一对）：
    字or词 <TAB> 分值       分值 = ln((女_count+0.5)/(男_count+0.5))
                            > 0 偏女，< 0 偏男，接近 0 = 中性（不输出）
"""
import math
import sys
from collections import Counter


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src, out = sys.argv[1], sys.argv[2]

    uni_f, uni_m = Counter(), Counter()
    bi_f, bi_m = Counter(), Counter()
    n = 0
    with open(src, encoding='utf-8-sig') as f:
        for line in f:
            line = line.strip()
            if not line or ',' not in line:
                continue
            name, _, sex = line.partition(',')
            name = name.strip()
            sex = sex.strip()
            if len(name) < 2 or len(name) > 4:
                continue
            if sex not in ('男', '女'):
                continue
            n += 1
            fu = uni_f if sex == '女' else uni_m
            fb = bi_f if sex == '女' else bi_m
            for ch in name:
                fu[ch] += 1
            for i in range(len(name) - 1):
                fb[name[i:i + 2]] += 1

    def table(one, two):
        rows = []
        for k, cf in one.items():
            cm = two.get(k, 0)
            if cf + cm < 200:          # 太少没有统计意义
                continue
            s = math.log((cf + 0.5) / (cm + 0.5))
            if abs(s) < 0.25:
                continue
            rows.append((abs(s) * math.log(cf + cm), k, s))
        return rows

    uni_rows = table(uni_f, uni_m)
    # 双字：只在两字都不太中性时收，且次数够多
    bi_rows = []
    for k, cf in bi_f.items():
        cm = bi_m.get(k, 0)
        if cf + cm < 60 or len(k) < 2 or not all('\u4e00' <= c <= '\u9fff' for c in k):
            continue
        s = math.log((cf + 0.5) / (cm + 0.5))
        if abs(s) < 0.4:
            continue
        bi_rows.append((abs(s) * math.log(cf + cm), k, s))

    uni_rows.sort(reverse=True)
    bi_rows.sort(reverse=True)
    uni_rows = uni_rows[:4000]
    bi_rows = bi_rows[:30000]

    with open(out, 'w', encoding='utf-8') as f:
        f.write('# 由 tools/build-gender-table.py 生成，请勿手改\n')
        f.write('# 数据源：wainshine/Chinese-Names-Corpus（Apache-2.0）%d 条名字\n' % n)
        for _, k, s in uni_rows:
            f.write('%s\t%.4f\n' % (k, s))
        for _, k, s in bi_rows:
            f.write('%s\t%.4f\n' % (k, s))

    print('语料 %d 条 → 单字 %d、双字 %d，写入 %s'
          % (n, len(uni_rows), len(bi_rows), out))
    return 0


if __name__ == '__main__':
    sys.exit(main())
