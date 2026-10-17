#!/usr/bin/env python3
"""把一个目录下所有 .aar 的 res/ 并成一个 res/ 交给 aapt2。

为什么不能直接 unzip：几个库的 res/values/values.xml 是**同名文件**，直接解压
到一个目录就互相覆盖、后一个把前一个的 string/style 全冲掉（aapt2 随后会报
"removing resource ... without required default value"）。Gradle/AGP 那一步做的是
"同名 XML 合并 children"，这里照做：

  · values*/ 下的 XML：按 <标签 + name(+type)> 去重，先来的留下，后来的补进来；
  · declare-styleable：**并集**（attr 子项合起来），这样两边库里
    R.styleable.Foo_bar 的下标都还对；
  · 其它（layout/drawable/raw…）：按文件名先到先得。

用法：merge-aar-res.py <aar 目录> <输出目录>   → 结果在 <输出目录>/res
"""
import os
import shutil
import sys
import zipfile
from xml.etree import ElementTree as ET


def key_of(el):
    tag = el.tag
    name = el.get('name') or ''
    typ = el.get('type') or ''
    if tag == 'item':
        return (tag, name, typ)
    return (tag, name, '')


def merge_values(dst_path, src_bytes):
    """把 src 的 values XML children 并进 dst 文件。"""
    dst = ET.parse(dst_path)
    root = dst.getroot()
    try:
        src = ET.fromstring(src_bytes)
    except ET.ParseError:
        return 0
    have = {}
    for child in list(root):
        have.setdefault(key_of(child), child)
    added = 0
    for child in list(src):
        k = key_of(child)
        if k in have:
            # 同类资源重名：declare-styleable 取并集，其它先到先得
            if child.tag == 'declare-styleable':
                old = have[k]
                names = set()
                for a in list(old):
                    names.add(a.get('name'))
                for a in list(child):
                    if a.tag == 'attr' and a.get('name') not in names:
                        old.append(a)
                        names.add(a.get('name'))
            continue
        root.append(child)
        have[k] = child
        added += 1
    if added:
        dst.write(dst_path, encoding='utf-8', xml_declaration=True)
    return added


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    aar_dir, out = sys.argv[1], sys.argv[2]
    res = os.path.join(out, 'res')
    if os.path.isdir(res):
        shutil.rmtree(res)
    os.makedirs(res)
    aars = sorted(f for f in os.listdir(aar_dir) if f.endswith('.aar'))
    total = 0
    for aar in aars:
        try:
            z = zipfile.ZipFile(os.path.join(aar_dir, aar))
        except Exception as e:
            print('  ! 打不开 %s：%s' % (aar, e), file=sys.stderr)
            continue
        for m in z.namelist():
            if not m.startswith('res/') or m.endswith('/'):
                continue
            rel = m[len('res/'):]
            if not rel:
                continue
            dst = os.path.join(res, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            data = z.read(m)
            first = rel.split('/')[0]
            if os.path.exists(dst):
                if first.startswith('values') and rel.endswith('.xml'):
                    merge_values(dst, data)
                continue
            with open(dst, 'wb') as f:
                f.write(data)
            total += 1
    n = sum(len(fs) for _, _, fs in os.walk(res))
    print('    %d 个 aar → %d 个资源文件（新增 %d，其余为合并）' % (len(aars), n, total))
    return 0


if __name__ == '__main__':
    sys.exit(main())
