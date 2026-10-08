// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a particular model refuses to be asked, and how to ask it differently.
 *
 * Every vendor calls its protocol OpenAI-compatible, and every vendor is compatible up to a point.
 * A reasoning model rejects `temperature`, renames `max_tokens`, drops the system role or refuses
 * to stream at all — and the refusal arrives as HTTP 400 with a sentence about an unsupported
 * parameter, which in the chat looks exactly like «модель сломалась».
 *
 * The catalogue is pure and matched by the model ID, because the ID is all we ever know before the
 * first request. It is deliberately CONSERVATIVE: a quirk here removes or renames a field the user
 * asked for, so a wrong guess is worse than no guess — a temperature silently dropped from a model
 * that supported it changes answers nobody asked to change.
 *
 * Quirks are applied BEFORE `extraBody`, so a hand-written entry in `.vibe/providers.json` always
 * wins over this catalogue: a vendor may fix its API tomorrow, and the person who noticed must not
 * have to wait for us to notice too.
 */
object ModelQuirks {
  enum class Quirk {
    /**
     * Every sampling knob is rejected: the model decides for itself.
     *
     * Kept as an aggregate of the three below rather than replaced by them: it is the honest
     * description of the reasoning families, it is already written in people's own
     * `.vibe/modelQuirks.json`, and taking a spelling away from a config format is a way to break
     * files that were correct yesterday.
     */
    NO_SAMPLING,

    /** Only `temperature` is rejected. */
    NO_TEMPERATURE,

    /** Only `top_p` is rejected. */
    NO_TOP_P,

    /**
     * Only `top_k` is rejected — the case the aggregate could not express.
     *
     * MiniMax documents exactly this: `temperature` and `top_p` work, `top_k` and `stop_sequences`
     * are ignored. Describing it with NO_SAMPLING would take away two knobs the model accepts,
     * which is worse than describing nothing: the person set a temperature and silently did not
     * get it.
     */
    NO_TOP_K,

    /** `max_tokens` is called `max_completion_tokens` here. */
    MAX_COMPLETION_TOKENS,

    /**
     * Thinking is set by the adaptive mode and an effort level, not by a token budget.
     *
     * The vendor split the two spellings by model, and BOTH directions fail: `thinking: {"type": "enabled",
     * "budget_tokens": N}` is a 400 on Opus 4.7, 4.8 and the whole 5 line, and `{"type": "adaptive"}` is a 400 on
     * Sonnet 4.5, Opus 4.5, Haiku 4.5 and earlier. So it is not a client default but a property of the model: a rule by
     * name, which a `modelQuirks.json` of one's own can override.
     *
     * The new spelling is `thinking: {"type": "adaptive"}` plus `output_config: {"effort": …}` with `low`, `medium`,
     * `high`, `xhigh`, `max`; the vendor's default differs by model (`high` on most, `medium` on Opus 5.5), and
     * `adaptive` is not an effort level. Sources: platform.claude.com/docs/en/build-with-claude/extended-thinking and
     * /effort, checked 2026-09-18; /about-claude/models/overview for the defaults, checked 2026-09-23.
     */
    ADAPTIVE_THINKING,

    /**
     * Reasoning cannot be switched off: the «off» position sends the model's lowest level instead.
     *
     * On these models an omitted field is not «no reasoning» but the vendor's default level, and the explicit switch is
     * refused: Claude Opus 5.5 and the Fable and Mythos 5 lines answer 400 to `thinking: {"type": "disabled"}` and
     * think at `medium` or `high` when nothing is sent; GPT-6 Astra answers 400 to `reasoning_effort: "none"`. Sending
     * nothing keeps the promise of «off» on the screen only — the person pays for a level they switched off.
     */
    THINKING_ALWAYS_ON,

    /**
     * «Off» is `thinking: {"type": "disabled"}`, Anthropic's switch: without it the model reasons by default.
     *
     * Sent on the Anthropic wire only — a router speaking chat/completions has its own spelling, and a field it does not
     * know is a guess. A vendor that documents this field on chat/completions declares it in its `providers.json` entry.
     */
    OFF_THINKING_DISABLED,

    /**
     * «Off» is `thinking: {"type": "between_tools"}` at effort `low`: no thinking before the answer, only between tool
     * calls. Claude Sonnet 5.5 answers `disabled` with 400, and this is its lowest setting; it is taken at low, medium
     * and high effort, and without an effort the API default `high` applies
     * (platform.claude.com/docs/en/models/sonnet-5-5/whats-new-sonnet-5-5, checked 2026-09-30)
     */
    OFF_THINKING_BETWEEN_TOOLS,

    /**
     * The model's effort words are `low`, `high` and `max`, with no `medium`, and an unknown word is a 400
     * Kimi K3 and Kimi Code list exactly these; a model fetched from the vendor's list carries no declaration, and our
     * own word `medium` would fail every request on the middle of the slider
     */
    EFFORT_LOW_HIGH_MAX,

    /**
     * «Off» is effort `none`: without it the model reasons at its default. Spelled per wire — `reasoning_effort` on
     * chat/completions, `reasoning.effort` on the Responses wire.
     */
    OFF_EFFORT_NONE,

    /**
     * The model takes no reasoning level: reasoning is a switch, on by default, and the dial's positions above «off»
     * send nothing. The only level field we could send would be one the vendor does not document.
     */
    NO_REASONING_LEVELS,

    /**
     * Forced tool use is refused with a 400
     * Anthropic wire: `tool_choice` `{"type": "any"}` or `{"type": "tool"}`
     * OpenAI wire: `tool_choice` `"required"` or `{"type": "function"}`
     *
     * We never send `tool_choice`, so the quirk changes no request: it lets the doctor name the field in a hand-written
     * `extraBody` before the vendor answers 400 to every turn
     */
    NO_FORCED_TOOL_CHOICE,

    /**
     * The model takes system messages mid-conversation, and with them tools added after the start ([InlineTools])
     * On Anthropic's own API only; the direct chat then keeps `tools` as the thread began and adds found tools in place
     */
    INLINE_TOOL_ADDITIONS,

    /**
     * On the Responses wire the effort changes mid-conversation by a `configuration_update` input item, and the
     * request-level effort stays the thread's first: changing it rewrites the cached prefix ([EffortUpdates])
     */
    EFFORT_BY_UPDATE,

    /**
     * On Anthropic's own API the effort changes mid-conversation by a system message with `output_config.effort`, and the
     * request-level effort stays the thread's first ([EffortUpdates]); in adaptive thinking only
     * Not [EFFORT_BY_UPDATE]: a quirk is chosen by the model's name, and the same Claude behind a router's Responses wire
     * would then get an item that wire does not know
     */
    EFFORT_BY_SYSTEM_MESSAGE,

    /** The system role is not accepted; the instruction has to travel as the first user message. */
    NO_SYSTEM_ROLE,

    /** Streaming is refused, so the answer arrives in one piece or not at all. */
    NO_STREAMING,

    /** Stop sequences are rejected. */
    NO_STOP,

    /**
     * The assistant's reasoning goes back with its answer: every earlier assistant message carries its reasoning in the
     * next request — `reasoning_content` on the openai wire, its thinking blocks on the anthropic one ([ThinkingReplay]).
     *
     * The one quirk that ADDS to a request rather than taking away. Kimi documents it for K3: in a
     * multi-turn conversation the assistant's reply is returned whole, `reasoning_content` included
     * (platform.kimi.ai/docs/guide/use-reasoning-effort, checked 2026-09-11; not verified with a live
     * key). Other models never get the field: a wire that does not expect it may reject it.
     */
    ECHO_REASONING,

    /**
     * With [ECHO_REASONING] on the openai wire: the reasoning goes back inside the answer as `<think>…</think>`, not as
     * a `reasoning_content` field
     * MiniMax streams its reasoning that way and asks for the content back unmodified, the tags included; the chat
     * takes the tags out of the answer as it streams ([InlineThinking]), so they are put back in front of the text
     */
    REASONING_AS_THINK_TAGS,

    /**
     * Tools are not accepted: a request carrying `tools` is refused as a whole.
     *
     * The direct chat offers tools to every model it can; a model behind an endpoint without function
     * calling would otherwise lose every turn to an HTTP 400, not just the tools.
     */
    NO_TOOLS,

    /**
     * Tools are accepted on the Responses wire only; on chat/completions a request carrying `tools` is refused.
     *
     * Not [NO_TOOLS]: the model does call functions, the endpoint decides. The same entry declared
     * `"protocol": "openai-responses"` gets its tools, and the chat says so to whoever calls it over chat/completions.
     */
    TOOLS_ONLY_ON_RESPONSES,

    /**
     * The model answers on the Responses wire only: chat/completions refuses the request as a whole, with or without tools.
     *
     * The wire is never guessed ([ResponsesWire]), so the request is refused here, before it leaves, with words that name
     * the fix — the entry's `"protocol": "openai-responses"` — instead of the vendor's 404.
     */
    RESPONSES_ONLY,

    /**
     * On chat/completions the model calls functions only with reasoning off: a request with tools and any effort but
     * `none` is refused. There a turn with tools goes out at `none`, and the chat says once why the dial was not
     * honoured; the Responses wire takes both together.
     */
    TOOLS_NEED_NO_REASONING,
  }

  /**
   * One catalogue line: which models, what they refuse, and a note for the IDE log.
   *
   * The note is English on purpose: it is written to the log, and the log is not interface — it is
   * read by whoever is debugging, next to hundreds of other English lines.
   */
  data class Rule(val pattern: Regex, val quirks: Set<Quirk>, val note: String)

  /**
   * The catalogue.
   *
   * Patterns are anchored at the start of the ID after the optional `vendor/` prefix used by
   * routers: `openai/o1-mini` is the same model as `o1-mini`, and a catalogue that missed one of
   * the two spellings would be a catalogue that works only on the direct provider.
   */
  val BUILT_IN: List<Rule> = listOf(
    Rule(
      Regex("^o1-(preview|mini)"),
      setOf(Quirk.NO_SAMPLING, Quirk.MAX_COMPLETION_TOKENS, Quirk.NO_SYSTEM_ROLE, Quirk.NO_STREAMING, Quirk.NO_STOP),
      "o1-preview/o1-mini: no system role, no streaming, no sampling knobs",
    ),
    Rule(
      Regex("^(o1|o3|o4)(-|$)"),
      setOf(Quirk.NO_SAMPLING, Quirk.MAX_COMPLETION_TOKENS, Quirk.NO_STOP),
      "o1/o3/o4 family: the model sets its own sampling, and the answer limit is named differently",
    ),
    Rule(
      // Model cards on developers.openai.com list v1/responses and v1/batch only, Chat Completions «Not supported»
      // (checked 2026-09-30)
      Regex("^(o1-pro|o3-pro|gpt-5-pro)(-|$)"),
      setOf(Quirk.RESPONSES_ONLY),
      "o1-pro, o3-pro, gpt-5-pro: the Responses wire only, chat/completions refuses every request",
    ),
    Rule(
      // MiniMax's own documentation of its Anthropic-compatible API: `temperature` and `top_p`
      // work, `top_k` and `stop_sequences` are ignored. Nothing here is inferred from a symptom —
      // the reported SSE oddities are deliberately NOT encoded, because no source names the
      // events, and a quirk guessed from someone else's symptom rewrites requests blindly.
      Regex("^minimax-"),
      setOf(Quirk.NO_TOP_K, Quirk.NO_STOP),
      "minimax: top_k and stop_sequences are ignored by the Anthropic-compatible endpoint",
    ),
    Rule(
      // The 5 line and Opus 4.7/4.8 reject a token budget with 400 and take the adaptive mode with an effort level;
      // the pattern covers fable-5, fable-5-1, mythos-5, mythos-5-1, opus-5, opus-5-5 and sonnet-5
      // (platform.claude.com/docs/en/build-with-claude/extended-thinking, checked 2026-09-18). The same models reject `temperature`, `top_p` and `top_k` with 400
      // (platform.claude.com/docs/en/models/opus-5-5/migration-guide, checked 2026-09-23).
      Regex("^claude-(opus|sonnet|fable|mythos)-5"),
      setOf(Quirk.ADAPTIVE_THINKING, Quirk.NO_SAMPLING),
      "claude 5: thinking is adaptive plus output_config.effort; a token budget and sampling knobs are rejected",
    ),
    Rule(
      Regex("^claude-opus-4-(7|8)"),
      setOf(Quirk.ADAPTIVE_THINKING, Quirk.NO_SAMPLING),
      "claude opus 4.7/4.8: thinking is adaptive plus output_config.effort; a token budget and sampling knobs are rejected",
    ),
    Rule(
      // Opus 4.6 and Sonnet 4.6 still take a token budget, but it is deprecated, and thinking stays off until the adaptive
      // mode is asked for; effort is low, medium, high or max, and the slider never sends xhigh
      // (platform.claude.com/docs/en/build-with-claude/thinking and /effort, checked 2026-10-01)
      // Sampling knobs are not listed as refused for 4.6, so they are left alone
      Regex("^claude-(opus|sonnet)-4-6"),
      setOf(Quirk.ADAPTIVE_THINKING),
      "claude 4.6: thinking is adaptive plus output_config.effort; the token budget is deprecated",
    ),
    Rule(
      // Thinking is always on: `{"type": "disabled"}` is a 400, and an omitted field runs the default level — `medium`
      // on Opus 5.5, `high` on Fable and Mythos (platform.claude.com/docs/en/about-claude/models/overview and
      // /models/opus-5-5/migration-guide, checked 2026-09-23).
      Regex("^claude-(opus-5-5|fable-5|mythos-5)"),
      setOf(Quirk.THINKING_ALWAYS_ON),
      "claude opus 5.5, fable 5, mythos 5: thinking cannot be switched off; «off» sends the lowest effort",
    ),
    Rule(
      // «tool_choice: type "tool" and "any" are not supported for this model», the token counting endpoint included;
      // Fable 5.1 and Mythos 5.1 «reject forced tool use on every request with a 400 error»
      // (platform.claude.com/docs/en/models/opus-5-5/whats-new-opus-5-5, checked 2026-09-25)
      // Exact versions: the page names these three, and Fable 5 and Mythos 5 are not on it
      Regex("^claude-(opus-5-5|fable-5-1|mythos-5-1|sonnet-5-5)"),
      setOf(Quirk.NO_FORCED_TOOL_CHOICE),
      "claude opus 5.5, fable 5.1, mythos 5.1, sonnet 5.5: forced tool use (tool_choice any or tool) is rejected",
    ),
    Rule(
      // Sonnet 5.5, 28.09.2026: thinking is adaptive and on by default, `disabled` is a 400 pointing to `between_tools`,
      // and the API default effort is `high` (platform.claude.com/docs/en/models/sonnet-5-5/whats-new-sonnet-5-5 and
      // /overview, checked 2026-09-30)
      Regex("^claude-sonnet-5-5"),
      setOf(Quirk.OFF_THINKING_BETWEEN_TOOLS),
      "claude sonnet 5.5: «off» sends thinking.type between_tools at effort low",
    ),
    Rule(
      // Haiku 5.5, 07.10.2026: adaptive thinking is on by default and a token budget is a 400; non-default `temperature`,
      // `top_p` or `top_k` is a 400 on every request; `disabled` is accepted at effort `high` or below, which is where
      // «off» leaves it (platform.claude.com/docs/en/models/haiku-5-5/migration-guide, /build-with-claude/thinking and
      // /effort, checked 2026-10-08)
      // Forced tool use is accepted here, unlike Sonnet 5.5: the answer then starts with the call and has no thinking
      Regex("^claude-haiku-5"),
      setOf(Quirk.ADAPTIVE_THINKING, Quirk.NO_SAMPLING, Quirk.OFF_THINKING_DISABLED),
      "claude haiku 5.5: thinking is adaptive and on by default; «off» sends thinking.type disabled; sampling knobs are rejected",
    ),
    Rule(
      // Mid-conversation system messages: «Claude Fable 5.1, Claude Mythos 5.1, Claude Fable 5, Claude Mythos 5,
      // Claude Opus 5.5, Claude Opus 4.8, Claude Opus 5, and Claude Sonnet 5.5», not Sonnet 5
      // (platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages, checked 2026-09-30)
      // The pattern covers opus-5 with opus-5-5 and fable-5, mythos-5 with their 5.1, and stops short of sonnet-5
      Regex("^claude-(opus-5|opus-4-8|fable-5|mythos-5|sonnet-5-5)"),
      setOf(Quirk.INLINE_TOOL_ADDITIONS),
      "claude opus 5, 5.5, 4.8, fable 5, mythos 5, sonnet 5.5: tools found mid-conversation are added by a system message",
    ),
    Rule(
      // Thinking is on by default here too, but the switch is accepted: Opus 5 takes `{"type": "disabled"}` at effort
      // `high` or below, which is where «off» leaves it, and Sonnet 5 always (the thinking pages of
      // platform.claude.com, checked 2026-09-23). Exact ids: a later model of the line has to be read, not assumed.
      Regex("^claude-(opus|sonnet)-5$"),
      setOf(Quirk.OFF_THINKING_DISABLED),
      "claude opus 5, sonnet 5: thinking is on by default; «off» sends thinking.type disabled",
    ),
    Rule(
      // GPT-6 Astra, documented by the vendor on the day it shipped: `temperature`, `top_p` and
      // `logprobs` must be dropped, and the answer limit is named `max_completion_tokens` on
      // chat/completions. Source: developers.openai.com/api/docs/guides/latest-model (checked 2026-09-08).
      Regex("^gpt-6"),
      setOf(Quirk.NO_SAMPLING, Quirk.MAX_COMPLETION_TOKENS),
      "gpt-6: the model sets its own sampling, and the answer limit is named differently",
    ),
    Rule(
      // «Configuration updates are supported by the GPT-6 model family in standard, single-agent mode. They change only
      // reasoning effort» (developers.openai.com/api/docs/guides/reasoning#change-reasoning-mid-conversation, 01.10.2026)
      Regex("^gpt-6"),
      setOf(Quirk.EFFORT_BY_UPDATE),
      "gpt-6: a changed effort goes as a configuration_update item, the request keeps the thread's first",
    ),
    Rule(
      // «On Claude Fable 5.1, Claude Mythos 5.1, Claude Opus 5.5, Claude Opus 5, Claude Sonnet 5.5, and Claude Haiku 5.5,
      // use a per-message effort change, which keeps the prompt cache»; Fable 5 answers it with a 400
      // (platform.claude.com/docs/en/build-with-claude/effort#change-effort-mid-conversation-beta, checked 2026-10-08)
      Regex("^claude-(fable-5-1|mythos-5-1|opus-5|sonnet-5-5|haiku-5-5)"),
      setOf(Quirk.EFFORT_BY_SYSTEM_MESSAGE),
      "claude fable 5.1, mythos 5.1, opus 5, 5.5, sonnet 5.5, haiku 5.5: a changed effort goes as a system message in place",
    ),
    Rule(
      // Astra refuses `reasoning_effort: "none"` with 400, and «Chat Completions does not support function calling with
      // GPT-6 Astra» — the Responses API does (developers.openai.com/api/docs/guides/reasoning, checked 2026-09-23)
      // GPT-6.1 Sol is the same: «The none and minimal reasoning efforts are not supported», «Use the Responses API for
      // tool calling. Chat Completions supports requests without tools» (developers.openai.com/api/docs/guides/latest-model,
      // checked 2026-09-30); `gpt-6.1-sol` is not caught by the Sol and Luna rule below, whose name has no `.1`
      Regex("^gpt-6(-astra|\\.1-sol)"),
      setOf(Quirk.THINKING_ALWAYS_ON, Quirk.TOOLS_ONLY_ON_RESPONSES),
      "gpt-6 astra, gpt-6.1 sol: reasoning cannot be switched off, and tools work on the Responses wire only",
    ),
    Rule(
      // Sol and Luna reason at `medium` unless told `none`, and on chat/completions they call functions ONLY at `none`
      // (developers.openai.com/api/docs/models/gpt-6-sol and /gpt-6-luna, checked 2026-09-23).
      Regex("^gpt-6-(sol|luna)"),
      setOf(Quirk.OFF_EFFORT_NONE, Quirk.TOOLS_NEED_NO_REASONING),
      "gpt-6 sol, luna: reasoning is on by default; «off» sends effort none, and chat/completions takes tools only at none",
    ),
    Rule(
      Regex("^gpt-5"),
      setOf(Quirk.NO_SAMPLING, Quirk.MAX_COMPLETION_TOKENS),
      "gpt-5: the model sets its own sampling, and the answer limit is named differently",
    ),
    Rule(
      // Kimi models that reason: in a multi-turn conversation the assistant's reply goes back whole,
      // `reasoning_content` included, on every message with tool calls too.
      // K3 — platform.kimi.ai/docs/guide/use-reasoning-effort (checked 2026-09-11); K2.6 and K2.7 Code —
      // platform.kimi.ai/docs/guide/use-kimi-k2-thinking-model (K2.7 Code keeps it always); Kimi Code's
      // `kimi-for-coding` (K2.8 Preview) and `k3` — the error «reasoning_content is missing in assistant tool call
      // message» in kimi.com/code/docs/en/kimi-code/error-reference.html (checked 2026-09-17).
      // Not verified with a live key — decision №83.
      Regex("^(kimi-k3|kimi-k2\\.[67]|kimi-for-coding|k3(-|$))"),
      setOf(Quirk.ECHO_REASONING),
      "kimi: the assistant's reasoning_content goes back with its answer and its tool calls in the history",
    ),
    Rule(
      // K3 cannot switch reasoning off, its levels are low, high and max, and the platform's default is max
      // (platform.kimi.ai/docs/guide/use-reasoning-effort, checked 2026-09-11); Kimi Code serves it as `k3` and
      // `k3-256k` with the same levels (kimi.com/code/docs/en/kimi-code/models.html, checked 2026-09-23)
      // Fetched from the vendor's list, the model carries no declaration: «off» sent nothing and ran at max
      Regex("^(kimi-k3|k3)(-|$)"),
      setOf(Quirk.THINKING_ALWAYS_ON, Quirk.EFFORT_LOW_HIGH_MAX),
      "kimi k3: reasoning cannot be switched off; the levels are low, high and max",
    ),
    Rule(
      // Kimi Code's `kimi-for-coding` (K2.8 Preview) takes low, high and max, and `none` switches reasoning off
      // (kimi.com/code/docs/en/kimi-code/models.html, checked 2026-09-23); the HighSpeed model is left out, it always reasons
      Regex("^kimi-for-coding$"),
      setOf(Quirk.OFF_EFFORT_NONE, Quirk.EFFORT_LOW_HIGH_MAX),
      "kimi-for-coding: «off» sends effort none; the levels are low, high and max",
    ),
    Rule(
      // MiniMax interleaved thinking: the whole assistant message goes back — thinking blocks on the Anthropic-compatible
      // endpoint, the content with its `<think>` tags on the OpenAI-compatible one, without which "subsequent
      // conversation loses context" (platform.minimax.io/docs/guides/text-m3-function-call, checked 25.09.2026)
      // M3.1 is left out: it answers with its reasoning in a separate field, and the rule below says so
      Regex("^minimax-m(?!3\\.1)\\d"),
      setOf(Quirk.ECHO_REASONING, Quirk.REASONING_AS_THINK_TAGS),
      "minimax: the reasoning goes back with every answer, as <think> tags on the openai wire",
    ),
    Rule(
      // MiniMax-M3.1-Flash-Preview, for M Plan and MiniMax Code only for now: «always thinks and returns 400 if disabled
      // is sent», reasoning_effort / output_config.effort take low, medium, high, xhigh and max and «defaults to max when
      // omitted»; its reasoning comes in `reasoning_content`, which «must also be preserved completely», and
      // `reasoning_split: false` is not supported (platform.minimax.io/docs/api-reference/text-openai-api and
      // text-anthropic-api, checked 2026-09-30)
      // «Off» therefore sends low: sending nothing ran the model at max; on the Anthropic wire thinking is adaptive
      Regex("^minimax-m3\\.1"),
      setOf(Quirk.ECHO_REASONING, Quirk.THINKING_ALWAYS_ON, Quirk.ADAPTIVE_THINKING),
      "minimax m3.1: reasoning cannot be switched off and goes back in reasoning_content; «off» sends low, not the default max",
    ),
    Rule(
      // Xiaomi MiMo: "during multi-turn tool calls in thinking mode the model returns a `thinking` content block
      // alongside `tool_use`", and the vendor recommends sending previous blocks back
      // (mimo.mi.com/docs/en-US/api/chat/anthropic-api, checked 2026-09-22) — the same condition as Kimi and
      // DeepSeek. Verified against the documentation, not with a live key.
      Regex("^mimo-"),
      setOf(Quirk.ECHO_REASONING),
      "mimo: the thinking block comes back with the answer and its tool calls in the history",
    ),
    Rule(
      // MiMo v2.6 in thinking mode FORCES temperature 1.0 and top_p 0.95: "the actual effective values will be
      // forcibly set by the model" — a value sent is not an error, it is silently ignored
      // (mimo.mi.com/docs/en-US/api/chat/anthropic-api, checked 2026-09-22).
      //
      // The cost of this rule, stated plainly: it removes both knobs from the WHOLE v2.6 line, including requests
      // with thinking off, where the vendor would honour them. The opposite mistake is worse: a slider that moves
      // and changes nothing is silence. The quirk catalog does not know the request mode, and adding a mode just
      // for one vendor costs more than losing temperature on flash.
      Regex("^mimo-v2\\.6"),
      setOf(Quirk.NO_SAMPLING),
      "mimo v2.6: in thinking mode the vendor overrides temperature and top_p, so we do not send them",
    ),
    Rule(
      // MiMo's thinking is a switch, `thinking: {"type": "enabled" | "disabled"}`, on by default; its chat/completions
      // reference lists no effort field at all (mimo.mi.com/docs, api/chat/openai-api, checked 2026-09-23). The «off»
      // spelling travels in the providers.json entry: this vendor documents `thinking` on both wires.
      Regex("^mimo-v2\\.6"),
      setOf(Quirk.NO_REASONING_LEVELS),
      "mimo v2.6: reasoning has no levels, only on and off",
    ),
    Rule(
      // DeepSeek ставит то же условие и ровно так же обусловливает его инструментами: «with `tools`,
      // the `reasoning_content` of all previous turns should be passed back», без инструментов
      // возвращать не нужно и присланное будет проигнорировано
      // (api-docs.deepseek.com/guides/thinking_mode, сверено 18.09.2026). Ход с вызовом инструмента
      // без возврата рассуждения вендор отвергает — а это ровно наш прямой чат с инструментами.
      Regex("^deepseek"),
      setOf(Quirk.ECHO_REASONING),
      "deepseek: the reasoning_content of previous turns goes back with the tool calls",
    ),
    Rule(
      // «Thinking mode does not support this tool_choice» — 400 on `tool_choice: "required"`, and thinking is on by
      // default (api-docs.deepseek.com/guides/thinking_mode; reproduced live by VibeIDE on deepseek-flash, 01.10.2026)
      Regex("^deepseek"),
      setOf(Quirk.NO_FORCED_TOOL_CHOICE),
      "deepseek: forced tool use (tool_choice required or a named function) is rejected in thinking mode",
    ),
  )

  /** Everything known about this model ID; an empty set for a model nobody complained about. */
  fun quirksOf(modelId: String, overrides: List<Rule> = emptyList()): Set<Quirk> {
    val id = normalize(modelId)
    if (id.isEmpty()) return emptySet()
    // A matching file entry REPLACES the built-in answer rather than adding to it — otherwise
    // there would be no way to say "this model is fine now", which is half the reason the file
    // exists: a vendor fixing its API must not need an IDE release to be believed.
    overrides.firstOrNull { it.pattern.containsMatchIn(id) }?.let { return it.quirks }
    return BUILT_IN.filter { it.pattern.containsMatchIn(id) }.flatMap { it.quirks }.toSet()
  }

  /** The human explanation, for the log line that says why the request was not sent as written. */
  fun noteOf(modelId: String, overrides: List<Rule> = emptyList()): String? {
    val id = normalize(modelId)
    overrides.firstOrNull { it.pattern.containsMatchIn(id) }?.let { return it.note }
    return BUILT_IN.firstOrNull { it.pattern.containsMatchIn(id) }?.note
  }

  /** Where the answer for this model came from — the one thing «почему пропала temperature» needs. */
  fun sourceOf(modelId: String, overrides: List<Rule> = emptyList()): String? {
    val id = normalize(modelId)
    if (id.isEmpty()) return null
    if (overrides.any { it.pattern.containsMatchIn(id) }) return "modelQuirks.json"
    return if (BUILT_IN.any { it.pattern.containsMatchIn(id) }) "built-in" else null
  }

  fun has(modelId: String, quirk: Quirk, overrides: List<Rule> = emptyList()): Boolean =
    quirk in quirksOf(modelId, overrides)

  /**
   * What the catalogue knows about switching this model's reasoning off, in the shape a `providers.json` entry declares
   * it; null when it knows nothing. An entry's own declaration wins field by field ([ReasoningMode.merged]).
   *
   * A spelling is offered only on the wire it belongs to: the same model behind a router speaks the router's dialect.
   */
  fun reasoningOf(modelId: String, wire: String, overrides: List<Rule> = emptyList()): ReasoningMode.Support? {
    val quirks = quirksOf(modelId, overrides)
    val words = if (Quirk.EFFORT_LOW_HIGH_MAX in quirks) LOW_HIGH_MAX else emptyList()
    return when {
      Quirk.THINKING_ALWAYS_ON in quirks -> ReasoningMode.Support(canTurnOff = false, words = words)
      Quirk.OFF_THINKING_BETWEEN_TOOLS in quirks && wire == WIRE_ANTHROPIC -> ReasoningMode.Support(off = THINKING_BETWEEN_TOOLS)
      Quirk.OFF_THINKING_DISABLED in quirks && wire == WIRE_ANTHROPIC -> ReasoningMode.Support(off = THINKING_DISABLED)
      Quirk.OFF_EFFORT_NONE in quirks && wire == WIRE_OPENAI -> ReasoningMode.Support(off = EFFORT_NONE, words = words)
      Quirk.OFF_EFFORT_NONE in quirks && wire == WIRE_OPENAI_RESPONSES ->
        ReasoningMode.Support(off = RESPONSES_EFFORT_NONE, words = words)
      words.isNotEmpty() -> ReasoningMode.Support(words = words)
      else -> null
    }
  }

  private val THINKING_DISABLED: JsonObject = buildJsonObject { put("thinking", buildJsonObject { put("type", "disabled") }) }
  private val THINKING_BETWEEN_TOOLS: JsonObject = buildJsonObject {
    put("thinking", buildJsonObject { put("type", "between_tools") })
    put("output_config", buildJsonObject { put("effort", "low") })
  }
  private val LOW_HIGH_MAX = listOf("low", "high", "max")

  private val EFFORT_NONE: JsonObject = buildJsonObject { put("reasoning_effort", "none") }

  private val RESPONSES_EFFORT_NONE: JsonObject = buildJsonObject { put("reasoning", buildJsonObject { put("effort", "none") }) }

  /** Whether a request to this model on this wire may carry tools. */
  enum class ToolSupport {
    YES,

    /** The model takes no tools on any wire we speak. */
    NO,

    /** The model takes tools, but only on the Responses wire ([Quirk.TOOLS_ONLY_ON_RESPONSES]). */
    ONLY_ON_RESPONSES,
  }

  fun toolSupport(modelId: String, wire: String, overrides: List<Rule> = emptyList()): ToolSupport {
    val quirks = quirksOf(modelId, overrides)
    return when {
      Quirk.NO_TOOLS in quirks -> ToolSupport.NO
      Quirk.TOOLS_ONLY_ON_RESPONSES in quirks && wire != WIRE_OPENAI_RESPONSES -> ToolSupport.ONLY_ON_RESPONSES
      else -> ToolSupport.YES
    }
  }

  /** True when a request with tools must go with reasoning off on this wire ([Quirk.TOOLS_NEED_NO_REASONING]). */
  fun reasoningYieldsToTools(modelId: String, wire: String, overrides: List<Rule> = emptyList()): Boolean =
    wire == WIRE_OPENAI && has(modelId, Quirk.TOOLS_NEED_NO_REASONING, overrides)

  fun supportsStreaming(modelId: String, overrides: List<Rule> = emptyList()): Boolean =
    !has(modelId, Quirk.NO_STREAMING, overrides)

  /**
   * The request body, rewritten to what this model actually accepts.
   *
   * Renaming keeps the VALUE: the user asked for a limit, and dropping it because the field moved
   * would replace their number with the provider's default without saying so.
   */
  fun applyToBody(
    modelId: String,
    body: JsonObject,
    overrides: List<Rule> = emptyList(),
    /**
     * Which dialect the body is written in.
     *
     * Not cosmetic: the same refusal is a different field on each wire — a stop list is `stop` for
     * OpenAI and `stop_sequences` for Anthropic. Applying the OpenAI names to an Anthropic body
     * removes nothing and silently sends exactly what the model rejects, which is how a quirk
     * catalogue turns into decoration.
     */
    wire: String = WIRE_OPENAI,
  ): JsonObject {
    val quirks = quirksOf(modelId, overrides)
    if (quirks.isEmpty()) return body
    val anthropic = wire == WIRE_ANTHROPIC
    val fields = LinkedHashMap(body)
    if (Quirk.NO_SAMPLING in quirks || Quirk.NO_TEMPERATURE in quirks) fields.remove("temperature")
    if (Quirk.NO_SAMPLING in quirks || Quirk.NO_TOP_P in quirks) fields.remove("top_p")
    if (Quirk.NO_SAMPLING in quirks || Quirk.NO_TOP_K in quirks) fields.remove("top_k")
    if (Quirk.NO_STOP in quirks) {
      fields.remove(if (anthropic) "stop_sequences" else "stop")
    }
    // Anthropic's own field IS `max_tokens` and it is required — renaming it there would produce a
    // request without an answer limit at all. The Responses wire names its limit `max_output_tokens` for every model.
    if (Quirk.MAX_COMPLETION_TOKENS in quirks && wire == WIRE_OPENAI) {
      fields.remove("max_tokens")?.let { fields["max_completion_tokens"] = it }
    }
    if (Quirk.NO_STREAMING in quirks) {
      fields.remove("stream")
    }
    return JsonObject(fields)
  }

  const val WIRE_OPENAI = "openai"
  const val WIRE_ANTHROPIC = "anthropic"

  /** OpenAI's `/v1/responses` ([ResponsesWire]): its own endpoint, not a dialect of chat/completions. */
  const val WIRE_OPENAI_RESPONSES = "openai-responses"

  /**
   * The messages, rewritten the same way.
   *
   * A model without a system role gets the instruction as the first user message rather than
   * losing it: the system prompt is where the rules of the whole session live, and silently
   * dropping it produces an agent that ignores the project — with nothing in the log to explain it.
   */
  fun applyToMessages(
    modelId: String,
    messages: List<ChatMessage>,
    overrides: List<Rule> = emptyList(),
  ): List<ChatMessage> {
    if (!has(modelId, Quirk.NO_SYSTEM_ROLE, overrides)) return messages
    val system = messages.filter { it.role == "system" }
    if (system.isEmpty()) return messages
    val rest = messages.filterNot { it.role == "system" }
    val folded = ChatMessage("user", system.joinToString("\n\n") { it.text })
    return listOf(folded) + rest
  }

  /** `openai/o1-mini` and `o1-mini` are the same model; a router prefix must not hide a quirk. */
  private fun normalize(modelId: String): String {
    val trimmed = modelId.trim().lowercase()
    return if ('/' in trimmed) trimmed.substringAfterLast('/') else trimmed
  }
}
