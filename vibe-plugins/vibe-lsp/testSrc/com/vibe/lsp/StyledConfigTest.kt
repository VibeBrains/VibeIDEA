// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
package com.vibe.lsp

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Признак «стили живут в шаблонных строках» — по нему решается, платит ли проект за плагин. */
class StyledConfigTest {
  @Test
  fun `styled-components и оба пакета emotion считаются признаком`() {
    assertTrue(StyledConfig.declaresStyled("""{"dependencies":{"styled-components":"^6.1.0"}}"""))
    assertTrue(StyledConfig.declaresStyled("""{"dependencies":{"@emotion/styled":"^11.0.0"}}"""))
    // Только `@emotion/react` — обычный случай: `css`-проп без `styled.div`.
    assertTrue(StyledConfig.declaresStyled("""{"dependencies":{"@emotion/react":"^11.0.0"}}"""))
    // DevDependencies тоже считаются: разбор нарочно не различает разделы — плагин нужен там, где
    // так ПИШУТ, а в каком разделе лежит пакет, к этому отношения не имеет.
    assertTrue(StyledConfig.declaresStyled("""{"devDependencies":{"styled-components":"6.1.0"}}"""))
  }

  @Test
  fun `похожее имя не делает проект проектом на styled-components`() {
    // Сравнение идёт с кавычками именно ради этого: подстрока совпала бы молча, и плагин уехал бы
    // в проект, который его не просил.
    assertFalse(StyledConfig.declaresStyled("""{"dependencies":{"styled-components-modifiers":"1.0.0"}}"""))
    assertFalse(StyledConfig.declaresStyled("""{"dependencies":{"@emotion/react-native":"1.0.0"}}"""))
  }

  @Test
  fun `нет package json — нет и признака`() {
    assertFalse(StyledConfig.declaresStyled(null))
    assertFalse(StyledConfig.declaresStyled(""))
    assertFalse(StyledConfig.isStyledProject(null))
    assertFalse(StyledConfig.isStyledProject("/нет/такого/проекта"))
  }
}
