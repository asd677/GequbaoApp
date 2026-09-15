package com.dsh.gequbao.core

import com.google.gson.JsonObject
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析站点页面的单元测试。
 *
 * 这些用例跑在 JVM 上、用的是**真实抓下来的歌曲页**（app/src/test/resources/music4188.html），
 * 因为「从 window.appData 里把被 JS 转义过的 JSON 还原出来」这段是最容易写错、
 * 又最不容易在身边发现的地方（真跑起来只会表现为「解析失败」四个字）。
 */
class GbResolverTest {

    private fun realPage(): String =
        javaClass.classLoader!!.getResourceAsStream("music4188.html")!!
            .bufferedReader().use { it.readText() }

    @Test
    fun `从真实歌曲页里能拿到 play_id 和歌曲信息`() {
        val raw = GbResolver.extractAppData(realPage())
        assertNotNull("应该在真实页面里找到 window.appData", raw)

        val obj = Gson().fromJson(raw, JsonObject::class.java)
        // 这个页面是 /music/4188：晴天 - 周杰伦
        assertEquals(4188, obj.get("mp3_id").asInt)
        // 页面里是 \u6674\u5929 这种转义，还原后应该是正常的汉字
        assertEquals("晴天", obj.get("mp3_title").asString)
        assertEquals("周杰伦", obj.get("mp3_author").asString)
        assertEquals("04:29", obj.get("mp3_duration").asString)
        assertTrue("play_id 应该是个长串", obj.get("play_id").asString.length > 100)
        // 页面里是 http:\/\/img2.kuwo.cn\/... ，顺便验证 \/ 的还原
        assertTrue(
            "封面应该是 http 直链，实际=" + obj.get("mp3_cover").asString,
            obj.get("mp3_cover").asString.startsWith("http://img")
        )
    }

    @Test
    fun `JS 转义还原`() {
        assertEquals("""{"a":1}""", GbResolver.unescapeJs("""{\u0022a\u0022:1}"""))
        assertEquals("a/b", GbResolver.unescapeJs("""a\/b"""))
        assertEquals("""say "hi"""", GbResolver.unescapeJs("""say \"hi\""""))
        assertEquals("""c:\path""", GbResolver.unescapeJs("""c:\\path"""))
        assertEquals("换行\n结束", GbResolver.unescapeJs("""换行\n结束"""))
        // 结尾一个孤立的反斜杠不能把解析器带崩
        assertEquals("尾巴\\", GbResolver.unescapeJs("尾巴\\"))
    }

    @Test
    fun `找不到 appData 时返回 null 而不是抛异常`() {
        assertEquals(null, GbResolver.extractAppData("<html><body>什么都没有</body></html>"))
    }
}
