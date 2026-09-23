// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.agent.providers

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * API key resolution, VibeIDE priority: apiKeyRef (OS secure storage) →
 * `.vibe/.env` (workspace, then `~/.vibe/.env`; minimal dotenv subset, no
 * interpolation) → OS environment via apiKeyEnv. The key never lives in
 * providers.json and never reaches the transcript.
 */
object ApiKeyResolver {
  fun attributes(ref: String): CredentialAttributes =
    CredentialAttributes(generateServiceName("VibeIDEA Providers", ref))

  fun storedKey(provider: ProviderEntry): String? {
    val ref = provider.apiKeyRef ?: provider.id
    return readStored(ref)?.also { known[ref] = it; reownOnce(provider, it) }
  }

  /**
   * Ключи, которые в ЭТОМ запуске уже удалось прочитать из связки, — чтобы фон к ней не ходил.
   *
   * Зачем. Обновление каталогов моделей при старте шло по всем провайдерам и читало ключ каждого —
   * шесть обращений к связке ещё до того, как человек что-то попросил. Запись, чей список доступа
   * не наш, на каждое такое обращение отвечает диалогом с паролем: владелец получал его при
   * каждом запуске за провайдера, которым в тот день и не пользовался (opencode, 20.09.2026).
   *
   * Фоновые задачи теперь спрашивают [resolveQuietly]: ключ из окружения, из `.vibe/.env` или уже
   * прочитанный здесь — и никогда из связки. Первым связку читает настоящий запрос человека к
   * этому провайдеру; после него ключ известен, а запись переписана ([reownOnce]).
   */
  private val known = java.util.concurrent.ConcurrentHashMap<String, String>()

  /**
   * Ключ провайдера БЕЗ обращения к связке: для фона, которому нельзя показывать диалог.
   *
   * Отсутствие ключа здесь не означает «ключа нет» — означает «его ещё никто не читал». Фоновая
   * задача в этом случае пропускает провайдера и оставляет то, что у неё есть (кэш каталога), а не
   * стучится в связку сама.
   */
  fun resolveQuietly(provider: ProviderEntry, projectBase: String?): String? {
    known[provider.apiKeyRef ?: provider.id]?.let { return it }
    provider.apiKeyEnv?.let { envName ->
      dotEnv(projectBase)[envName]?.takeIf { it.isNotBlank() }?.let { return it }
      System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
  }

  /**
   * Переписать запись под нынешнее приложение — один раз на ключ, в фоне, молча.
   *
   * Момент выбран не случайно: чтение только что удалось, значит вопрос пароля (если он был)
   * человек уже увидел и закрыл. Перезапись делает так, что второго вопроса не будет никогда.
   * Раньше это умело только действие по просьбе человека, и закрывало лишь те ключи, что были на
   * момент запуска, — новый ключ приносил вопрос снова. Разбор — [KeyOwnership].
   *
   * В фоне и под `runCatching`, потому что это удобство, а не работа: отказ связки не должен
   * ни задержать запрос к модели, ни тем более его уронить.
   */
  private fun reownOnce(provider: ProviderEntry, key: String) {
    val ref = provider.apiKeyRef ?: provider.id
    val properties = PropertiesComponent.getInstance()
    val mark = KeyOwnership.markOf(ref)
    if (!KeyOwnership.needsReown(SystemInfo.isMac, properties.getBoolean(mark, false), keyPresent = true)) return
    // Отметка ставится ТОЛЬКО на удачу, а от повторных попыток в этом же запуске защищает список
    // в памяти. Первая версия ставила отметку заранее — «чтобы не дёргать связку каждым запросом»,
    // — и тем самым делала неудачу вечной: перезапись не удалась, второй попытки уже не будет, и
    // человек остаётся с вопросом пароля навсегда. Память процесса решает ту же задачу и не
    // переживает перезапуск: следующий запуск попробует снова.
    if (!attempted.add(ref)) return
    ApplicationManager.getApplication().executeOnPooledThread {
      val done = runCatching { restoreKey(provider, key) }
        .onFailure { logger<ApiKeyResolver>().warn("could not re-own the keychain entry for $ref: ${it.message}") }
        .getOrDefault(false)
      // Удача тоже пишется в лог: иначе «сработало или нет» невозможно узнать иначе как по
      // отсутствию вопроса пароля через день.
      if (done) {
        properties.setValue(mark, true)
        logger<ApiKeyResolver>().info("keychain entry for $ref re-owned by this build; no more password prompts for it")
      }
    }
  }

  /**
   * Ключи, перезапись которых уже пробовали в ЭТОМ запуске.
   *
   * Не кэш и не состояние — предохранитель от того, чтобы неудача повторялась на каждом чтении
   * ключа, то есть на каждом запросе к модели.
   */
  private val attempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  /** A key is present, absent, or could not be asked about. The third is not a synonym of the second. */
  enum class Presence { PRESENT, ABSENT, UNKNOWN }

  fun hasStoredKey(provider: ProviderEntry): Boolean = keyPresence(provider) == Presence.PRESENT

  /**
   * Whether a key is stored — in three answers, not two.
   *
   * A keychain dialog comes from reading a VALUE, and only on macOS, where the access list guards the item's data
   * ([KeychainProbe]). Hence the order:
   *
   * - on macOS the probe goes first, without reading;
   * - **elsewhere the value is read right away**: the store is not Apple's keychain, there is no dialog at all, and
   *   answering "unknown" there would be made up;
   * - on macOS, when the probe does not answer, the answer says so — `UNKNOWN`.
   *
   * A two-valued answer folds "could not ask" into "no", and outside macOS the probe always answers "could not ask":
   * every stored key would then be reported missing — on the settings page, in the doctor and in catalog refresh.
   */
  fun keyPresence(provider: ProviderEntry): Presence {
    val ref = provider.apiKeyRef ?: provider.id
    val onMac = SystemInfo.isMac
    val knownInThisRun = known.containsKey(ref)
    val valueFound = KeyPresenceRule.mustReadValue(onMac, knownInThisRun) && readStored(ref) != null
    val probe = if (!knownInThisRun && onMac) KeychainProbe.probe(attributes(ref).serviceName)
                else KeychainProbe.State.UNKNOWN
    return KeyPresenceRule.decide(onMac, knownInThisRun, probe, valueFound)
  }

  /**
   * Read a store entry, falling back to the backup left by an interrupted rewrite.
   *
   * The backup is checked here and not only in the manual action: [restoreKey] deletes the entry before creating it
   * again, and an IDE restart or a keychain refusal fits into the gap between the two steps. The key is then intact
   * but sits in the `(backup)` entry, while the person sees an empty field and only knows the provider stopped
   * working. Recovery has to happen where the key is read, not where someone remembers to press a button.
   */
  private fun readStored(ref: String): String? {
    rawStored(ref)?.let { return it }
    val backup = runCatching { PasswordSafe.instance.getPassword(attributes(ref + BACKUP_SUFFIX)) }
      .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    // Found a backup: put the key back right away, so the next start does not depend on anyone pressing a button.
    runCatching {
      PasswordSafe.instance.setPassword(attributes(ref), backup)
      PasswordSafe.instance.setPassword(attributes(ref + BACKUP_SUFFIX), null)
      logger<ApiKeyResolver>().info("recovered the key for $ref from its backup entry")
    }
    return backup
  }

  /**
   * Only the main entry, without backup recovery.
   *
   * [restoreKey] needs it: it checks whether the entry is GONE after the delete, and recovery there would return the
   * very key it had just put into the backup — the check would always fail and every rewrite would look unsuccessful.
   */
  private fun rawStored(ref: String): String? =
    PasswordSafe.instance.getPassword(attributes(ref))?.takeIf { it.isNotBlank() }

  fun storeKey(provider: ProviderEntry, key: String?) {
    val ref = provider.apiKeyRef ?: provider.id
    if (key.isNullOrBlank()) known.remove(ref) else known[ref] = key
    PasswordSafe.instance.setPassword(attributes(ref), key?.ifBlank { null })
  }

  /**
   * Записать ключ ЗАНОВО — с удалением прежней записи, а не поверх неё.
   *
   * Разница не косметическая, и стоила она владельцу недели вопросов пароля. Связка ключей держит
   * список доступа У КАЖДОЙ ЗАПИСИ и заполняет его при СОЗДАНИИ записи. Платформа на macOS, найдя
   * существующую запись, зовёт `SecKeychainItemModifyContent` — правку на месте
   * (`platform/credential-store-impl/src/credentialStore/macOsKeychainLibrary.kt`), а правка на
   * месте список доступа не трогает. Поэтому «прочитать и записать обратно» оставляло владельцем
   * записи ТО САМОЕ старое приложение, и связка продолжала спрашивать пароль при каждом чтении —
   * сколько бы раз мы ни «переписывали».
   *
   * Удаление уводит платформу в другую ветку того же метода (`SecKeychainItemDelete`), и следующая
   * запись создаётся заново — `SecKeychainAddGenericPassword`, список доступа наш.
   *
   * Найдено 19.09.2026 по коду платформы, после того как владелец сообщил, что вопросы вернулись
   * после действия, которое должно было их убрать.
   */
  fun restoreKey(provider: ProviderEntry, key: String): Boolean {
    val ref = provider.apiKeyRef ?: provider.id
    // Страховка перед удалением. Между удалением и записью есть окно, и цена падения в нём —
    // не «неудобство», а потерянный ключ: половина провайдеров показывает его ОДИН раз при
    // создании, и восстановить его человеку неоткуда. Запасная запись переживает и падение
    // процесса, и отказ связки: следующий запуск подберёт её сам ([recoverLeftovers]).
    PasswordSafe.instance.setPassword(attributes(ref + BACKUP_SUFFIX), key)
    storeKey(provider, null)
    // Удаление обязано СОСТОЯТЬСЯ, и это проверяется отдельно. Если список доступа записи не наш,
    // связка может отказать в удалении (или человек откажет в диалоге) — запись остаётся, следующая
    // запись поверх неё идёт правкой на месте, список доступа не меняется, а прочитанное обратно
    // всё равно совпадает с ключом. Прежняя проверка «прочитали то, что писали» считала это
    // успехом и ставила отметку — вопрос пароля становился вечным.
    if (rawStored(ref) != null) {
      logger<ApiKeyResolver>().warn("keychain entry for $ref survived the delete — its access list is not ours, will retry on the next launch")
      PasswordSafe.instance.setPassword(attributes(ref + BACKUP_SUFFIX), null)
      return false
    }
    storeKey(provider, key)
    // Читаем обратно, а не верим записи: связка может отказать молча, и «переписано» без проверки
    // означало бы отчёт о работе, которой не было.
    val written = rawStored(ref) == key
    if (written) PasswordSafe.instance.setPassword(attributes(ref + BACKUP_SUFFIX), null)
    return written
  }

  /**
   * Вернуть ключи из страховочных записей, оставшихся от прерванной перезаписи.
   *
   * Зовётся перед перезаписью: если прошлый заход умер между удалением и записью, ключ лежит в
   * запасной записи, и человек об этом не знает — он знает только, что провайдер перестал работать.
   *
   * @return сколько ключей возвращено
   */
  fun recoverLeftovers(providers: List<ProviderEntry>): Int {
    var recovered = 0
    providers.forEach { provider ->
      val ref = provider.apiKeyRef ?: provider.id
      val backup = runCatching { PasswordSafe.instance.getPassword(attributes(ref + BACKUP_SUFFIX)) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
      if (storedKey(provider) == null) {
        storeKey(provider, backup)
        recovered++
      }
      PasswordSafe.instance.setPassword(attributes(ref + BACKUP_SUFFIX), null)
    }
    return recovered
  }

  /**
   * Суффикс страховочной записи. Отдельная запись, а не поле: связка хранит одну строку на ключ.
   *
   * По-английски намеренно, и это не про стиль: имя записи — АДРЕС, по которому страховка ищется
   * при следующем запуске. Переведи его — и после смены языка интерфейса старая страховка станет
   * недостижимой ровно тогда, когда она нужна.
   */
  private const val BACKUP_SUFFIX = " (backup)"

  /**
   * The key of this provider, or null when there is none.
   *
   * Blank is NOT a key, at any of the three sources. An empty `ZAI_API_KEY=` in `.vibe/.env` used to
   * win as a value: the request then went out with an empty Bearer, the vendor answered «no API
   * key», and the IDE — which had checked for null and found a string — reported nothing at all
   * (brought by the owner's brother 18.09.2026, as «берёт значение, даже если оно пустое»).
   */
  fun resolve(provider: ProviderEntry, projectBase: String?): String? {
    storedKey(provider)?.let { return it }
    provider.apiKeyEnv?.let { envName ->
      dotEnv(projectBase)[envName]?.takeIf { it.isNotBlank() }?.let { return it }
      System.getenv(envName)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
  }

  /** Where the key for this provider is looked for, named for the person: slot and variable. */
  fun sourceNames(provider: ProviderEntry): String =
    listOfNotNull(provider.apiKeyRef ?: provider.id, provider.apiKeyEnv).joinToString(" / ")

  /** Workspace `.vibe/.env` overrides `~/.vibe/.env` per variable. */
  internal fun dotEnv(projectBase: String?): Map<String, String> {
    val result = HashMap<String, String>()
    readEnvFile(Path.of(System.getProperty("user.home"), ".vibe", ".env"), result)
    projectBase?.let { readEnvFile(Path.of(it, ".vibe", ".env"), result) }
    return result
  }

  private fun readEnvFile(path: Path, into: MutableMap<String, String>) {
    if (!Files.isRegularFile(path)) return
    for (line in Files.readAllLines(path)) {
      val trimmed = line.trim()
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
      val eq = trimmed.indexOf('=')
      if (eq <= 0) continue
      into[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
    }
  }
}
