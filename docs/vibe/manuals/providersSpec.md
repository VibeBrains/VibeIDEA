# Спека: providers.json — свои LLM-провайдеры

> Скопируйте этот файл целиком своей LLM и попросите собрать `providers.json` под вашего провайдера — этого документа достаточно, в интернет и исходники ходить не нужно.

## Что это

Реестр LLM-провайдеров для прямого чата в панели **Vibe Agent** (записи вида `LLM: <провайдер> · <модель>` в выпадашке). Контракт совместим с VibeIDE (форк VSCode) — один файл переносится между продуктами. Ключи API в файле **не хранятся никогда** — файл можно коммитить.

## Где лежит

- `~/.vibe/providers.json` — глобальный (все проекты);
- `<проект>/.vibe/providers.json` — проектный; при совпадении `id` **перекрывает глобальный по полям**, `models.static` мёржится по id модели; провайдеры только из проекта добавляются после глобальных.
- Файлы разбираются независимо: сломанный проектный не гасит глобальных провайдеров; битая запись пропускается с предупреждением в чате и не роняет реестр.
- Формат JSONC: `//`-комментарии и висячие запятые разрешены. Изменения подхватываются при перезапуске IDE.

## Формат

```jsonc
{
  "version": 1,
  "providers": [
    {
      // Обязательное: id (уникальный) и baseURL.
      "id": "ollama",
      "name": "Ollama (локально)",
      "protocol": "openai",              // openai (дефолт) | anthropic | gemini
      "baseURL": "http://localhost:11434/v1",
      "auth": "bearer",                  // "bearer" | {"type":"header","name":"x-api-key"} | {"type":"query","name":"key"}
      "apiKeyEnv": "OLLAMA_API_KEY",     // имя переменной: ищется в <проект>/.vibe/.env → ~/.vibe/.env → окружении ОС
      "apiKeyRef": "ollama",             // или ключ из защищённого хранилища ОС (PasswordSafe), сильнее apiKeyEnv
      "headers": {},                     // статические заголовки
      "query": {},                       // статические query-параметры
      "timeoutMs": 600000,
      "active": true,                    // false выключает провайдера и его модели
      "order": 10,                       // порядок среди ваших провайдеров (меньше = выше)
      "models": {
        // fetch: true (дефолт) = авто-каталог с <baseURL>/models; строка = полный URL каталога;
        // false = только static. Ответ понимается и openai-вида {data:[{id}]}, и gemini-вида {models:[{name}]}.
        // Найденные модели добавляются к static; static сильнее по совпавшему id.
        "fetch": true,
        "static": [
          {
            "id": "qwen3:14b",           // обязательное: как принимает API
            "name": "Qwen3 14B",
            "default": true,             // модель по умолчанию (первая в списке)
            "pinned": false,
            "active": true,
            "contextWindow": 131072,
            "maxOutputTokens": 8192,
            "temperature": 0.7,
            "topP": 0.95,
            "topK": 40,
            "extraBody": {},             // доливается в тело запроса дословно (квирки вендора)
            "fim": false                 // поддержка автокомплита (потребителя пока нет — поле читается)
          }
        ]
      }
    },
    // Клон: новый id + extends — унаследовать все поля другой записи и переопределить нужные.
    { "id": "ollama-fast", "extends": "ollama", "name": "Ollama fast", "models": { "static": [ { "id": "qwen3:4b", "default": true } ] } }
  ]
}
```

## Ключи API

Приоритет резолва: `apiKeyRef` (защищённое хранилище ОС) → `.vibe/.env` проекта → `~/.vibe/.env` → переменная окружения ОС (имя из `apiKeyEnv`). Формат `.env`: строки `ИМЯ=значение`, `#`-комментарии; интерполяции нет; файл в `.gitignore`. Для localhost-провайдера ключ не обязателен, чат помечается меткой `[локальная модель]`.

Точный путь: клиент дописывает к `baseURL` только имя метода — anthropic: `/messages`, openai: `/chat/completions`, gemini: `/models/<id>:streamGenerateContent` — поэтому версию пути включайте в `baseURL` сами: `https://api.anthropic.com/v1`, `http://localhost:11434/v1`, `https://generativelanguage.googleapis.com/v1beta`. Для gemini дефолтный auth-способ — `{"type":"query","name":"key"}` либо заголовок `x-goog-api-key` (auth `header`).

## Безопасность (Config Guard)

При загрузке реестр сканируется чистой функцией; находки печатаются в чат:
- `provider-endpoint-non-https` (critical) — не-HTTPS endpoint (localhost/127.0.0.1 легитимны, не флагаются);
- `provider-endpoint-raw-ip` (high) — сырой IP;
- `provider-hardcoded-secret` (critical) — креды в URL (`user:pass@`) или секрет-подобный литерал в `headers`/`query`.

## Отличия от VibeIDE (честные границы этой реализации)

- Встроенных провайдеров нет → патч встроенного по id и `extends` встроенного невозможны; `extends` работает между записями ваших файлов.
- `models.fetch` по умолчанию ходит на `<baseURL>/models` (в VibeIDE — `<baseURL>/v1/models`): у нас `baseURL` всегда включает версионный корень. Нестандартный путь — строкой с полным URL, как и в VibeIDE.
- Потребителя `fim` (автокомплит) пока нет; `maxTools`/`maxPromptDirectoryChars` не читаются (нет потребителей).
- Кэша последнего рабочего набора и вотчера файлов нет — перезапуск IDE.

## Проверка

Создайте `~/.vibe/providers.json` с провайдером выше → перезапустите VibeIDEA → в панели Vibe Agent в выпадашке появятся `LLM: …`-записи → выберите и напишите сообщение: ответ стримится прямо с endpoint, без ACP-агента.
