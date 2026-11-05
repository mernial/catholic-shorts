package kr.yunho.talkhelper.core

/**
 * AI 가 다듬은 문장의 '부르는 말(호칭)'을 사용자가 실제로 말한 것과 맞춤.
 * 예) 사용자는 "자매님"이라고 했는데 AI 가 (화면이나 내 역할을 보고) "신부님, ~"으로 바꾼 경우 바로잡음.
 * 사용자가 말하지 않은 호칭으로 부르는 부분은 빼거나, 사용자가 말한 호칭으로 바꿈.
 */
object TitleGuard {
    val TITLES = listOf(
        "신부님", "수녀님", "주교님", "부제님", "수사님", "자매님", "형제님", "교우님",
        "목사님", "전도사님", "스님", "선생님", "교수님", "원장님", "사장님", "회장님", "대표님",
        "부장님", "과장님", "팀장님", "실장님", "어머님", "아버님", "어르신",
    )

    fun fix(output: String, spoken: String): String {
        val said = TITLES.filter { spoken.contains(it) }
        val keep = said.firstOrNull { output.contains(it) }
        val replacement = if (keep == null) said.firstOrNull() else null   // 말한 호칭이 빠졌으면 그걸로
        var out = output
        var used = false
        for (t in TITLES) {
            if (t in said || !out.contains(t)) continue
            // 문장 맨 앞의 "신부님, " / "신부님 "
            out = out.replace(Regex("(^|(?<=[.!?~\\n]\\s{0,2}))$t\\s*[,!~]?\\s+")) { m ->
                if (replacement != null && !used) { used = true; "${m.groupValues[1]}$replacement, " } else m.groupValues[1]
            }
            // 문장 끝의 ", 신부님." / " 신부님!"
            out = out.replace(Regex(",?\\s*$t(?=[.!?~]*\\s*$)")) {
                if (replacement != null && !used) { used = true; ", $replacement" } else ""
            }
        }
        return out.trim()
    }
}
