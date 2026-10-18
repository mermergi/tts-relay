/*
 * CosyVoice v3.5 Flash —— 阿里云百炼（DashScope）非实时语音合成插件
 *
 * 接口：POST https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer
 * 文档：https://help.aliyun.com/zh/model-studio/cosyvoice-tts-http-api
 *
 * 这个模型没有系统音色：只能用它「声音复刻 / 声音设计」出来的音色（voice_id）。
 * 音色列表是插件自己去百炼的 list_voice 接口拉的，拉不到时可以在「手动音色 ID」里填一个。
 */

var MODEL = 'cosyvoice-v3.5-flash';
var SAMPLE_RATE = 24000;
var AUDIO_FORMAT = 'mp3';          // mp3 / wav / pcm / opus；mp3 体积最小，播放端都认
var PAGE_SIZE = 100;               // list_voice 一页多少条
var MAX_PAGES = 10;                // 最多翻几页（一个账号上限 1000 个音色）

/* 目标语言提示：locale -> language_hints。v3.5-flash 支持这些语种。 */
var LANG_HINTS = {
    'zh-CN': 'zh',
    'en-US': 'en',
    'fr-FR': 'fr',
    'de-DE': 'de',
    'ja-JP': 'ja',
    'ko-KR': 'ko',
    'ru-RU': 'ru',
    'pt-PT': 'pt',
    'th-TH': 'th',
    'id-ID': 'id',
    'vi-VN': 'vi'
};

var LANG_ORDER = ['zh-CN', 'en-US', 'ja-JP', 'ko-KR', 'fr-FR', 'de-DE', 'ru-RU',
    'pt-PT', 'th-TH', 'id-ID', 'vi-VN'];

var LANG_LABELS = {
    'zh-CN': '🇨🇳 中文',
    'en-US': '🇺🇸 English',
    'ja-JP': '🇯🇵 日本語',
    'ko-KR': '🇰🇷 한국어',
    'fr-FR': '🇫🇷 Français',
    'de-DE': '🇩🇪 Deutsch',
    'ru-RU': '🇷🇺 Русский',
    'pt-PT': '🇵🇹 Português',
    'th-TH': '🇹🇭 ไทย',
    'id-ID': '🇮🇩 Indonesia',
    'vi-VN': '🇻🇳 Tiếng Việt'
};

/* 音色列表缓存：开一次选择框就翻十几页太浪费，5 分钟内复用。 */
var VOICE_CACHE = null;
var VOICE_CACHE_AT = 0;
var VOICE_CACHE_MS = 5 * 60 * 1000;

// ───────────────────────── 小工具 ─────────────────────────

function userVar(name) {
    var v = ttsrv.userVars[name];
    return (v === undefined || v === null) ? '' : String(v).trim();
}

/**
 * 接口根地址，按顺序试：
 *   ① 「接口域名」变量（填了就只用它）
 *   ② {WorkspaceId}.cn-beijing.maas.aliyuncs.com（填了业务空间 ID 时优先走专属域名）
 *   ③ dashscope.aliyuncs.com（百炼通用域名，一直能用）
 * 专属域名填错会报 BadRequest.IllegalEndpoint，所以必须留退路。
 */
function apiRoots() {
    var roots = [];
    var ep = userVar('endpoint');
    if (ep.length > 0) return [ep.replace(/\/+$/, '')];
    var ws = userVar('workspace_id').replace(/[^A-Za-z0-9_\-]/g, '');
    if (ws.length > 0) roots.push('https://' + ws + '.cn-beijing.maas.aliyuncs.com');
    roots.push('https://dashscope.aliyuncs.com');
    return roots;
}

function readAll(ins) {
    var r = new java.io.BufferedReader(new java.io.InputStreamReader(ins, 'UTF-8'));
    var text = '';
    var line;
    while ((line = r.readLine()) !== null) text += line;
    r.close();
    return text;
}

/** POST JSON，返回解析好的对象；非 2xx 就把服务端原话带出来。 */
function postJson(path, body, apikey, readTimeoutMs) {
    var roots = apiRoots();
    var lastError = null;
    for (var i = 0; i < roots.length; i++) {
        try {
            return postJsonAt(roots[i] + path, body, apikey, readTimeoutMs);
        } catch (e) {
            lastError = e;
        }
    }
    throw lastError === null ? '没有可用的接口地址' : lastError;
}

function postJsonAt(url, body, apikey, readTimeoutMs) {
    var conn = new java.net.URL(url).openConnection();
    conn.setRequestMethod('POST');
    conn.setDoOutput(true);
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(readTimeoutMs || 60000);
    conn.setRequestProperty('Authorization', 'Bearer ' + apikey);
    conn.setRequestProperty('Content-Type', 'application/json');

    var os = conn.getOutputStream();
    var w = new java.io.OutputStreamWriter(os, 'UTF-8');
    w.write(JSON.stringify(body));
    w.flush();
    w.close();

    var code = conn.getResponseCode();
    var ins = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
    var text = (ins === null) ? '' : readAll(ins);
    if (code < 200 || code >= 300) {
        throw '百炼 HTTP ' + code + '：' + (text.length > 300 ? text.substring(0, 300) : text);
    }
    try {
        return JSON.parse(text);
    } catch (e) {
        throw '百炼返回的不是 JSON：' + text.substring(0, 200);
    }
}

function clamp(x, lo, hi) {
    return x < lo ? lo : (x > hi ? hi : x);
}

/* 宿主传进来的档位是「50 = 正常」（TtsPluginEngineV2 里是 rate*50）。 */
function toRate(x) {
    var n = parseFloat(x);
    if (isNaN(n) || n <= 0) n = 50;
    return Math.round(clamp(n / 50, 0.5, 2.0) * 100) / 100;
}

function toPitch(x) {
    var n = parseFloat(x);
    if (isNaN(n) || n <= 0) n = 50;
    return Math.round(clamp(n / 50, 0.5, 2.0) * 100) / 100;
}

function toVolume(x) {
    var n = parseFloat(x);
    if (isNaN(n) || n <= 0) n = 50;
    return Math.round(clamp(n, 0, 100));
}

/** 拉账号下所有自定义音色（复刻 + 设计）。 */
function fetchVoices(apikey) {
    var now = new Date().getTime();
    if (VOICE_CACHE !== null && (now - VOICE_CACHE_AT) < VOICE_CACHE_MS) return VOICE_CACHE;

    var list = [];
    var page = 0;
    while (page < MAX_PAGES) {
        var resp = postJson('/api/v1/services/audio/tts/customization', {
            model: 'voice-enrollment',
            input: { action: 'list_voice', page_index: page, page_size: PAGE_SIZE }
        }, apikey, 20000);
        var one = (resp && resp.output && resp.output.voice_list) ? resp.output.voice_list : [];
        for (var i = 0; i < one.length; i++) list.push(one[i]);
        if (one.length < PAGE_SIZE) break;
        page++;
    }
    VOICE_CACHE = list;
    VOICE_CACHE_AT = now;
    return list;
}

/**
 * 音色显示名。百炼的 list_voice 不返回名字，只有一个 voice_id，所以按这个顺序取：
 *   ① 「音色别名」里给它起的名字（`bailian-74e42b76=妈妈`，分号分隔）
 *   ② 声音设计的 voice_prompt（创建时写的那段描述，本来就像个名字）
 *   ③ 声音复刻没有描述 → 用 voice_id 认得出的那一截：`前缀-尾巴8位`，例如 bailian-74e42b76
 */
function aliasOf(id) {
    var raw = userVar('voice_aliases');
    if (raw.length === 0) return '';
    var lines = raw.split(/[\n;；]+/);
    var tail = id.substring(id.lastIndexOf('-') + 1);
    for (var i = 0; i < lines.length; i++) {
        var line = String(lines[i]).trim();
        var eq = line.indexOf('=');
        if (eq < 0) eq = line.indexOf('＝');
        if (eq <= 0) continue;
        var key = line.substring(0, eq).trim();
        var val = line.substring(eq + 1).trim();
        if (key.length === 0 || val.length === 0) continue;
        if (id === key || tail === key || id.indexOf(key) >= 0) return val;
    }
    return '';
}

function voiceLabel(v) {
    var id = String(v.voice_id);
    var alias = aliasOf(id);
    if (alias.length > 0) return alias;

    var prompt = v.voice_prompt ? String(v.voice_prompt).replace(/[。.\s]+$/, '') : '';
    if (prompt.length > 0) return prompt;

    // voice_id 形如 {模型}-{prefix}-{唯一标识}；声音设计的中间会多一段 vd
    var core = (id.indexOf(MODEL + '-') === 0) ? id.substring(MODEL.length + 1) : id;
    if (core.indexOf('vd-') === 0) core = core.substring(3);
    var dash = core.indexOf('-');
    var prefix = dash > 0 ? core.substring(0, dash) : '';
    var tail = id.substring(id.lastIndexOf('-') + 1);
    if (tail.length > 8) tail = tail.substring(0, 8);
    return (prefix.length > 0 ? prefix + '-' : '') + tail;
}

// ───────────────────────── 合成 ─────────────────────────

var PluginJS = {
    'name': 'CosyVoice v3.5 Flash',
    'id': 'mermergi.tts.cosyvoice.v35flash',
    'author': 'tts-relay',
    'description': '百炼 CosyVoice v3.5 Flash：音色从账号自动拉取（声音复刻/声音设计），支持语速、音量、音调、目标语言与合成指令',
    'version': 1,
    'vars': {
        apikey: {
            label: 'API 密钥',
            hint: '百炼控制台 → API-KEY，形如 sk-xxxxxxxx',
            loginUrl: 'https://bailian.console.aliyun.com/?tab=model#/api-key',
            loginDesc: '在百炼控制台创建 API-KEY 后填到这里（北京地域的密钥只能用北京地域的接口）',
            binding: 'user_input'
        },
        workspace_id: {
            label: '业务空间 ID',
            hint: '可选。填了对不上会自动退回 dashscope.aliyuncs.com，一般留空即可',
            loginUrl: 'https://bailian.console.aliyun.com/',
            loginDesc: '百炼控制台右上角可以查看业务空间 ID',
            binding: 'user_input'
        },
        endpoint: {
            label: '接口域名',
            hint: '可选。完整根地址，留空自动（北京用 dashscope.aliyuncs.com；国际站可填 https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com）',
            binding: 'user_input'
        },
        voice_id: {
            label: '手动音色 ID',
            hint: '可选。音色列表拉不到（没填密钥/接口不通）时，把 voice_id 直接填这儿',
            binding: 'user_input'
        },
        instruction: {
            label: '合成指令',
            hint: '可选。控制方言、情感、角色，例如「用四川话说」「语气温柔一点」',
            binding: 'user_input'
        },
        voice_aliases: {
            label: '音色别名',
            hint: '可选。百炼不返回音色名，只能用 ID 认；在这儿起名，分号分隔，例如 bailian-74e42b76=妈妈; bailian-c7f0c0ec=御姐',
            binding: 'user_input'
        }
    },

    'getAudio': function (text, locale, voice, speed, volume, pitch) {
        var apikey = userVar('apikey');
        if (apikey.length === 0) {
            throw '请先在插件配置里填「API 密钥」（百炼控制台 → API-KEY）';
        }
        var t = String(text === undefined || text === null ? '' : text).trim();
        if (t.length === 0) throw '空文本，不合成';

        var voiceId = String(voice === undefined || voice === null ? '' : voice).trim();
        if (voiceId.length === 0) voiceId = userVar('voice_id');
        if (voiceId.length === 0) {
            throw '没有音色：cosyvoice-v3.5-flash 没有系统音色，先到百炼控制台做「声音复刻/声音设计」，再回来在「声音」里选';
        }

        var input = {
            text: t,
            voice: voiceId,
            format: AUDIO_FORMAT,
            sample_rate: SAMPLE_RATE
        };
        var rate = toRate(speed);
        if (rate !== 1) input.rate = rate;
        var vol = toVolume(volume);
        if (vol !== 50) input.volume = vol;
        var pit = toPitch(pitch);
        if (pit !== 1) input.pitch = pit;

        var hint = LANG_HINTS[locale];
        if (hint) input.language_hints = [hint];

        var instr = userVar('instruction');
        if (instr.length > 0) input.instruction = instr;

        var resp = postJson('/api/v1/services/audio/tts/SpeechSynthesizer',
            { model: MODEL, input: input }, apikey, 120000);

        var audio = (resp && resp.output && resp.output.audio) ? resp.output.audio : null;
        if (audio === null) {
            throw '百炼没返回音频：' + JSON.stringify(resp).substring(0, 200);
        }
        // 非流式一般给一个 24 小时有效的 url；流式才会把 base64 塞在 data 里。
        if (audio.url) {
            var bytes = ttsrv.httpGetBytes(String(audio.url));
            if (bytes === null || bytes.length < 100) throw '音频下载失败：' + audio.url;
            return bytes;
        }
        if (audio.data) {
            var b64 = String(audio.data);
            return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
        }
        throw '百炼返回里既没有 url 也没有 data';
    }
};

// ───────────────────────── 界面 ─────────────────────────

var EditorJS = {
    'getAudioSampleRate': function (locale, voice) {
        return SAMPLE_RATE;
    },

    'getLocales': function () {
        var m = new java.util.LinkedHashMap();
        for (var i = 0; i < LANG_ORDER.length; i++) {
            var code = LANG_ORDER[i];
            m.put(code, LANG_LABELS[code] || code);
        }
        return m;
    },

    'getVoices': function (locale) {
        var out = {};

        var manual = userVar('voice_id');
        if (manual.length > 0) out[manual] = manual + '（手动填入）';

        var apikey = userVar('apikey');
        if (apikey.length === 0) return out;

        var list;
        try {
            list = fetchVoices(apikey);
        } catch (e) {
            if (typeof console !== 'undefined') console.log('拉音色列表失败：' + e);
            return out;
        }
        var matched = 0;
        for (var i = 0; i < list.length; i++) {
            var v = list[i];
            var id = v.voice_id ? String(v.voice_id) : '';
            if (id.length === 0) continue;
            // 音色创建时绑死了 target_model，用不对的模型合成会失败 —— 只留这个模型的。
            // 接口现在会返回 target_model，老接口没有就按 ID 前缀认。
            var model = v.target_model ? String(v.target_model) : '';
            if (model.length > 0) {
                if (model !== MODEL) continue;
            } else if (id.indexOf(MODEL + '-') !== 0) {
                continue;
            }
            if (v.status && v.status !== 'OK') continue;
            out[id] = voiceLabel(v);
            matched++;
        }
        // 一个都没匹配上：返回空表，用户一眼就知道是这个模型还没有音色
        if (matched === 0 && typeof console !== 'undefined') {
            console.log('账号里有 ' + list.length + ' 个自定义音色，但没有 ' + MODEL + ' 的');
        }
        return out;
    },

    'onLoadData': function () {
        // 没有要预置的数据；音色在 getVoices 里现拉。
    },

    'onVoiceChanged': function (locale, voice) {
        // 插件没有需要跟着音色变的界面。
    }
};
