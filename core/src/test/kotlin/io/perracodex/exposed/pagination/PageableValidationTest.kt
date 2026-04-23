/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.perracodex.exposed.pagination

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [Pageable] constructor validation.
 */
class PageableValidationTest : FunSpec({

    test("valid pageable with size > 0 and page > 0 should be allowed") {
        val pageable = Pageable(page = 3, position = null, size = 10)
        pageable.page shouldBe 3
        pageable.size shouldBe 10
    }

    test("valid pageable with size > 0 and position > 0 should be allowed") {
        val pageable = Pageable(page = 0, position = 50, size = 10)
        pageable.position shouldBe 50
    }

    test("valid pageable with size = 0 and page = 0 should be allowed") {
        val pageable = Pageable(page = 0, position = null, size = 0)
        pageable.size shouldBe 0
    }

    test("valid pageable with size = 0, page = 0 and position = 0 should be allowed") {
        val pageable = Pageable(page = 0, position = 0, size = 0)
        pageable.position shouldBe 0
    }

    test("size = 0 with page > 0 should throw") {
        shouldThrow<IllegalArgumentException> {
            Pageable(page = 1, position = null, size = 0)
        }.message shouldBe "Page index must be 0 when size is 0 (no pagination)."
    }

    test("size = 0 with position > 0 should throw") {
        shouldThrow<IllegalArgumentException> {
            Pageable(page = 0, position = 5, size = 0)
        }.message shouldBe "Position must be null or 0 when size is 0 (no pagination)."
    }

    test("size = 0 with both page > 0 and position > 0 should throw on page check first") {
        shouldThrow<IllegalArgumentException> {
            Pageable(page = 2, position = 100, size = 0)
        }.message shouldBe "Page index must be 0 when size is 0 (no pagination)."
    }

    test("negative page should throw") {
        shouldThrow<IllegalArgumentException> {
            Pageable(page = -1, position = null, size = 10)
        }.message shouldBe "Page index must be >= 0."
    }

    test("negative size should throw") {
        shouldThrow<IllegalArgumentException> {
            Pageable(page = 0, position = null, size = -1)
        }.message shouldBe "Page size must be >= 0. (0 means all elements)."
    }

    test("negative position should throw") {
        shouldThrow<IllegalArgumentException> {
            Pageable(page = 0, position = -1, size = 10)
        }.message shouldBe "Position must be >= 0 when provided."
    }
})
