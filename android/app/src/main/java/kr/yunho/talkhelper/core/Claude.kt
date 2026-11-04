package kr.yunho.talkhelper.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/** 화면에 그대로 보여줄 수 있는 한국어 오류 */
class HelperException(message: String) : Exception(message)

/**
 * Claude 호출. 블로킹 함수이므로 반드시 백그라운드 스레드에서 부를 것.
 */
class Claude(apiKey: String, baseUrl: String? = null) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofSeconds(90))
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    /** 내가 대충 말한 것을 [mode] 말투의 카톡 메시지로. [screenText] 는 그 순간 카톡 화면에 보이던 글 */
    fun rewrite(mode: Mode, spoken: String, screenText: String?, myName: String, myRole: String): String =
        ask(Prompts.system(mode, myName, myRole), Prompts.user(mode, spoken, screenText))
            .trim().removeSurrounding("\"").trim()

    private fun ask(system: String, user: String): String {
        val params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(4000)
            // 짧은 문장 다듬기라 effort 를 낮춰 속도와 요금을 줄임
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // 안전 분류기가 거절하면 서버가 알맞은 다른 모델로 자동 재시도
            .addBeta("server-side-fallback-2026-07-01")
            .fallbacksDefault()
            .system(system)
            .addUserMessage(user)
            .build()

        val msg = try {
            client.beta().messages().create(params)
        } catch (e: UnauthorizedException) {
            throw HelperException("API 키가 올바르지 않습니다. 설정에서 키를 확인해 주세요.")
        } catch (e: PermissionDeniedException) {
            throw HelperException("이 API 키로는 사용할 수 없습니다. 키 권한을 확인해 주세요.")
        } catch (e: RateLimitException) {
            throw HelperException("요청이 많아 잠시 쉬어야 합니다. 조금 뒤에 다시 해 주세요.")
        } catch (e: BadRequestException) {
            throw HelperException("요청이 거부되었습니다: ${e.message}")
        } catch (e: InternalServerException) {
            throw HelperException("Claude 서버가 잠시 불안정합니다. 조금 뒤에 다시 해 주세요.")
        } catch (e: AnthropicServiceException) {
            throw HelperException("Claude 오류(${e.statusCode()}): ${e.message}")
        } catch (e: AnthropicIoException) {
            throw HelperException("인터넷 연결을 확인해 주세요.")
        }

        if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
            throw HelperException("이 내용은 처리할 수 없다고 합니다. 다르게 말해 주세요.")
        }
        val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) throw HelperException("답을 받지 못했습니다. 다시 해 주세요.")
        return text
    }

    companion object {
        // 뉘앙스·존댓말을 더 섬세하게 다루는 중간 등급 (한 번 다듬는 데 약 10원)
        const val MODEL = "claude-sonnet-5-5"
    }
}
