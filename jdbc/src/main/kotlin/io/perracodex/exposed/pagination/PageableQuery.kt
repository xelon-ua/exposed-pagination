/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.perracodex.exposed.pagination

import io.perracodex.exposed.utils.QuerySorter
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select

/**
 * Retrieves a page from this [Query] based on the specified [pageable] parameters.
 * The query instance is **not** modified.
 *
 * If [pageable] is `null`, or the defined page size is `0`, the entire result set is retrieved without any pagination,
 * and a [Page] containing all domain models is returned.
 *
 * #### Usage:
 * ```
 * // Note that the actual model mapping has still to be done by the caller.
 * // See the `MapModel` interface for more information.
 *
 * fun findProduct(categoryId: Uuid, pageable: Pageable?): Page<Product> {
 *     return transaction {
 *         ProductTable
 *             .selectAll()
 *             .where { ProductTable.categoryId eq categoryId }
 *             .paginate(
 *                 pageable = pageable,
 *                 map = Product,
 *                 defaultOrder = listOf(ProductTable.createdAt to SortOrder.DESC),
 *                 tiebreaker = listOf(ProductTable.id to SortOrder.ASC)
 *             )
 *     }
 * }
 * ```
 *
 * @param pageable Optional [Pageable] containing pagination and sorting information. If `null`, no pagination is applied.
 * @param map Implementation of [MapModel] used to map [ResultRow] instances into domain models of type [T].
 * @param defaultOrder Order applied only when [pageable] is `null` or carries no sort directives.
 * @param tiebreaker Order applied last, after the client sort or [defaultOrder], to make the order deterministic.
 * Expressions the query is already ordered by are skipped.
 * @return A [Page] containing the list of mapped domain models and associated pagination metadata.
 *
 * @see [Page]
 * @see [Pageable]
 * @see [MapModel]
 * @see [Query.paginate]
 */
public fun <T : Any> Query.paginate(
    pageable: Pageable?,
    map: MapModel<T>,
    defaultOrder: List<Pair<Expression<*>, SortOrder>> = emptyList(),
    tiebreaker: List<Pair<Expression<*>, SortOrder>> = emptyList()
): Page<T> {
    // For paginated queries, a dedicated COUNT query is required since the main query
    // returns only a subset — unless the caller supplies a non-zero `knownTotal`. For
    // unpaginated queries, the total is derived from the fetched content.
    val isPaginated: Boolean = pageable != null && pageable.size > 0
    val precomputedTotal: Long? = if (isPaginated) {
        pageable.knownTotal?.takeIf { it > 0 }?.toLong() ?: this.count()
    } else {
        null
    }
    if (precomputedTotal == 0L) {
        return Page.empty(pageable = pageable)
    }

    // Apply pagination and capture the modified query.
    val paginatedQuery: Query = this.copy().paginate(
        pageable = pageable,
        defaultOrder = defaultOrder,
        tiebreaker = tiebreaker
    )

    // Map each row to the domain model.
    val content: List<T> = paginatedQuery.map { resultRow ->
        map.from(row = resultRow)
    }

    return Page.build(
        content = content,
        totalElements = precomputedTotal?.toInt() ?: content.size,
        pageable = pageable
    )
}

/**
 * Retrieves a page from this [Query] based on the specified [pageable] parameters.
 * The query instance is **not** modified.
 *
 * This is meant to be used by 1 -> N relationships where the query returns multiple rows per top-level entity.
 *
 * When performing queries that join a main entity with related entities,
 * it's essential to manage pagination in a way that accurately reflects the number of unique top-level entities.
 *
 * If [pageable] is `null`, or the defined page size is `0`, the entire result set is retrieved without any pagination,
 * and a [Page] containing all grouped domain models is returned.
 *
 * #### Usage:
 * ```
 * // Note that the actual model mapping has still to be done by the caller.
 * // See the `MapModel` interface for more information.
 *
 * fun findAll(pageable: Pageable?): Page<Employee> {
 *     return transaction {
 *         EmployeeTable
 *             .leftJoin(ContactTable)
 *             .leftJoin(EmploymentTable)
 *             .selectAll()
 *             .paginate(
 *                 pageable = pageable,
 *                 map = Employee,
 *                 groupBy = EmployeeTable.id
 *             )
 *     }
 * }
 * ```
 *
 * @param pageable Optional [Pageable] containing pagination and sorting information. If `null`, no pagination is applied.
 * @param map Implementation of [MapModel] used to map [ResultRow] instances into domain models of type [T].
 * @param groupBy The column used to group the top-entity results.
 * @param defaultOrder Order applied only when [pageable] is `null` or carries no sort directives.
 * @param tiebreaker Order applied last, after the client sort or [defaultOrder], to make the order deterministic.
 * Expressions the query is already ordered by are skipped.
 * @return A [Page] containing the list of mapped domain models and associated pagination metadata.
 *
 * @see [Page]
 * @see [Pageable]
 * @see [MapModel]
 * @see [Query.paginate]
 */
public fun <T : Any, K> Query.paginate(
    pageable: Pageable?,
    map: MapModel<T>,
    groupBy: Column<K>,
    defaultOrder: List<Pair<Expression<*>, SortOrder>> = emptyList(),
    tiebreaker: List<Pair<Expression<*>, SortOrder>> = emptyList()
): Page<T> {
    // For paginated queries, a dedicated COUNT DISTINCT query plus a key sub-query are required
    // to slice by top-level entity — unless the caller supplies a non-zero `knownTotal`. For
    // unpaginated queries, the total is derived from the number of distinct groups.
    val isPaginated: Boolean = pageable != null && pageable.size > 0
    val precomputedTotal: Long? = if (isPaginated) {
        pageable.knownTotal?.takeIf { it > 0 }?.toLong() ?: this.copy()
            .adjustSelect { select(groupBy.countDistinct()) }
            .first()[groupBy.countDistinct()]
    } else {
        null
    }
    if (precomputedTotal == 0L) {
        return Page.empty(pageable = pageable)
    }

    val records: List<ResultRow> = if (isPaginated) {
        // Construct the paginated keys query for the top-level entities,
        // then fetch the records that correspond to those keys.
        val paginatedKeys: Query = this.copy()
            .adjustSelect { select(groupBy) }
            .groupBy(groupBy)
            .paginate(pageable = pageable, defaultOrder = defaultOrder, tiebreaker = tiebreaker)

        // The sub-query order only selects the keys, so the same order is applied
        // to the main query to keep the page content in that order.
        this.copy()
            .andWhere { groupBy inSubQuery paginatedKeys }
            .applyOrder(pageable = pageable, defaultOrder = defaultOrder, tiebreaker = tiebreaker)
            .toList()
    } else {
        // Without pagination the key sub-query would match every top-level entity,
        // so the main query is fetched directly with sorting applied.
        this.copy().paginate(pageable = pageable, defaultOrder = defaultOrder, tiebreaker = tiebreaker).toList()
    }

    // Map each group of rows to the corresponding domain model.
    val grouped: Map<K, List<ResultRow>> = records.groupBy { it[groupBy] }
    val content: List<T> = grouped.mapNotNull { (_, groupRows) ->
        map.from(rows = groupRows)
    }

    return Page.build(
        content = content,
        totalElements = precomputedTotal?.toInt() ?: grouped.size,
        pageable = pageable
    )
}

/**
 * Applies pagination directives to this [Query] based on the provided [pageable] parameters.
 * The query instance is modified in-place.
 *
 * This extension function modifies the query in the following ways:
 * 1. **Sorting:** If [pageable] includes sorting directives, they are applied to the [Query];
 *    otherwise [defaultOrder] is applied. [tiebreaker] is applied last in either case.
 * 2. **Limiting and offset:**
 *    - If [pageable.position] is defined, it is used as the absolute zero-based starting position (offset).
 *    - Otherwise, the offset is calculated from the page number as `page * size`.
 *
 * If [pageable] is `null`, or the defined page size is `0`, the entire result set is retrieved without any pagination,
 * and a [Page] containing all domain models is returned.
 *
 * @param pageable Optional [Pageable] containing pagination and sorting information. If `null`, no pagination is applied.
 * @param defaultOrder Order applied only when [pageable] is `null` or carries no sort directives.
 * @param tiebreaker Order applied last, after the client sort or [defaultOrder], to make the order deterministic.
 * Expressions the query is already ordered by are skipped.
 * @return The modified [Query] with sorting applied, and pagination applied if [pageable] defines a page size.
 *
 * @see [Pageable]
 */
public fun Query.paginate(
    pageable: Pageable?,
    defaultOrder: List<Pair<Expression<*>, SortOrder>> = emptyList(),
    tiebreaker: List<Pair<Expression<*>, SortOrder>> = emptyList()
): Query {
    applyOrder(pageable = pageable, defaultOrder = defaultOrder, tiebreaker = tiebreaker)

    if (pageable != null && pageable.size > 0) {
        val startIndex: Long = pageable.position?.toLong() ?: (pageable.page.toLong() * pageable.size.toLong())
        this.limit(count = pageable.size)
            .offset(start = startIndex)
    }

    return this
}

/**
 * Applies the client sort from [pageable], or [defaultOrder] when it carries none,
 * followed by the [tiebreaker] expressions this [Query] is not yet ordered by.
 * The query instance is modified in-place.
 */
private fun Query.applyOrder(
    pageable: Pageable?,
    defaultOrder: List<Pair<Expression<*>, SortOrder>>,
    tiebreaker: List<Pair<Expression<*>, SortOrder>>
): Query {
    val sortDirectives: List<Pageable.PageSort>? = pageable?.sort
    if (sortDirectives.isNullOrEmpty()) {
        defaultOrder.forEach { (expression, order) ->
            this.orderBy(column = expression, order = order)
        }
    } else {
        QuerySorter.applyOrder(query = this, sortDirectives = sortDirectives)
    }

    val orderedBy: Set<Expression<*>> = this.orderByExpressions.map { it.first }.toSet()
    tiebreaker.filterNot { it.first in orderedBy }.forEach { (expression, order) ->
        this.orderBy(column = expression, order = order)
    }

    return this
}
