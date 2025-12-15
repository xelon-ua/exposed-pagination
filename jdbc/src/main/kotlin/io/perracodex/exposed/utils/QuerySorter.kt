/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package io.perracodex.exposed.utils

import io.perracodex.exposed.pagination.Pageable
import io.perracodex.exposed.pagination.PaginationError
import io.perracodex.exposed.utils.QuerySorter.columnCache
import io.perracodex.exposed.utils.QuerySorter.generateCacheKey
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Query
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.memberProperties

/**
 * Responsible for determining and applying column-based ordering to database [Query] instances.
 *
 * It handles the resolution of columns across multiple tables, manages caching for performance optimization,
 * and ensures that sorting directives are applied unambiguously.
 *
 * **Key Responsibilities:**
 * - **Sorting Application:** Applies multiple sorting directives to a [Query].
 * - **Column Resolution:** Resolves column references from sorting directives, handling ambiguities.
 * - **Caching:** Utilizes a cache to manage column references, minimizing the overhead of reflection-based resolution.
 *
 * @see [Pageable.PageSort]
 * @see [Query]
 * @see [PaginationError]
 */
internal object QuerySorter {
    private val tracer = Tracer<QuerySorter>()

    /**
     * A cache that stores resolved column references to optimize column lookup operations.
     *
     * This cache maps a unique key, generated based on query tables and sorting directives,
     * to their corresponding [Column] instances. By caching these references, the [QuerySorter]
     * minimizes the performance cost associated with reflection-based column resolution.
     *
     * @see [generateCacheKey]
     */
    private val columnCache: MutableMap<String, Column<*>> = ConcurrentHashMap()

    /**
     * Applies the specified sorting directives to the given [Query], by processing
     * each [Pageable.PageSort] directive, resolves the corresponding [Column] within
     * the context of the query's tables, and applies the sorting order to the [Query].
     *
     * @param query The [Query] instance to which the sorting directives will be applied.
     * @param sortDirectives A list of [Pageable.PageSort] directives defining the sorting order.
     * @throws PaginationError.InvalidSortDirective If a sort directive references a non-existent field or table.
     * @throws PaginationError.AmbiguousSortField If a sort directive's field is found in multiple tables
     * without explicit table specification.
     *
     * @see [Pageable.PageSort]
     * @see [PaginationError]
     */
    fun applyOrder(query: Query, sortDirectives: List<Pageable.PageSort>) {
        if (sortDirectives.isEmpty()) {
            return
        }

        val queryTables: List<Table> = query.targets.distinct()

        sortDirectives.forEach { sort ->
            // Get the list of query tables to resolve the field from the sort directive.
            val targetTables: List<Table> = findTargetTables(queryTables = queryTables, sort = sort)

            // Retrieve the column from the target tables.
            val key: String = generateCacheKey(queryTables = queryTables, sort = sort)
            val column: Column<*> = getColumn(
                key = key,
                sort = sort,
                targets = targetTables
            )

            // Apply the sorting order to the query based on the direction
            // specified in the Pageable.
            val sortOrder: SortOrder = when (sort.direction) {
                Pageable.PageDirection.ASC -> SortOrder.ASC
                Pageable.PageDirection.DESC -> SortOrder.DESC
            }
            query.orderBy(column to sortOrder)
        }
    }

    /**
     * Identifies the target tables within the [Query] that correspond to a given [sort] directive.
     *
     * This method filters the list of tables involved in the query based on the table name
     * specified in the [Pageable.PageSort] directive. If a table name is provided, it selects
     * only the table that matches the specified name. If no table name is specified,
     * it returns all tables involved in the [Query].
     *
     * @param queryTables The list of all the [Table] instances involved in the current [Query].
     * @param sort The [Pageable.PageSort] directive containing the table name (if any) and field name.
     *
     * @return A list of [Table] objects that match the sorting directive's table name, or all tables if none is specified.
     * @throws PaginationError.InvalidSortDirective If the specified table name does not exist within the query's tables.
     *
     * @see [Pageable.PageSort]
     * @see [PaginationError]
     */
    private fun findTargetTables(queryTables: List<Table>, sort: Pageable.PageSort): List<Table> {
        return sort.table?.let { tableName ->
            queryTables.filter { table ->
                table.tableName.equals(other = tableName, ignoreCase = true)
            }.distinct().takeIf { tables ->
                tables.isNotEmpty()
            } ?: throw PaginationError.InvalidSortDirective(
                sort = sort,
                reason = "'$tableName' is not recognized as part of the Query tables."
            )
        } ?: queryTables
    }

    /**
     * Attempts to retrieve the [Column] associated with a sorting directive, utilizing caching for efficiency.
     *
     * This method first attempts to fetch the [Column] from the [columnCache] using the provided key.
     * If the column is not cached, it resolves the column through reflection by searching the target
     * tables for a matching field name. Upon successful resolution, the column is cached for future
     * retrievals.
     *
     * @param key A unique string key generated from the query tables and sort directive.
     * @param sort The [Pageable.PageSort] directive containing the field name and sort direction.
     * @param targets A list of [Table] instances to search for the corresponding column.
     * @return The resolved [Column] instance corresponding to the sort directive.
     * @throws PaginationError.InvalidSortDirective If the field specified in the sort directive does not exist in the target tables.
     * @throws PaginationError.AmbiguousSortField If the field exists in multiple tables without explicit table specification.
     *
     * @see [Pageable.PageSort]
     * @see [PaginationError]
     */
    private fun getColumn(key: String, sort: Pageable.PageSort, targets: List<Table>): Column<*> {
        // Check if the column is already cached, and return it if found.
        columnCache[key]?.let { column ->
            return column
        }

        // Attempt to resolve the column from the target tables, which are part of the query.
        val columns: List<Column<*>> = targets.asSequence().flatMap { table ->
            resolveTableColumn(table = table, fieldName = sort.field)
        }.distinct().toList()

        if (columns.isEmpty()) {
            // If no columns are found, it implies the field is not part of the query tables.
            throw PaginationError.InvalidSortDirective(sort = sort, reason = "Field not found in query tables.")
        } else if (columns.size > 1) {
            // If multiple columns are found, it implies ambiguity between tables.
            val reason = "'${sort.field}' found in: ${columns.joinToString { it.table.tableName }}"
            throw PaginationError.AmbiguousSortField(sort = sort, reason = reason)
        }

        // Cache the column and return it.
        val column: Column<*> = columns.single()
        columnCache[key] = column
        return column
    }

    /**
     * Resolves a [Column] within a specific [Table] based on the provided field name,
     * by using reflection to search for a property within the [Table] class that matches
     * the specified [fieldName]. It filters properties to identify those that are of type [Column]
     * and have a name matching the field name (case-insensitive).
     *
     * Using the Table class properties to resolve the columns instead of the actual table field name
     * at database level, allows to abstract clients from the actual database field name definition,
     * which could be different from the property name in the Table class.
     *
     * @param table The [Table] in which to search for the column.
     * @param fieldName The name of the field representing the column to be resolved.
     * @return A list of [Column] instances that match the specified field name within the table.
     *
     * **Note:** Typically, this list should contain either zero or one [Column]. Multiple matches indicate
     * a configuration issue where multiple columns share the same field name within a table.
     */
    private fun resolveTableColumn(table: Table, fieldName: String): List<Column<*>> {
        return table::class.memberProperties.filter { property ->
            // Look for a property in the table class that matches the field name and is a Column type.
            property.returnType.classifier == Column::class &&
                    property.name.equals(other = fieldName, ignoreCase = true)
        }.mapNotNull { property ->
            runCatching {
                // Attempt to retrieve the Column property from the table.
                tracer.debug("Column matched. ${table.tableName}::${property.name}.")
                return@runCatching property.getter.call(table) as? Column<*>
            }.onFailure { exception ->
                // Log the exception if the reflection call fails, as it may indicate a misconfiguration.
                tracer.error(message = "Failed to access column. ${table.tableName}::${property.name}", cause = exception)
            }.getOrNull()
        }
    }

    /**
     * Generates a unique cache key based on the [Query] tables and a [Pageable.PageSort] directive.
     *
     * The cache key is constructed by concatenating the names of all tables involved in the [Query],
     * followed by the table name (if specified) the field belongs to, and finally the field name.
     * This unique key ensures that cached columns are associated only with their respective query
     * contexts and sorting criteria.
     *
     * **Example Key Format:**
     * - With table specified: `"employees::departments::contracts=employees.firstName"`
     * - Without table specified: `"employees::departments::contracts=firstName"`
     *
     * @param queryTables The list of [Table] objects involved in the [Query].
     * @param sort The [Pageable.PageSort] directive containing the table name (optional) and field name.
     * @return A unique string key representing the combination of query tables and sorting directive.
     */
    private fun generateCacheKey(queryTables: List<Table>, sort: Pageable.PageSort): String {
        val tableNames: String = queryTables.joinToString("::") { it.tableName.lowercase() }
        return "$tableNames=${sort.table?.lowercase()}.${sort.field.lowercase()}"
    }
}
