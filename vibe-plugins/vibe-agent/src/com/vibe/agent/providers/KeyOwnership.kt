// Copyright 2026 VibeBrains. Use of this source code is governed by the GNU AGPL-3.0 license.
package com.vibe.agent.providers

/**
 * Кто владеет записью ключа в связке — и почему вопрос пароля возвращается.
 *
 * Связка ключей macOS хранит список доступа У КАЖДОЙ ЗАПИСИ и заполняет его в момент СОЗДАНИЯ
 * записи. Запись, созданная прежней сборкой — неподписанной или подписанной иначе, — помнит то
 * приложение, и нынешнее при каждом чтении спрашивает пароль. Лечится перезаписью
 * ([ApiKeyResolver.restoreKey]): удалить и создать заново, тогда список доступа становится наш.
 *
 * Действие «Ключи провайдеров: убрать вопросы связки ключей» делает это по просьбе человека — и
 * закрывает ровно те ключи, что существовали в момент запуска. Ключ, добавленный позже, приносит
 * вопрос снова, и владелец справедливо спросил, сколько раз ему об этом писать (20.09.2026).
 *
 * Поэтому перезапись стала происходить САМА — но ровно там, где она ничего не стоит человеку:
 * **сразу после успешного чтения**. Вопрос за этот ключ он в этот момент уже увидел и пароль уже
 * ввёл; перезапись делает так, что второго раза не будет. Ни очереди вопросов на старте, ни
 * лишних обращений к связке: один ключ — не больше одного вопроса за всю жизнь.
 *
 * Чистая: состояние внутрь, решение наружу.
 */
object KeyOwnership {
  /**
   * Версия схемы владения. Меняется, если меняется САМ способ подписи: перезаписанные под
   * прежним удостоверением ключи тогда придётся переписать заново.
   */
  const val SCHEME: String = "v1"

  /** Отметка «этот ключ уже переписан под нынешнее удостоверение». */
  fun markOf(ref: String): String = "vibe.keys.reowned.$SCHEME.$ref"

  /**
   * @param onMac только у macOS список доступа привязан к приложению; у остальных хранилищ
   *   перезапись не даёт ничего и лишь тревожит хранилище
   * @param alreadyReowned переписывали ли мы этот ключ раньше
   * @param keyPresent нашёлся ли ключ в связке (переписывать нечего, если его там нет)
   */
  fun needsReown(onMac: Boolean, alreadyReowned: Boolean, keyPresent: Boolean): Boolean =
    onMac && keyPresent && !alreadyReowned

  /**
   * The two places a provider key can live, and the case where both hold one.
   *
   * [ApiKeyResolver.resolve] reads the IDE slot first and the environment only as a fallback, so a key typed into the
   * provider card silently shadows a variable the person set on purpose. Neither copy is wrong on its own; together
   * they make «I changed the variable and nothing happened» — a support question with no visible cause. The doctor
   * names the pair instead of printing any value.
   *
   * Pure: presence in, a report out. Reading the keychain or the environment stays with the caller.
   */
  data class BothKeySources(val providerId: String, val envName: String, val ref: String)

  fun bothKeySources(provider: ProviderEntry, storedPresent: Boolean, envPresent: Boolean): BothKeySources? {
    if (!storedPresent || !envPresent) return null
    val envName = provider.apiKeyEnv?.takeIf { it.isNotBlank() } ?: return null
    return BothKeySources(provider.id, envName, provider.apiKeyRef ?: provider.id)
  }
}
