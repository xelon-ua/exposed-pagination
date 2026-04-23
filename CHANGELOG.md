# Changelog

All notable changes to this project are documented in this file.

## [1.2.1] - 2026-04-23

### Changed

- Optimized `Query.paginate` to skip the redundant `COUNT` query when no pagination is applied
  (`pageable` is `null` or `size == 0`); `totalElements` is now derived from the fetched content
  in that case.
- Optimized the `groupBy` overload of `Query.paginate`: when no pagination is applied, it now
  issues a single query instead of three (previously `COUNT DISTINCT` + keys sub-query + main
  fetch); the distinct total is derived from the number of groups in the result.

Paginated behavior (`size > 0`) is unchanged — `COUNT` is still issued to keep `totalPages`,
`hasNext`, and `isOverflow` metadata correct.

## [1.2.0] - 2026-04-16

### Changed

- Updated Exposed from `1.0.0-rc-4` to `1.2.0`.
- Updated Kotlin from `2.2.21` to `2.3.20`.
- Updated Ktor from `3.3.2` to `3.4.2`.
- Updated kotlinx-serialization from `1.9.0` to `1.11.0`.
- Updated Kotest from `6.0.0.M1` to `6.1.5`.
