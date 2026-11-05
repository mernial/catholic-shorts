package kr.yunho.talkhelper.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 도우미 버튼을 꾹 누르면 펼쳐지는 동그라미들의 자리.
 * 모든 동그라미는 같은 크기, 같은 간격으로 반원 위에 놓임. 화면에 다 안 들어가면
 * 하나만 밀어 넣지 않고(그러면 간격이 어긋남) 반원 전체를 옮기거나 다 같이 작게 함.
 */
object MenuLayout {
    data class Placement(val size: Float, val centers: List<Pair<Float, Float>>)

    fun place(
        n: Int, cx: Float, cy: Float, w: Float, h: Float,
        maxRadius: Float, maxSize: Float, gap: Float, margin: Float, minSize: Float,
    ): Placement {
        val left = cx > w / 2   // 버튼이 오른쪽에 있으면 왼쪽으로 펼침
        val step = PI / (n - 1)
        var size = maxSize
        var r = maxRadius
        repeat(3) {
            // 반원 중심(가로): 끝 동그라미가 화면 밖으로 나가지 않게 반원 전체를 안쪽으로
            val acx = cx.coerceIn(margin + size / 2, w - margin - size / 2)
            val far = if (left) acx - margin - size / 2 else w - margin - size / 2 - acx
            r = minOf(maxRadius, far, (h - 2 * margin - size) / 2)
            // 이웃한 동그라미 중심 사이 거리가 크기+간격보다 작으면 다 같이 줄임
            val chord = (2 * r * sin(step / 2)).toFloat()
            if (chord >= size + gap) return@repeat
            size = (chord - gap).coerceAtLeast(minSize)
        }
        val acx = cx.coerceIn(margin + size / 2, w - margin - size / 2)
        val lo = margin + r + size / 2
        val hi = h - margin - r - size / 2
        val acy = if (lo <= hi) cy.coerceIn(lo, hi) else h / 2
        val centers = (0 until n).map { i ->
            val t = i.toDouble() / (n - 1)
            val a = if (left) PI + PI / 2 - PI * t else -PI / 2 + PI * t   // 위에서 아래로
            (acx + r * cos(a)).toFloat() to (acy + r * sin(a)).toFloat()
        }
        return Placement(min(size, maxSize), centers)
    }
}
