/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.perracodex.exposed.pagination

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlLogger
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Tests for the `defaultOrder` and `tiebreaker` parameters of `paginate`.
 */
class PaginationOrderTest : FunSpec({

    // `status` is non-unique, `rank` is unique.
    val itemTable = object : Table("order_items") {
        val id = integer("id")
        val status = varchar("status", 8)
        val rank = integer("rank")
        override val primaryKey = PrimaryKey(id)
    }

    val tagTable = object : Table("order_tags") {
        val id = integer("id").autoIncrement()
        val itemId = reference("item_id", itemTable.id)
        val label = varchar("label", 16)
        override val primaryKey = PrimaryKey(id)
    }

    // Maps a row, or a group of rows, to the item id.
    val idMapper = object : MapModel<Int> {
        override fun from(row: ResultRow): Int = row[itemTable.id]
    }

    lateinit var database: Database

    fun pageable(size: Int, page: Int = 0, vararg sort: Pair<String, Pageable.PageDirection>): Pageable {
        return Pageable(
            page = page,
            position = null,
            size = size,
            sort = sort.map { (field, direction) -> Pageable.PageSort(field = field, direction = direction) }
        )
    }

    fun captureSql(block: () -> Unit): List<String> {
        val captured: MutableList<String> = mutableListOf()
        transaction(database) {
            addLogger(object : SqlLogger {
                override fun log(context: StatementContext, transaction: Transaction) {
                    captured += context.sql(transaction)
                }
            })
            block()
        }
        return captured
    }

    beforeSpec {
        database = Database.connect(
            url = "jdbc:h2:mem:pagination_order_test;DB_CLOSE_DELAY=-1;",
            driver = "org.h2.Driver"
        )
        transaction(database) {
            SchemaUtils.drop(tagTable, itemTable)
            SchemaUtils.create(itemTable, tagTable)
            // id:     1  2  3  4  5  6
            // status: A  B  A  B  A  B
            // rank:   3  1  2  6  5  4
            val statuses: List<String> = listOf("A", "B", "A", "B", "A", "B")
            val ranks: List<Int> = listOf(3, 1, 2, 6, 5, 4)
            (1..6).forEach { itemId ->
                itemTable.insert {
                    it[id] = itemId
                    it[status] = statuses[itemId - 1]
                    it[rank] = ranks[itemId - 1]
                }
                repeat(2) { index ->
                    tagTable.insert {
                        it[this.itemId] = itemId
                        it[label] = "tag-$itemId-$index"
                    }
                }
            }
        }
    }

    afterSpec {
        transaction(database) {
            SchemaUtils.drop(tagTable, itemTable)
        }
    }

    context("defaultOrder") {
        test("is applied when the pageable carries no sort") {
            val page: Page<Int> = transaction(database) {
                itemTable.selectAll().paginate(
                    pageable = pageable(size = 10),
                    map = idMapper,
                    defaultOrder = listOf(itemTable.rank to SortOrder.DESC)
                )
            }
            page.content shouldBe listOf(4, 5, 6, 1, 3, 2)
        }

        test("is applied when the pageable is null") {
            val page: Page<Int> = transaction(database) {
                itemTable.selectAll().paginate(
                    pageable = null,
                    map = idMapper,
                    defaultOrder = listOf(itemTable.rank to SortOrder.DESC)
                )
            }
            page.content shouldBe listOf(4, 5, 6, 1, 3, 2)
        }

        test("is ignored when the client sorts") {
            val page: Page<Int> = transaction(database) {
                itemTable.selectAll().paginate(
                    pageable = pageable(size = 10, sort = arrayOf("rank" to Pageable.PageDirection.ASC)),
                    map = idMapper,
                    defaultOrder = listOf(itemTable.id to SortOrder.DESC)
                )
            }
            page.content shouldBe listOf(2, 3, 1, 6, 5, 4)
        }
    }

    context("tiebreaker") {
        test("is applied after the client sort") {
            val page: Page<Int> = transaction(database) {
                itemTable.selectAll().paginate(
                    pageable = pageable(size = 10, sort = arrayOf("status" to Pageable.PageDirection.ASC)),
                    map = idMapper,
                    tiebreaker = listOf(itemTable.id to SortOrder.DESC)
                )
            }
            page.content shouldBe listOf(5, 3, 1, 6, 4, 2)
        }

        test("is applied when the client gives no sort") {
            val page: Page<Int> = transaction(database) {
                itemTable.selectAll().paginate(
                    pageable = pageable(size = 10),
                    map = idMapper,
                    tiebreaker = listOf(itemTable.id to SortOrder.DESC)
                )
            }
            page.content shouldBe listOf(6, 5, 4, 3, 2, 1)
        }

        test("is applied after the defaultOrder") {
            val page: Page<Int> = transaction(database) {
                itemTable.selectAll().paginate(
                    pageable = pageable(size = 10),
                    map = idMapper,
                    defaultOrder = listOf(itemTable.status to SortOrder.DESC),
                    tiebreaker = listOf(itemTable.id to SortOrder.ASC)
                )
            }
            page.content shouldBe listOf(2, 4, 6, 1, 3, 5)
        }

        test("does not repeat a column the client already sorted by") {
            var page: Page<Int>? = null
            val sql: List<String> = captureSql {
                page = itemTable.selectAll().paginate(
                    pageable = pageable(size = 10, sort = arrayOf("id" to Pageable.PageDirection.ASC)),
                    map = idMapper,
                    tiebreaker = listOf(itemTable.id to SortOrder.DESC)
                )
            }
            page?.content shouldBe listOf(1, 2, 3, 4, 5, 6)
            val orderBy: String = sql.last().substringAfter("ORDER BY")
            Regex("\\bID\\b", RegexOption.IGNORE_CASE).findAll(orderBy).count() shouldBe 1
        }
    }

    test("empty defaultOrder and tiebreaker leave the SQL unchanged") {
        val sql: List<String> = captureSql {
            itemTable.selectAll().paginate(pageable = pageable(size = 10), map = idMapper)
        }
        sql.last() shouldNotContain "ORDER BY"
    }

    context("groupBy overload") {
        test("keeps the client sort order within the page") {
            val page: Page<Int> = transaction(database) {
                itemTable.leftJoin(tagTable).selectAll().paginate(
                    pageable = pageable(size = 4, sort = arrayOf("rank" to Pageable.PageDirection.DESC)),
                    map = idMapper,
                    groupBy = itemTable.id
                )
            }
            page.content shouldBe listOf(4, 5, 6, 1)
        }

        test("applies defaultOrder and tiebreaker to the page slice and its order") {
            val page: Page<Int> = transaction(database) {
                itemTable.leftJoin(tagTable).selectAll().paginate(
                    pageable = pageable(size = 4),
                    map = idMapper,
                    groupBy = itemTable.id,
                    defaultOrder = listOf(itemTable.status to SortOrder.DESC),
                    tiebreaker = listOf(itemTable.id to SortOrder.DESC)
                )
            }
            page.content shouldBe listOf(6, 4, 2, 5)
            page.details.totalElements shouldBe 6
        }

        test("applies defaultOrder when the pageable is null") {
            val page: Page<Int> = transaction(database) {
                itemTable.leftJoin(tagTable).selectAll().paginate(
                    pageable = null,
                    map = idMapper,
                    groupBy = itemTable.id,
                    defaultOrder = listOf(itemTable.rank to SortOrder.ASC)
                )
            }
            page.content shouldBe listOf(2, 3, 1, 6, 5, 4)
        }
    }
})
