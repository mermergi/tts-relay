#!/usr/bin/env python3
"""从 CC-CEDICT 生成「词 → 性别」表（assets/gender_words.txt）。

为什么用它：音色名的性别线索通常就在词的**语义**里（萝莉 = young cute girl、
一姐 = top-ranked female、御姐 = woman、正太 = cute boy、学长 = senior male student）。
CC-CEDICT 是现成的开放汉英词典，英文释义里已经写明了男女，不用我们再手写词表。

数据来源：CC-CEDICT — https://www.mdbg.net/chinese/dictionary?page=cc-cedict
          https://cc-cedict.org/  许可 CC BY-SA 4.0

用法：
    curl -O https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz
    gunzip -c cedict_1_0_ts_utf-8_mdbg.txt.gz > cedict.txt
    python3 tools/build-gender-words.py cedict.txt assets/gender_words.txt

输出（一行一对）：
    词 <TAB> 1(女) / -1(男)
"""
import re
import sys
from collections import Counter

FEMALE = {
    'she', 'her', 'hers', 'female', 'woman', 'women', 'girl', 'girls', 'lady', 'ladies',
    'sister', 'sisters', 'aunt', 'auntie', 'mother', 'mom', 'mommy', 'mum', 'mummy',
    'wife', 'girlfriend', 'daughter', 'grandmother', 'grandma', 'granny', 'princess',
    'queen', 'actress', 'madam', 'miss', 'mrs', 'ms', 'matron', 'nun', 'maiden',
    'gal', 'gals', 'chick', 'dame', 'granddaughter', 'niece', 'bride', 'widow',
    'mistress', 'countess', 'empress', 'goddess', 'heroine', 'ballerina', 'nanny',
}
MALE = {
    'he', 'him', 'his', 'male', 'man', 'men', 'boy', 'boys', 'lad', 'lads',
    'brother', 'brothers', 'uncle', 'father', 'dad', 'daddy', 'husband', 'boyfriend',
    'son', 'grandfather', 'grandpa', 'prince', 'king', 'actor', 'sir', 'mr', 'guy',
    'guys', 'gentleman', 'gentlemen', 'monk', 'fellow', 'bloke', 'dude', 'bro',
    'grandson', 'nephew', 'groom', 'widower', 'master', 'emperor', 'god', 'hero',
    'baller', 'ladiesman',
}
# 出现这些就不算（"either sex"、"man or woman" 之类）
BOTH = {'both', 'either', 'or'}
WORD = re.compile(r"[a-z]+(?:'[a-z]+)?")

# CEDICT 行：傳統 简体 [pin1 yin1] /gloss1/gloss2/
LINE = re.compile(r"^(\S+)\s+(\S+)\s+\[([^\]]*)\]\s+/(.*)/\s*$")


def gender_of_gloss(gloss):
    words = WORD.findall(gloss.lower())
    f = sum(1 for w in words if w in FEMALE)
    m = sum(1 for w in words if w in MALE)
    if f and m:
        return 0            # 释义里男女都有 → 不下结论
    if f:
        return 1
    if m:
        return -1
    return 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    src, out = sys.argv[1], sys.argv[2]

    best = {}                       # 词 → (票数, 性别)
    lines = 0
    with open(src, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#'):
                continue
            m = LINE.match(line.rstrip('\n'))
            if not m:
                continue
            lines += 1
            trad, simp, _py, gloss = m.groups()
            g = gender_of_gloss(gloss)
            if g == 0:
                continue
            for word in (simp, trad):
                if len(word) < 1 or len(word) > 4:
                    continue
                if not all('\u4e00' <= c <= '\u9fff' for c in word):
                    continue
                prev = best.get(word)
                if prev is None or (prev[1] != g):
                    cnt = 1 if prev is None else prev[0] + 1
                    best[word] = (cnt, g if prev is None or prev[1] == g else prev[1])
                else:
                    best[word] = (prev[0] + 1, g)

    # 只留「男女说法一致」的词（同一词在词典里释义互相打架就不要了）
    final = {}
    for word, (cnt, g) in best.items():
        final[word] = g

    with open(out, 'w', encoding='utf-8') as f:
        f.write('# 由 tools/build-gender-words.py 从 CC-CEDICT 生成，请勿手改\n')
        f.write('# 数据源：CC-CEDICT (CC BY-SA 4.0) —— 看了 %d 条词条\n' % lines)
        for word in sorted(final, key=lambda w: (-len(w), w)):
            f.write('%s\t%d\n' % (word, final[word]))

    print('CC-CEDICT %d 条 → 性别词 %d 个（女 %d / 男 %d），写入 %s'
          % (lines, len(final),
             sum(1 for v in final.values() if v > 0),
             sum(1 for v in final.values() if v < 0), out))
    return 0


if __name__ == '__main__':
    sys.exit(main())
