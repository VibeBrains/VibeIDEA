# Список-попап под полем ввода без потери фокуса (меню `@` композера)

**Роль:** [архитектура] · 2026-08-23 · волна B композера (`com.vibe.agent.ui.composer.MentionPopup`).

## Контекст

Меню `@`-контекста должно висеть под `JTextArea`, пока пользователь продолжает печатать фильтр в самом поле: стрелки/Enter/Esc уходят в список, буквы — в текст. Очевидные платформенные пути не подходят: `ListPopupImpl`/`ActionGroupPopup` заточены на попап с фокусом (`WizardPopup.dispatch` игнорирует клавиши, когда попап не активен), а `LookupManager`/`TextFieldWithCompletion` требуют настоящий `Editor`, не Swing-компонент.

## Суть

Паттерн из платформы — путь-поле файлового чузера `FileTextFieldImpl` (platform-impl, `openapi/fileChooser/ex`):

- свой `JBList` + `PopupChooserBuilder(list).setRequestFocus(false).setCancelKeyEnabled(false).setAutoSelectIfEmpty(false).setFocusOwners(arrayOf(textArea))` — попап не забирает фокус и не закрывается по Esc сам;
- **клавиши навигации — только локальные шорткаты** (`DumbAwareAction.registerCustomShortcutSet(..., textArea, disposable)`, `update` включает их лишь пока меню открыто). `KeyListener`/`InputMap` на текстовом компоненте **не работают** для стрелок, Backspace, Esc: `IdeKeyEventDispatcher` пропускает в Swing только голые буквы/цифры (`IdeKeyEventDispatcher.kt:314-321`), всё остальное уходит в keymap, где `EditorUp/EditorBackSpace/…` — `TextComponentEditorAction`, включённые на любом `JTextComponent`. Единственное, что старше keymap, — локальные шорткаты компонента. Plain Enter для отправки в `InputMap` работает лишь потому, что в контексте тулвиндоу на него нет включённых keymap-экшенов;
- отключённый локальный экшен (`isEnabled = false`) пропускает событие дальше — так Backspace с пустым условием «снять чип» остаётся обычным удалением, а Esc без открытого меню и генерации — платформенным «фокус в редактор»;
- при открытом меню Enter без модификаторов — выбор (локальный шорткат выигрывает у Swing-Enter композера), Shift+Enter проваливается в перенос строки, ← в корне не глушится — каретка уходит левее `@` и меню закрывается через синхронизацию с документом;
- `showUnderneathOf` **не** переворачивает попап вверх при нехватке места — `AbstractPopup` лишь сдвигает его в экран и накрывает поле. Для композера под лентой место считается вручную: снизу, если влезает, иначе над полем (`showInScreenCoordinates` + `pack` + `setLocation`);
- поиск по индексу — только `ReadAction.nonBlocking(...).coalesceBy(this).expireWith(this).finishOnUiThread(ModalityState.any())`, дебаунс `Alarm`; dumb-режим даёт одну строку «Индексация…», а не исключение.

## Применение

Любое «меню под полем» (slash-команды, упоминания, автодополнение в Swing-поле) — по этому же скелету. Проверять глазами: фокус остаётся в поле, стрелки двигают подсветку (а не каретку), Enter при открытом меню не отправляет сообщение, Backspace в пустом поле снимает чип, Esc закрывает меню, а не уводит фокус.

## Связано

ACP-блоки контекста (`resource` при `promptCapabilities.embeddedContext`, иначе `resource_link`; `image` при `promptCapabilities.image`; режимы `session/new.modes` + `session/set_mode` + `current_mode_update`) — в `acp/AcpTypes.kt`; факты сняты со схемы `@agentclientprotocol/sdk` 0.14.1 и адаптера `claude-agent-acp` 0.70.0 (`~/.npm/_npx/*/node_modules/@agentclientprotocol/`).
