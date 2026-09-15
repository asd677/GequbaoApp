/*
 * 歌曲宝套壳 App —— 页面注入脚本
 * ---------------------------------------------------------------------------
 * 这个文件是原生 App 和网页之间唯一的结构耦合点。
 * 站点改版时，理论上只需要改这里（选择器、window.appData 字段名）。
 *
 * 它做四件事：
 *   1. 反向控制播放：原生按钮 -> window.__gb.play/pause/seek/toggle/replay
 *   2. 正向上报状态：<audio> 的播放/进度/元信息 -> GB.onPlayerState(json)
 *   3. 外观增强：夜间模式（原生自动深色不支持时用反色兜底）、隐藏站点头部/页脚、缩放
 *   4. 连播支持：原生队列跳页后自动点一下播放（GB.consumeAutoplay()）
 */
(function () {
    'use strict';

    var GB = window.GB;
    if (!GB) {
        return;
    }

    var PUSH_GAP = 700;       // 状态上报最小间隔(ms)，进度条够用又不吵
    var lastPush = 0;
    var hookTimer = null;

    function $(id) { return document.getElementById(id); }

    function player() { return $('custom-audio-player'); }

    function appData() { return window.appData || {}; }

    function songId() {
        var m = /\/music\/(\d+)/.exec(location.pathname || '');
        return m ? m[1] : '';
    }

    function toSeconds(text) {
        if (!text) { return 0; }
        var p = String(text).split(':');
        if (p.length === 2) { return (parseInt(p[0], 10) || 0) * 60 + (parseInt(p[1], 10) || 0); }
        if (p.length === 3) {
            return (parseInt(p[0], 10) || 0) * 3600 + (parseInt(p[1], 10) || 0) * 60 + (parseInt(p[2], 10) || 0);
        }
        return parseInt(text, 10) || 0;
    }

    function pickText(sel) {
        var el = document.querySelector(sel);
        return el && el.textContent ? el.textContent.trim() : '';
    }

    function meta() {
        var d = appData();
        var a = player();
        var coverEl = document.querySelector('.player-cover-img');
        var cover = d.mp3_cover || (coverEl ? (coverEl.src || coverEl.getAttribute('src') || '') : '');
        var title = (d.mp3_title || pickText('.song-title-styled') || document.title || '').trim();
        var artist = (d.mp3_author || pickText('.song-author-styled') || '').trim();

        var pos = 0, dur = 0, playing = false, url = '';
        if (a) {
            url = a.currentSrc || a.src || '';
            if (isFinite(a.currentTime) && a.currentTime > 0) { pos = Math.round(a.currentTime); }
            if (isFinite(a.duration) && a.duration > 0) { dur = Math.round(a.duration); }
            playing = !a.paused && !a.ended;
        }
        if (!dur && d.mp3_duration) { dur = toSeconds(d.mp3_duration); }

        return {
            title: title,
            artist: artist,
            cover: cover,
            url: url,
            songId: songId(),
            pageUrl: location.href,
            position: pos,
            duration: dur,
            playing: playing
        };
    }

    function push(force) {
        var now = Date.now();
        if (!force && now - lastPush < PUSH_GAP) { return; }
        lastPush = now;
        try { GB.onPlayerState(JSON.stringify(meta())); } catch (e) { /* 桥没了就算了 */ }
    }

    /* ---------------------------------------------------------------- 音频钩子 */

    function hook() {
        var a = player();
        if (!a || a.__gbHooked) { return; }
        a.__gbHooked = true;

        var events = ['play', 'pause', 'playing', 'ended', 'loadedmetadata',
            'durationchange', 'seeked', 'emptied', 'canplay'];
        for (var i = 0; i < events.length; i++) {
            (function (ev) {
                a.addEventListener(ev, function () {
                    push(true);
                    if (ev === 'ended') {
                        try { GB.onEnded(); } catch (e) { }
                    }
                });
            })(events[i]);
        }
        a.addEventListener('timeupdate', function () { push(false); });
        push(true);
    }

    /* ---------------------------------------------------------------- 原生 -> 网页 */

    window.__gb = {
        toggle: function () {
            var a = player();
            if (!a) { return; }
            if (a.paused) { this.play(); } else { a.pause(); push(true); }
        },
        play: function () {
            var a = player();
            if (!a) { return; }
            var hasSrc = !!(a.currentSrc || a.src);
            if (!hasSrc) {
                // 站点要先点一下播放按钮才会去解析播放地址
                var btn = $('player-toggle-btn') || $('main-play-btn');
                if (btn) { btn.click(); }
            } else {
                var p = a.play();
                if (p && p.catch) { p.catch(function () { }); }
            }
            push(true);
        },
        pause: function () {
            var a = player();
            if (a && !a.paused) { a.pause(); }
            push(true);
        },
        replay: function () {
            var a = player();
            if (!a) { return; }
            try { a.currentTime = 0; } catch (e) { }
            var p = a.play();
            if (p && p.catch) { p.catch(function () { }); }
            push(true);
        },
        seek: function (sec) {
            var a = player();
            if (!a) { return; }
            try { a.currentTime = Math.max(0, Number(sec) || 0); } catch (e) { }
            push(true);
        },
        state: function () { return JSON.stringify(meta()); },
        /** 当前可下载的直链：优先用站点「下载歌曲」按钮上的地址，否则用音频直链 */
        urls: function () {
            var a = player();
            var src = a ? (a.currentSrc || a.src || '') : '';
            var dl = '';
            var btn = $('btn-download-mp3');
            if (btn) {
                var href = btn.getAttribute('href') || '';
                if (href && href.indexOf('javascript:') !== 0 && href.indexOf('void(') !== 0) { dl = href; }
            }
            return JSON.stringify({ src: src, dl: dl, title: meta().title, artist: meta().artist });
        }
    };

    /* ---------------------------------------------------------------- 外观增强 */

    var CSS = [
        /* 夜间模式兜底：WebView 自带的自动深色不可用时才走这里（反色 + 图片再反回来） */
        '.gb-dark{filter:invert(1) hue-rotate(180deg);background:#101014;}',
        '.gb-dark img,.gb-dark video,.gb-dark canvas,.gb-dark .player-cover-img{filter:invert(1) hue-rotate(180deg);}',
        /* 原生已经有底部导航了，站点的头部导航/页脚是重复的 */
        '.gb-hidehead .site-header{display:none !important;}',
        '.gb-hidefoot footer,.gb-hidefoot .footer-modern{display:none !important;}'
    ].join('\n');

    var lastLook = '';

    function applyLook() {
        var raw = '';
        try { raw = GB.uiConfig(); } catch (e) { return; }
        // 配置没变就不碰 DOM：这个函数每 3 秒会被叫一次，别让站点白白重排
        if (raw === lastLook) { return; }
        var c = {};
        try { c = JSON.parse(raw) || {}; } catch (e) { return; }
        var html = document.documentElement;
        if (!html || !document.body) { return; }
        lastLook = raw;

        var style = $('gb-style');
        if (!style) {
            style = document.createElement('style');
            style.id = 'gb-style';
            (document.head || html).appendChild(style);
        }
        style.textContent = CSS;

        var cls = html.classList;
        if (c.dark && !c.nativeDark) { cls.add('gb-dark'); } else { cls.remove('gb-dark'); }
        if (c.hideHeader) { cls.add('gb-hidehead'); } else { cls.remove('gb-hidehead'); }
        if (c.hideFooter) { cls.add('gb-hidefoot'); } else { cls.remove('gb-hidefoot'); }

        var z = Number(c.zoom) || 1;
        document.body.style.zoom = z === 1 ? '' : String(z);
    }

    /* ---------------------------------------------------------------- 初始化 */

    function autoplayIfNeeded() {
        if (!songId()) { return; }        // 非歌曲页不谈自动播放
        var want = false;
        try { want = !!GB.consumeAutoplay(); } catch (e) { want = false; }
        if (!want) { return; }
        setTimeout(function () { window.__gb.play(); }, 600);
        setTimeout(function () {
            var a = player();
            if (a && a.paused) { window.__gb.play(); }
            push(true);
        }, 2200);
        setTimeout(function () { push(true); }, 4000);
    }

    function reportSong() {
        var d = appData();
        if (!d || !d.mp3_id) { return; }
        try {
            GB.onSongPage(JSON.stringify({
                id: String(d.mp3_id),
                title: d.mp3_title || '',
                artist: d.mp3_author || '',
                cover: d.mp3_cover || '',
                duration: d.mp3_duration || '',
                url: location.href
            }));
        } catch (e) { }
    }

    function init() {
        applyLook();
        hook();
        reportSong();
        autoplayIfNeeded();
        push(true);

        // 站点是整页刷新式导航，但播放器元素可能被复用/重建，这里低频兜底补钩子
        if (hookTimer) { clearInterval(hookTimer); }
        hookTimer = setInterval(function () { hook(); applyLook(); reportSong(); }, 3000);
    }

    window.__gbApply = function () { applyLook(); push(true); };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
