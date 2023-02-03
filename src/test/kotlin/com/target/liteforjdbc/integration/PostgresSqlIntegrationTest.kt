package com.target.liteforjdbc.integration

import com.target.liteforjdbc.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.ResultSet
import java.time.Instant


@Testcontainers
class PostgresSqlIntegrationTest {
    companion object {
        lateinit var db: Db
        var postgres: GenericContainer<*> = GenericContainer(DockerImageName.parse("postgres:15.1"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "test")
            .withEnv("POSTGRES_PASSWORD", "password")

        @BeforeAll
        @JvmStatic
        fun setUp() {
            postgres.start()
            val dbConfig = DbConfig(
                type = DbType.POSTGRES,
                host = postgres.host,
                port = postgres.firstMappedPort,
                username = "postgres",
                password = "password",
                databaseName = "test"
            )
            db = Db(dbConfig)
            // Now we have an address and port for Redis, no matter where it is running
            db.executeUpdate("CREATE TYPE annoyed_parent_type AS ENUM ( 'ONE', 'TWO', 'TWO_AND_A_HALF', 'THREE' )")
            db.executeUpdate("CREATE TABLE T ( id INT, field1 VARCHAR(255), field2 INT, field3 TIMESTAMP, annoyed_parent annoyed_parent_type )")

            db.executeUpdate("CREATE TABLE KEY_GEN_T ( id SERIAL, field1 VARCHAR(255), field2 INT, field3 TIMESTAMP DEFAULT CURRENT_TIMESTAMP, annoyed_parent annoyed_parent_type )")

            db.executeUpdate("INSERT INTO T (id, field1, field2, field3, annoyed_parent) VALUES (1, 'First', 10, TIMESTAMP '1970-01-01 00:00:00', 'ONE')")
            db.executeUpdate("INSERT INTO T (id, field1, field2, field3, annoyed_parent) VALUES (2, 'Second', 20, TIMESTAMP '1970-01-01 00:00:01', 'TWO')")
            db.executeUpdate("INSERT INTO T (id, field1, field2, field3, annoyed_parent) VALUES (3, 'Third', 30, TIMESTAMP '1970-01-01 00:00:02', 'TWO_AND_A_HALF')")

        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            postgres.stop()
        }
    }


    @Test
    fun testUseConnection() {
        db.useConnection { conn ->
            val ps = conn.prepareStatement("SELECT * FROM pg_catalog.pg_tables;")
            val rs = ps.executeQuery()
            rs.next() shouldBe true
        }
    }

    @Test
    fun testUsePreparedStatement() {
        db.usePreparedStatement("SELECT * FROM pg_catalog.pg_tables;") { ps ->
            val rs = ps.executeQuery()
            rs.next() shouldBe true
        }
    }

    @Test
    fun testUseNamedParamPreparedStatement() {
        db.useNamedParamPreparedStatement("SELECT * FROM T WHERE id = :id") { ps ->
            ps.setInt("id", 1)
            val rs = ps.executeQuery()
            rs.next() shouldBe true
            val field1Val = rs.getString("field1")
            field1Val shouldBe "First"
        }
    }

    @Test
    fun testExecuteUpdate() {
        db.executeUpdate("INSERT INTO T (id, field1, field2, annoyed_parent) VALUES (123, 'Temp', 10, 'ONE')")
        var count = db.executeQuery("SELECT COUNT(*) cnt FROM T WHERE id = 123", rowMapper = countResultSetMap)
        count shouldBe 1
        db.executeUpdate("DELETE FROM T WHERE id = 123")
        count = db.executeQuery("SELECT COUNT(*) cnt FROM T WHERE id = 123", rowMapper = countResultSetMap)
        count shouldBe 0
    }

    @Test
    fun `a reused named parameter should populate all ordinal parameters`() {
        val count = db.executeQuery(
            sql = "SELECT COUNT(*) cnt FROM T WHERE id = :id and id = :id",
            args = mapOf("id" to 1),
            rowMapper = countResultSetMap
        )
        count shouldBe 1
    }

    @Test
    fun testExecuteUpdatePositionalParams() {
        db.executeUpdatePositionalParams(
            "INSERT INTO T (id, field1, field2, field3, annoyed_parent) VALUES (?, ?, ?, ?, ?::annoyed_parent_type)",
            123,
            "Temp",
            10,
            Instant.ofEpochMilli(0),
            AnnoyedParent.TWO
        )
        var count = db.executeQuery("SELECT COUNT(*) cnt FROM T WHERE id = 123", rowMapper = countResultSetMap)
        count shouldBe 1
        db.executeUpdatePositionalParams("DELETE FROM T WHERE id = ?", 123)
        count = db.executeQuery("SELECT COUNT(*) cnt FROM T WHERE id = 123", rowMapper = countResultSetMap)
        count shouldBe 0
    }

    @Test
    fun testExecuteWithParams() {
        db.executeUpdate(
            "INSERT INTO T (id, field1, field2, field3, annoyed_parent) VALUES (:id, :field1, :field2, :field3, CAST(:annoyedParent as annoyed_parent_type))",
            mapOf(
                "id" to 123,
                "field1" to "Temp",
                "field2" to 10,
                "field3" to Instant.ofEpochMilli(0),
                "annoyedParent" to AnnoyedParent.TWO
            )
        )
        var count = db.executeQuery("SELECT COUNT(*) cnt FROM T WHERE id = 123", rowMapper = countResultSetMap)
        count shouldBe 1
        db.executeUpdate(
            "DELETE FROM T WHERE id = :id",
            mapOf("id" to 123)
        )
        count = db.executeQuery("SELECT COUNT(*) cnt FROM T WHERE id = 123", rowMapper = countResultSetMap)
        count shouldBe 0
    }

    @Test
    fun testExecuteQuery() {
        val result = db.executeQuery(
            "SELECT COUNT(*) cnt FROM T",
            rowMapper = countResultSetMap
        )


        checkNotNull(result)
        result::class shouldBe Int::class
        result shouldBe 3
    }

    @Test
    fun testExecuteWithGeneratedKeys() {
        clearKeyGenTable()

        val ids = db.executeWithGeneratedKeys(
            "INSERT INTO KEY_GEN_T (field1, field2, field3, annoyed_parent) VALUES (:field1, :field2, :field3, CAST(:annoyedParent as annoyed_parent_type))",
            mapOf(
                "field1" to "Temp",
                "field2" to 10,
                "field3" to Instant.ofEpochMilli(0),
                "annoyedParent" to AnnoyedParent.THREE
            )
        ) { resultSet: ResultSet -> resultSet.getInt("id") }

        val finalCount = tableKeyGenCount()

        val expectedId = tableKeyGenIdByField1("Temp")
        clearKeyGenTable()

        ids.size shouldBe 1
        ids[0] shouldBe expectedId
        finalCount shouldBe 1
    }

    @Test
    fun testExecuteWithGeneratedKeysPositionalParams() {
        clearKeyGenTable()

        val ids = db.executeWithGeneratedKeysPositionalParams(
            "INSERT INTO KEY_GEN_T (field1, field2, field3, annoyed_parent) VALUES (?, ?, ?, ?::annoyed_parent_type)",
            { resultSet: ResultSet -> resultSet.getInt("id") },
            "Temp", 10, Instant.ofEpochMilli(0), AnnoyedParent.TWO_AND_A_HALF
        )

        val finalCount = tableKeyGenCount()

        val expectedId = tableKeyGenIdByField1("Temp")
        clearKeyGenTable()

        ids.size shouldBe 1
        ids[0] shouldBe expectedId
        finalCount shouldBe 1
    }

    @Test
    fun testExecuteBatch() {
        clearKeyGenTable()

        val result = db.executeBatch(
            "INSERT INTO KEY_GEN_T (field1, field2, field3, annoyed_parent) VALUES (:field1, :field2, :field3, CAST(:annoyedParent as annoyed_parent_type))",
            listOf(
                mapOf(
                    "field1" to "Temp",
                    "field2" to 10,
                    "field3" to Instant.ofEpochMilli(0),
                    "annoyedParent" to AnnoyedParent.ONE
                ),
                mapOf(
                    "field1" to "Temp2",
                    "field2" to 11,
                    "field3" to Instant.ofEpochMilli(1),
                    "annoyedParent" to AnnoyedParent.TWO
                ),
            )
        )

        val finalCount = tableKeyGenCount()
        clearKeyGenTable()

        finalCount shouldBe 2
        result.size shouldBe 2
        result[0] shouldBe -2
        result[1] shouldBe -2
    }

    @Test
    fun testExecuteBatchWithGeneratedKeys() {
        clearKeyGenTable()

        val result = db.executeBatch(
            "INSERT INTO KEY_GEN_T (field1, field2, field3, annoyed_parent) VALUES (:field1, :field2, :field3, CAST(:annoyedParent as annoyed_parent_type))",
            listOf(
                mapOf(
                    "field1" to "Temp",
                    "field2" to 10,
                    "field3" to Instant.ofEpochMilli(0),
                    "annoyedParent" to AnnoyedParent.ONE
                ),
                mapOf(
                    "field1" to "Temp2",
                    "field2" to 11,
                    "field3" to Instant.ofEpochMilli(1),
                    "annoyedParent" to AnnoyedParent.TWO_AND_A_HALF
                ),
            )
        ) { resultSet: ResultSet -> resultSet.getInt("id") }

        val finalCount = tableKeyGenCount()
        val expectedId1 = tableKeyGenIdByField1("Temp")
        val expectedId2 = tableKeyGenIdByField1("Temp2")
        clearKeyGenTable()

        result.size shouldBe 2
        result[0] shouldBe expectedId1
        result[1] shouldBe expectedId2
        finalCount shouldBe 2
    }

    @Test
    fun testExecuteBatchPositionalParams() {
        clearKeyGenTable()

        val rowCounts = db.executeBatchPositionalParams(
            "INSERT INTO KEY_GEN_T (field1, field2, field3, annoyed_parent) VALUES (?, ?, ?, CAST(? as annoyed_parent_type))",
            listOf(
                listOf("Temp", 10, Instant.ofEpochMilli(0), AnnoyedParent.TWO),
                listOf("Temp2", 11, Instant.ofEpochMilli(1), AnnoyedParent.TWO_AND_A_HALF),
            )
        )

        val finalCount = tableKeyGenCount()
        clearKeyGenTable()

        finalCount shouldBe 2
        rowCounts.size shouldBe 2
        rowCounts[0] shouldBe -2
        rowCounts[0] shouldBe -2
    }

    @Test
    fun testExecuteBatchPositionalParamsWithGeneratedKeys() {
        clearKeyGenTable()

        val result = db.executeBatchPositionalParams(
            "INSERT INTO KEY_GEN_T (field1, field2, field3, annoyed_parent) VALUES (?, ?, ?, ?::annoyed_parent_type)",
            listOf(
                listOf("Temp", 10, Instant.ofEpochMilli(0), AnnoyedParent.ONE),
                listOf("Temp2", 11, Instant.ofEpochMilli(1), AnnoyedParent.TWO),
            )
        ) { resultSet: ResultSet -> resultSet.getInt("id") }

        val finalCount = tableKeyGenCount()
        val expectedId1 = tableKeyGenIdByField1("Temp")
        val expectedId2 = tableKeyGenIdByField1("Temp2")
        clearKeyGenTable()

        finalCount shouldBe 2
        result.size shouldBe 2
        result[0] shouldBe expectedId1
        result[1] shouldBe expectedId2
    }

    @Test
    fun testExecuteQueryPositionalParams() {
        val result = db.executeQueryPositionalParams(
            "SELECT COUNT(*) cnt FROM T WHERE field2 > ?",
            countResultSetMap,
            15
        )

        checkNotNull(result)
        result::class shouldBe Int::class
        result shouldBe 2
    }

    @Test
    fun testExecuteQueryWithParams() {
        val result = db.executeQuery(
            "SELECT COUNT(*) cnt FROM T WHERE field2 > :field2Min",
            mapOf("field2Min" to 15),
            countResultSetMap
        )

        checkNotNull(result)
        result::class shouldBe Int::class
        result shouldBe 2
    }

    @Test
    fun testFindAll() {
        val result = db.findAll(
            sql = "SELECT * FROM T ORDER BY field3",
            rowMapper = modelResultSetMap
        )

        result.size shouldBe 3
        result[0]::class shouldBe Model::class
        result[0].id shouldBe 1
        result[0].field3 shouldBe Instant.ofEpochMilli(0)
        result[0].annoyedParent shouldBe AnnoyedParent.ONE
        result[1]::class shouldBe Model::class
        result[1].id shouldBe 2
        result[1].field3 shouldBe Instant.ofEpochMilli(1000)
        result[1].annoyedParent shouldBe AnnoyedParent.TWO
        result[2]::class shouldBe Model::class
        result[2].id shouldBe 3
        result[2].field3 shouldBe Instant.ofEpochMilli(2000)
        result[2].annoyedParent shouldBe AnnoyedParent.TWO_AND_A_HALF

    }

    @Test
    fun testFindAllPositionalParams() {
        val result = db.findAllPositionalParams(
            "SELECT * FROM T WHERE field2 > ?",
            rowMapper = modelResultSetMap,
            15
        )

        result.size shouldBe 2
        result[0]::class shouldBe Model::class
    }

    @Test
    fun testFindAllWithParams() {
        val result = db.findAll(
            "SELECT * FROM T WHERE field2 > :field2Min",
            mapOf("field2Min" to 15),
            rowMapper = modelResultSetMap,
        )

        result.size shouldBe 2
        result[0]::class shouldBe Model::class

    }

    @Test
    fun testSaveAndDelete() {
        val originalCount = tableCount()

        val model = Model(100, "testName", 1000, Instant.ofEpochMilli(0), AnnoyedParent.TWO)
        db.executeUpdate(
            "INSERT INTO T (id, field1, field2, field3, annoyed_parent) VALUES (:id, :field1, :field2, :field3, CAST(:annoyedParent as annoyed_parent_type))",
            model.propertiesToMap()
        )

        val newCount = tableCount()

        newCount shouldBe originalCount + 1

        val result = checkNotNull(
            db.executeQuery(
                "SELECT * FROM T WHERE id = :id",
                mapOf("id" to 100),
                modelResultSetMap
            )
        )

        result shouldBe model
        result.field3 shouldBe Instant.ofEpochMilli(0)

        db.executeUpdate(
            "DELETE FROM T WHERE id = :id",
            model.propertiesToMap()
        )

        val finalCount = tableCount()

        finalCount shouldBe originalCount
    }

    fun tableCount(): Int {
        return checkNotNull(db.executeQuery(
            "SELECT COUNT(*) cnt FROM T"
        ) { resultSet -> resultSet.getInt("cnt") })
    }

    fun tableKeyGenCount(): Int {
        return checkNotNull(db.executeQuery(
            "SELECT COUNT(*) cnt FROM KEY_GEN_T"
        ) { resultSet -> resultSet.getInt("cnt") })
    }

    fun tableKeyGenIdByField1(field1Val: String): Int {
        return checkNotNull(db.executeQuery(
            "SELECT id FROM KEY_GEN_T WHERE field1 = :field1Val",
            mapOf("field1Val" to field1Val)
        ) { resultSet -> resultSet.getInt("id") })
    }

    fun clearKeyGenTable() {
        db.executeUpdate("DELETE FROM KEY_GEN_T")
    }

}