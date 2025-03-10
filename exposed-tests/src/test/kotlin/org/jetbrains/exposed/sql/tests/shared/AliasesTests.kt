package org.jetbrains.exposed.sql.tests.shared

import org.jetbrains.exposed.dao.entityCache
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.tests.DatabaseTestsBase
import org.jetbrains.exposed.sql.tests.shared.dml.withCitiesAndUsers
import org.jetbrains.exposed.sql.tests.shared.entities.EntityTestsData
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AliasesTests : DatabaseTestsBase() {
    @Test
    fun `test_github_issue_379_count_alias_ClassCastException`() {
        val stables = object : UUIDTable("Stables") {
            val name = varchar("name", 256).uniqueIndex()
        }

        val facilities = object : UUIDTable("Facilities") {
            val stableId = reference("stable_id", stables)
            val name = varchar("name", 256)
        }

        withTables(facilities, stables) {
            val stable1Id = stables.insertAndGetId {
                it[stables.name] = "Stables1"
            }
            stables.insertAndGetId {
                it[stables.name] = "Stables2"
            }
            facilities.insertAndGetId {
                it[facilities.stableId] = stable1Id
                it[facilities.name] = "Facility1"
            }
            val fcAlias = facilities.name.count().alias("fc")
            val fAlias = facilities.select(facilities.stableId, fcAlias).groupBy(facilities.stableId).alias("f")
            val sliceColumns = stables.columns + fAlias[fcAlias]
            val stats = stables.join(fAlias, JoinType.LEFT, stables.id, fAlias[facilities.stableId])
                .select(sliceColumns)
                .groupBy(*sliceColumns.toTypedArray()).map {
                    it[stables.name] to it[fAlias[fcAlias]]
                }.toMap()
            assertEquals(2, stats.size)
            assertEquals(1, stats["Stables1"])
            assertNull(stats["Stables2"])
        }
    }

    @Test
    fun testJoinSubQuery01() {
        withCitiesAndUsers { _, users, _ ->
            val expAlias = users.name.max().alias("m")
            val usersAlias = users.select(users.cityId, expAlias).groupBy(users.cityId).alias("u2")
            val resultRows = Join(users).join(usersAlias, JoinType.INNER, usersAlias[expAlias], users.name).selectAll().toList()
            assertEquals(3, resultRows.size)
        }
    }

    @Test
    fun testJoinSubQuery02() {
        withCitiesAndUsers { _, users, _ ->
            val expAlias = users.name.max().alias("m")

            val query = Join(users).joinQuery(on = { it[expAlias].eq(users.name) }) {
                users.select(users.cityId, expAlias).groupBy(users.cityId)
            }
            val innerExp = query.lastQueryAlias!![expAlias]

            assertEquals("q0", query.lastQueryAlias?.alias)
            assertEquals(3L, query.selectAll().count())
            assertNotNull(query.select(users.columns + innerExp).first()[innerExp])
        }
    }

    @Test
    fun `test wrap row with Aliased table`() {
        withTables(EntityTestsData.XTable, EntityTestsData.YTable) {
            val entity1 = EntityTestsData.XEntity.new {
                this.b1 = false
            }

            flushCache()
            entityCache.clear()

            val alias = EntityTestsData.XTable.alias("xAlias")
            val entityFromAlias = alias.selectAll().map { EntityTestsData.XEntity.wrapRow(it, alias) }.singleOrNull()
            assertNotNull(entityFromAlias)
            assertEquals(entity1.id, entityFromAlias.id)
            assertEquals(false, entityFromAlias.b1)
        }
    }

    @Test
    fun `test wrap row with Aliased query`() {
        withTables(EntityTestsData.XTable, EntityTestsData.YTable) {
            val entity1 = EntityTestsData.XEntity.new {
                this.b1 = false
            }

            flushCache()
            entityCache.clear()

            val alias = EntityTestsData.XTable.selectAll().alias("xAlias")
            val entityFromAlias = alias.selectAll().map { EntityTestsData.XEntity.wrapRow(it, alias) }.singleOrNull()
            assertNotNull(entityFromAlias)
            assertEquals(entity1.id, entityFromAlias.id)
            assertEquals(false, entityFromAlias.b1)
        }
    }

    @Test
    fun `test aliased expression with aliased query`() {
        withTables(EntityTestsData.XTable, EntityTestsData.YTable) {
            val dataToInsert = listOf(true, true, false, true)
            EntityTestsData.XTable.batchInsert(dataToInsert) {
                this[EntityTestsData.XTable.b1] = it
            }
            val aliasedExpression = EntityTestsData.XTable.id.max().alias("maxId")
            val aliasedQuery = EntityTestsData.XTable
                .select(EntityTestsData.XTable.b1, aliasedExpression)
                .groupBy(EntityTestsData.XTable.b1)
                .alias("maxBoolean")

            val aliasedBool = aliasedQuery[EntityTestsData.XTable.b1]
            val expressionToCheck = aliasedQuery[aliasedExpression]
            assertEquals("maxBoolean.maxId", expressionToCheck.toString())

            val resultQuery = aliasedQuery
                .leftJoin(EntityTestsData.XTable, { this[aliasedExpression] }, { id })
                .select(aliasedBool, expressionToCheck)

            val result = resultQuery.map {
                it[aliasedBool] to it[expressionToCheck]!!.value
            }

            assertEqualCollections(listOf(true to 4, false to 3), result)
        }
    }

    @Test fun `test alias for same table with join`() {
        withTables(EntityTestsData.XTable, EntityTestsData.YTable) {
            val table1Count = EntityTestsData.XTable.id.max().alias("t1max")
            val table2Count = EntityTestsData.XTable.id.max().alias("t2max")
            val t1Alias = EntityTestsData.XTable.select(table1Count).groupBy(EntityTestsData.XTable.b1).alias("t1")
            val t2Alias = EntityTestsData.XTable.select(table2Count).groupBy(EntityTestsData.XTable.b1).alias("t2")
            t1Alias.join(t2Alias, JoinType.INNER) {
                t1Alias[table1Count] eq t2Alias[table2Count]
            }.select(t1Alias[table1Count]).toList()
        }
    }
}
