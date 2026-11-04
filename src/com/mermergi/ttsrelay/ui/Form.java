package com.mermergi.ttsrelay.ui;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.mermergi.ttsrelay.Ui;

import java.util.ArrayList;
import java.util.List;

/** 引擎编辑表单：按 spec 批量铺「标签 + 输入框」。 */
public final class Form {
    private Form() { }

    public static final class Fld {
        public String key;
        public EditText box;
    }

    /** spec 每行：{配置键, 显示名, 当前值, "single"|"multi"} */
    public static List<Fld> buildForm(Context ctx, Ui ui, LinearLayout box, String[][] spec) {
        List<Fld> out = new ArrayList<Fld>();
        for (String[] row : spec) {
            boolean multi = row.length > 3 && "multi".equals(row[3]);
            box.addView(ui.label(row[1]));
            EditText e;
            if (multi) {
                e = ui.area("");
                e.setText(row[2] == null ? "" : row[2]);
                e.setMinLines(3);
                e.setMaxLines(5);          // 长内容（headers 之类）别把整屏撑爆
            } else {
                e = ui.field(null, row[2] == null ? "" : row[2], InputType.TYPE_CLASS_TEXT);
            }
            e.setTag(null);
            box.addView(e);
            Fld f = new Fld();
            f.key = row[0];
            f.box = e;
            out.add(f);
        }
        return out;
    }
}
