package com.mermergi.ttsrelay.js

import android.content.res.AssetManager
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * sora-editor 的初始化 —— 逐行照 TTS Server 的
 * `compose/codeeditor/CodeEditorHelper.kt:initEditor()/setTheme()` 搬的：
 * 主题 JSON、JS 的 TextMate 语法都从 assets/textmate/ 读（那几份也是从它仓库原样拿的），
 * 编辑器语言挂 `source.js`，配色用 TextMateColorScheme。
 *
 * 用法：在 setText 之前调一次 [init]。
 */
object EditorSetup {

    private val THEMES = arrayOf(
        "textmate/quietlight.json",
        "textmate/solarized_drak.json",
        "textmate/darcula.json",
        "textmate/abyss.json",
    )

    @Volatile private var done = false

    /** 深色主题（app 本身是深色的），要浅色就传 "textmate/quietlight.json"。 */
    @JvmStatic
    @JvmOverloads
    fun init(assets: AssetManager, editor: CodeEditor, theme: String = "textmate/solarized_drak.json") {
        val registry = FileProviderRegistry.getInstance()
        registry.addFileProvider(AssetsFileResolver(assets))

        val themeRegistry = ThemeRegistry.getInstance()
        try {
            for (t in THEMES) {
                themeRegistry.loadTheme(
                    ThemeModel(
                        IThemeSource.fromInputStream(registry.tryGetInputStream(t), t, null)
                    )
                )
            }
        } catch (_: Throwable) {
        }

        if (!done) {
            try {
                GrammarRegistry.getInstance().loadGrammars(
                    languages {
                        language("js") {
                            grammar = "textmate/javascript/syntaxes/JavaScript.tmLanguage.json"
                            defaultScopeName()
                            languageConfiguration = "textmate/javascript/language-configuration.json"
                        }
                    }
                )
            } catch (_: Throwable) {
            }
            done = true
        }

        try {
            editor.setEditorLanguage(TextMateLanguage.create("source.js", true))
        } catch (_: Throwable) {
        }

        try {
            val id = theme.removePrefix("textmate/").removeSuffix(".json")
            themeRegistry.setTheme(id)
            editor.colorScheme = TextMateColorScheme.create(themeRegistry)
        } catch (_: Throwable) {
        }
    }
}
