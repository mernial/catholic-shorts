package kr.yunho.talkhelper.core

/**
 * 카톡 대화방을 위로 넘기며 모은 화면들을 하나의 대화로 이어 붙임.
 * 한 번 넘길 때 앞 화면과 몇 줄이 겹치므로, 겹친 부분은 한 번만 남김.
 */
object History {
    /** 지금까지 모은 대화 [acc] 앞에, 위로 넘겨서 본 더 예전 화면 [older] 를 붙임 */
    fun prepend(acc: List<String>, older: List<String>): List<String> {
        if (acc.isEmpty()) return older
        if (older.isEmpty()) return acc
        // older 의 끝부분이 acc 의 앞부분과 겹치는 가장 긴 길이
        for (k in minOf(acc.size, older.size) downTo 1) {
            if (older.subList(older.size - k, older.size) == acc.subList(0, k)) {
                return older.subList(0, older.size - k) + acc
            }
        }
        return older + acc
    }

    /** 위로 넘겼는데 새로 보이는 줄이 없으면 대화의 맨 처음에 닿은 것 */
    fun reachedTop(acc: List<String>, older: List<String>) = prepend(acc, older).size == acc.size
}
