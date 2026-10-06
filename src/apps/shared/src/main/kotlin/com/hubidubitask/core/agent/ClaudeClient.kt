package com.hubidubitask.core.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/**
 * Claude, through Anthropic's API. Reached with the user's API key from the
 * Anthropic Console; there is no server of ours in between.
 *
 * The apps ask short things (a chat question, a review of one item), so the
 * effort is low: quick answers matter more here than deep deliberation.
 */
class ClaudeClient(
    private val config: AgentConfig,
    /** Only tests point this elsewhere; the apps always use Anthropic's API. */
    baseUrl: String? = null,
) : ModelClient {

    private val key = config.apiKey?.trim().orEmpty()
    private val client: AnthropicClient? =
        if (key.isEmpty()) null
        else AnthropicOkHttpClient.builder().apiKey(key).maxRetries(1)
            .apply { if (baseUrl != null) baseUrl(baseUrl) }.build()

    private fun need(): AnthropicClient =
        client ?: throw AgentError("Claude needs an API key. Add one in Settings.")

    override fun complete(messages: List<Message>): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val params = MessageCreateParams.builder()
            .model(config.model.ifBlank { AgentConfig.CLAUDE_MODEL })
            .maxTokens(16000L)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            // If Claude's safety classifiers decline, Anthropic re-runs the
            // request on the model it recommends for that case.
            .addBeta("server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .apply { if (system.isNotBlank()) system(system) }
            .apply {
                messages.filter { it.role != "system" }.forEach {
                    if (it.role == "assistant") addAssistantMessage(it.content) else addUserMessage(it.content)
                }
            }
            .build()
        val reply = call { need().beta().messages().create(params) }
        if (reply.stopReason().orElse(null) == BetaStopReason.REFUSAL)
            throw AgentError("Claude declined to answer this one.")
        return reply.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }

    override fun health(): Health {
        val model = config.model.ifBlank { AgentConfig.CLAUDE_MODEL }
        if (client == null) return Health(false, "Claude", emptyList(), "no API key")
        return try {
            call { client.models().retrieve(model) }
            Health(true, "Claude ($model)", listOf(model))
        } catch (e: AgentError) {
            Health(false, "Claude", emptyList(), e.message)
        }
    }

    override fun close() { client?.close() }

    /** One call, with Anthropic's errors turned into sentences a person can act on. */
    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: UnauthorizedException) {
        throw AgentError("Anthropic refused the API key. Check it in Settings.", e)
    } catch (e: PermissionDeniedException) {
        throw AgentError("This API key is not allowed to use ${config.model}.", e)
    } catch (e: NotFoundException) {
        throw AgentError("Anthropic does not know the model ${config.model}.", e)
    } catch (e: RateLimitException) {
        throw AgentError("Claude is busy for this key right now. Try again in a minute.", e)
    } catch (e: AnthropicServiceException) {
        throw AgentError("Claude answered with an error (${e.statusCode()}).", e)
    } catch (e: AnthropicIoException) {
        throw AgentError("Claude could not be reached. Check the internet connection.", e)
    }
}
