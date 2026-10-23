//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("jacoco")
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain(17)
}

detekt {
    toolVersion = libs.versions.detekt.get()
    config = files("${rootProject.projectDir}/config/detekt/detekt.yml")
}

spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
        licenseHeaderFile("${rootProject.projectDir}/config/spotless/copyright.kt")
    }
}

// WHY THIS EXISTS:
//
// The desktop opens the SAME encrypted database Android's Room writes. Room can replay a migration
// chain; the desktop cannot, so for a version it has no migration for it needs the DDL itself - the
// CREATE TABLE / CREATE INDEX statements and Room's identity hash. That DDL is the Room-generated
// schema export, so THAT is the single source of truth, not a hand-written second copy that would
// drift the first time a column changed.
//
// The exported JSON is turned into typed Kotlin at BUILD time for the same reason `core/strings`
// generates `UiStrings`: the copy must be compiled and cross-checked, not re-derived at runtime.
//
// Room writes entity `createSql` with the literal token `${TABLE_NAME}` where the table name goes
// (it cannot know the name at the point the string is built). Substituting that exact token is the
// whole job of the substitution below; any other token means the export format changed and the build
// must stop rather than emit DDL that creates a literal table called `${TABLE_NAME}`.
//
// Every failure mode here is a WRONG SCHEMA on the user's data file, so every check below is a hard
// `require` (throwing), never a warning. A silently-skipped key or a silently-preferred stale orphan
// export would both produce a DDL set that does not match the database being opened.
//
// JSON is parsed by the hand-rolled reader below rather than a library: this module's buildscript has
// no JSON library on its classpath, and adding one (kotlinx-serialization is only a *project*
// dependency of `core/shared`, and this task runs at CONFIGURATION time, before the module's own
// dependency classpath is assembled). Groovy's `groovy.json.JsonSlurper` ships inside the Gradle
// runtime but is not part of the Kotlin DSL's buildscript API contract, so the parser is written
// here instead: ~40 lines, and the only JSON it ever sees is a machine-generated Room export.
val databaseSchemasDir = layout.projectDirectory.dir("../database/schemas")

val generateDatabaseSchemaDdl by tasks.registering {
    description = "Generates PayanamDatabaseSchemaDdl from the Room schema export of the current version"
    group = "build"
    // A local of THIS closure, not a script-level `val`: a `doLast` that reaches out to the script
    // instance (to read a top-level property) captures the Gradle script object, which the
    // configuration cache (enabled in gradle.properties) refuses to serialize.
    val declaredVersion = 22 // MIRROR of PayanamDatabaseSchema.VERSION in :core:shared - bump together
    val sourceSchemas = databaseSchemasDir
    val destination = layout.buildDirectory.dir("generated/databaseSchemaDdl/main/kotlin")
    // Resolved at configuration time: touching `layout` or `logger` inside doLast would capture the
    // script object, which the configuration cache (enabled in gradle.properties) refuses to serialize.
    val buildDirFile = layout.buildDirectory.get().asFile
    inputs.dir(sourceSchemas).withPropertyName("roomSchemaExports")
    outputs.dir(destination).withPropertyName("generatedDatabaseSchemaDdl")
    doLast {
        /**
         * Minimal JSON reader: values are [String], [Double], [Boolean], null, or nested
         * [Map]/[List]. Sufficient for a Room export, which contains no escape sequences at all
         * (verified: zero backslashes in 21.json) and no number whose exact type matters.
         */
        fun parseJson(text: String): Any? {
            var i = 0

            fun fail(what: String): Nothing = throw IllegalStateException(
                "Schema JSON is not parseable at offset $i: $what",
            )

            fun skipWs() {
                while (i < text.length && text[i].isWhitespace()) i++
            }

            fun readString(): String {
                i++ // opening quote
                val sb = StringBuilder()
                while (true) {
                    if (i >= text.length) fail("unterminated string")
                    when (val c = text[i]) {
                        '"' -> { i++; return sb.toString() }
                        '\\' -> {
                            if (i + 1 >= text.length) fail("dangling escape")
                            when (val e = text[i + 1]) {
                                '"', '\\', '/' -> { sb.append(e); i += 2 }
                                'n' -> { sb.append('\n'); i += 2 }
                                't' -> { sb.append('\t'); i += 2 }
                                'r' -> { sb.append('\r'); i += 2 }
                                'b' -> { sb.append('\b'); i += 2 }
                                'f' -> { sb.append('\u000C'); i += 2 }
                                'u' -> {
                                    if (i + 5 >= text.length) fail("truncated \\u escape")
                                    val hex = text.substring(i + 2, i + 6)
                                    val code = hex.toIntOrNull(16) ?: fail("bad \\u escape '$hex'")
                                    sb.append(code.toChar())
                                    i += 6
                                }
                                else -> fail("unsupported escape '\\$e'")
                            }
                        }
                        else -> { sb.append(c); i++ }
                    }
                }
            }

            fun readValue(): Any? {
                skipWs()
                if (i >= text.length) fail("unexpected end of input")
                return when (val c = text[i]) {
                    '{' -> {
                        i++
                        val obj = LinkedHashMap<String, Any?>()
                        skipWs()
                        if (i < text.length && text[i] == '}') { i++; return obj }
                        while (true) {
                            skipWs()
                            val key = readString()
                            skipWs()
                            if (i >= text.length || text[i] != ':') fail("expected ':' after key '$key'")
                            i++
                            obj[key] = readValue()
                            skipWs()
                            if (i >= text.length) fail("unterminated object")
                            when (text[i]) {
                                ',' -> i++
                                '}' -> { i++; return obj }
                                else -> fail("expected ',' or '}' but found '${text[i]}'")
                            }
                        }
                    }
                    '[' -> {
                        i++
                        val arr = ArrayList<Any?>()
                        skipWs()
                        if (i < text.length && text[i] == ']') { i++; return arr }
                        while (true) {
                            arr.add(readValue())
                            skipWs()
                            if (i >= text.length) fail("unterminated array")
                            when (text[i]) {
                                ',' -> i++
                                ']' -> { i++; return arr }
                                else -> fail("expected ',' or ']' but found '${text[i]}'")
                            }
                        }
                    }
                    '"' -> readString()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> {
                        val start = i
                        while (i < text.length && text[i] !in ",]} \t\n\r") i++
                        val raw = text.substring(start, i)
                        raw.toDoubleOrNull() ?: fail("unrecognised value '$raw'")
                    }
                }
            }

            val value = readValue()
            skipWs()
            require(i == text.length) { "Schema JSON has trailing content at offset $i" }
            return value
        }

        fun Any?.obj(what: String): Map<String, Any?> =
            this as? Map<String, Any?> ?: throw IllegalStateException("Expected a JSON object for $what")

        fun Any?.arr(what: String): List<Any?> =
            this as? List<Any?> ?: throw IllegalStateException("Expected a JSON array for $what")

        fun Any?.str(what: String): String =
            this as? String ?: throw IllegalStateException("Expected a JSON string for $what")

        val schemasRoot = sourceSchemas.asFile
        val expectedDirName = "io.payanam.database.PayanamDatabase"
        val expectedFileName = "$declaredVersion.json"

        // CHECK 1 - no OTHER export anywhere under the schemas root may claim this version.
        //
        // A stale orphan directory (`io.payanam.core.database.PayanamDatabase`, the name the module
        // had before a package rename) still sits beside the live one and still holds a `16.json`.
        // Picking "the first 21.json found" would silently prefer whichever the filesystem happened to
        // list, so an orphan gaining a same-versioned file would swap the schema under the build. The
        // walk is recursive on purpose: a nested directory (`a/b/PayanamDatabase/21.json`) is just as
        // able to shadow the live export as an immediate one.
        val expectedFile = schemasRoot.resolve(expectedDirName).resolve(expectedFileName)
        val sameVersionedElsewhere = schemasRoot.walkTopDown()
            .filter { it.isFile && it.name == expectedFileName && it != expectedFile }
            .map { it.relativeTo(schemasRoot).path }
            .sorted()
            .toList()
        require(sameVersionedElsewhere.isEmpty()) {
            "More than one schema export under $schemasRoot holds $expectedFileName: the expected " +
                "'$expectedDirName/$expectedFileName' plus $sameVersionedElsewhere. A stale orphan " +
                "export must be deleted or renamed, not silently preferred."
        }
        require(expectedFile.isFile) {
            "Missing schema export: $expectedFile. Room writes it from core/database via the " +
                "room.schemaLocation argument."
        }

        val root = parseJson(expectedFile.readText()).obj("root")

        // CHECK 2a - the root and the `database` object are closed sets.
        //
        // Room adds keys (e.g. `views`) as features land. Silently ignoring a new one would emit DDL
        // missing whatever that key describes - a database that opens and then fails on first use.
        require(root.keys == setOf("formatVersion", "database")) {
            "Unexpected keys in $expectedFileName root: ${root.keys}; expected exactly " +
                "${setOf("formatVersion", "database")}. Room added or removed a key - update this task."
        }
        require(root["formatVersion"] == 1.0) {
            "Unsupported Room export formatVersion ${root["formatVersion"]} in $expectedFileName " +
                "(this reader understands 1)."
        }
        val database = root["database"].obj("database")
        val databaseKeys = setOf("version", "identityHash", "entities", "setupQueries")
        require(database.keys == databaseKeys) {
            "Unexpected keys in $expectedFileName database object: ${database.keys}; expected exactly " +
                "$databaseKeys. A new key (e.g. views) must be handled here, not dropped."
        }

        val jsonVersion = (database["version"] as Number).toInt()
        require(jsonVersion == declaredVersion) {
            "$expectedFileName declares schema version $jsonVersion but this build expects " +
                "$declaredVersion (mirror of PayanamDatabaseSchema.VERSION in core/shared). Export the " +
                "new version, or revert the version bump - do not generate DDL that disagrees with it."
        }

        // CHECK 2b - the per-entity and per-index key sets are closed, and the check is a FORMAT
        // check, not a record of what this task happens to read.
        //
        // `readKeys` are the keys whose value becomes Kotlin. `notReadKeys` are keys Room writes
        // that describe schema this DDL already encodes: `fields`/`primaryKey`/`foreignKeys` are
        // re-parsable out of the `CREATE TABLE` string itself, and an index's `unique`/
        // `columnNames`/`orders` are all in its `CREATE INDEX` string. They are listed rather than
        // ignored so that a key Room has NEVER written before still fails the build - the guarantee
        // this gives is "no key appears that nobody classified", not "the generator reads
        // everything". The first version of this file listed neither and failed on `fields`.
        // An entity may legitimately omit `indices` and `foreignKeys`, so only `tableName` and
        // `createSql` are mandatory.
        val entityReadKeys = setOf("tableName", "createSql", "indices")
        val entityNotReadKeys = setOf("fields", "primaryKey", "foreignKeys")
        val entityKeys = entityReadKeys + entityNotReadKeys
        val entityRequired = setOf("tableName", "createSql")
        val indexReadKeys = setOf("name", "createSql")
        val indexNotReadKeys = setOf("unique", "columnNames", "orders")
        val indexKeys = indexReadKeys + indexNotReadKeys

        // The Room export carries no double quotes, no backslashes and no newlines (verified: 0 of
        // each in 21.json), and its only `$` characters sit inside the `${TABLE_NAME}` token this
        // task substitutes away. The escaping below is the same chain `core/strings` uses for its
        // generated Kotlin, applied unconditionally so a future export that DOES contain a quote or a
        // newline still compiles: backslash first (so the escapes added after it are not re-escaped),
        // then quote, then `$` (emitted as `\$`, a literal dollar in the generated Kotlin), then the
        // line terminators. A raw string is NOT usable here - it would be broken by any `$` left.
        fun kotlinLiteral(raw: String): String = "\"" +
            raw.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("$", "\\\$")
                .replace("\r", "\\r")
                .replace("\n", "\\n") +
            "\""

        val sb = StringBuilder()
        val tables = database["entities"].arr("entities").map { it.obj("entity") }
        val indexStatements = mutableListOf<Pair<String, String>>()
        val emittedIndices = mutableSetOf<String>()

        tables.forEach { entity ->
            val present = entity.keys
            val unknown = present - entityKeys
            require(unknown.isEmpty()) {
                "Unhandled key(s) $unknown on entity ${entity["tableName"]} in $expectedFileName. Add " +
                    "each to entityReadKeys (if its value becomes Kotlin) or entityNotReadKeys (if the " +
                    "DDL already encodes it) before adding it - a dropped key emits wrong DDL."
            }
            val missing = entityRequired - present
            require(missing.isEmpty()) { "Entity is missing mandatory key(s) $missing in $expectedFileName" }
            val tableName = entity["tableName"].str("entity.tableName")
            val createSql = entity["createSql"].str("entity.createSql")

            // CHECK 2c - the placeholder must be present and must be the only one. A missing
            // placeholder means the table name was inlined instead, and this substitution is then a
            // no-op that would silently drift from Room.
            require(createSql.contains("\${TABLE_NAME}")) {
                "createSql for table '$tableName' has no \${TABLE_NAME} placeholder. Room stopped " +
                    "templating the table name, so the substitution this task performs is now wrong."
            }
            val sql = createSql.replace("\${TABLE_NAME}", tableName)
            require(!sql.contains("\${")) {
                "createSql for table '$tableName' still contains a \${...} placeholder after " +
                    "substitution: $sql"
            }

            (entity["indices"] ?: emptyList<Any?>()).arr("entity.indices").forEach { raw ->
                val index = raw.obj("index")
                val indexUnknown = index.keys - indexKeys
                require(indexUnknown.isEmpty()) {
                    "Unhandled key(s) $indexUnknown on index ${index["name"]} in $expectedFileName. " +
                        "Classify each in indexReadKeys or indexNotReadKeys before adding it."
                }
                val name = index["name"].str("index.name")
                val createIdx = index["createSql"].str("index.createSql")
                require(emittedIndices.add(name)) { "Duplicate index name '$name' in $expectedFileName" }
                require(createIdx.contains("\${TABLE_NAME}")) {
                    "createSql for index '$name' has no \${TABLE_NAME} placeholder."
                }
                val resolved = createIdx.replace("\${TABLE_NAME}", tableName)
                require(!resolved.contains("\${")) {
                    "createSql for index '$name' still contains a \${...} placeholder: $resolved"
                }
                indexStatements.add(name to resolved)
            }

            // KEY FIRST, THEN VALUE: `linkedMapOf` takes key to value pairs, so the table name is the
            // quoted key and the statement is the quoted value.
            sb.append("        ").append(kotlinLiteral(tableName)).append(" to ")
                .append(kotlinLiteral(sql)).append(",\n")
        }

        val setupQueries = database["setupQueries"].arr("setupQueries").map { it.str("setupQuery") }
        val identityHash = database["identityHash"].str("identityHash")
        require(setupQueries.isNotEmpty()) {
            "$expectedFileName has no setupQueries; without them Room's master table is never written."
        }
        require(setupQueries.any { it.contains(identityHash) }) {
            "No setupQuery mentions the identity hash '$identityHash' in $expectedFileName, so the " +
                "generated master-table insert and IDENTITY_HASH would disagree."
        }

        val generated = StringBuilder()
        generated.append("//  SPDX-FileCopyrightText: 2026 Aravinth-Earth\n")
        generated.append("//  SPDX-License-Identifier: AGPL-3.0-or-later\n")
        generated.append("//  GENERATED FILE - do not edit. Source: $expectedDirName/$expectedFileName\n")
        generated.append("//  Regenerate with: ./gradlew :core:database-jvm:generateDatabaseSchemaDdl\n")
        generated.append("package io.payanam.database.jvm.schema\n\n")
        generated.append("/**\n")
        generated.append(" * The DDL of the Payanam database at schema version $declaredVersion, emitted\n")
        generated.append(" * from the Room schema export so the Android database stays the one source of\n")
        generated.append(" * truth.\n")
        generated.append(" *\n")
        generated.append(" * The desktop cannot run Room migrations, so for a version it has no migration\n")
        generated.append(" * for it rebuilds this schema from [TABLES], [INDICES] and [SETUP_QUERIES], and\n")
        generated.append(" * compares [IDENTITY_HASH] against the file's own master table first.\n")
        generated.append(" */\n")
        generated.append("object PayanamDatabaseSchemaDdl {\n")
        generated.append("    /** Schema version these statements were exported from. */\n")
        generated.append("    const val SCHEMA_VERSION: Int = $declaredVersion\n\n")
        generated.append("    /** `CREATE TABLE` statement per table, keyed by table name. */\n")
        generated.append("    val TABLES: Map<String, String> = linkedMapOf(\n")
        generated.append(sb)
        generated.append("    )\n\n")
        generated.append("    /** `CREATE INDEX` statement per index name, keyed by index name. */\n")
        generated.append("    val INDICES: Map<String, String> = linkedMapOf(\n")
        indexStatements.forEach { (name, sql) ->
            generated.append("        ").append(kotlinLiteral(name)).append(" to ")
                .append(kotlinLiteral(sql)).append(",\n")
        }
        generated.append("    )\n\n")
        generated.append("    /** Room's post-create bookkeeping, including the `room_master_table` insert. */\n")
        generated.append("    val SETUP_QUERIES: List<String> = listOf(\n")
        setupQueries.forEach { generated.append("        ").append(kotlinLiteral(it)).append(",\n") }
        generated.append("    )\n\n")
        generated.append("    /** Room's identity hash for this version, as stored in `room_master_table`. */\n")
        generated.append("    const val IDENTITY_HASH: String = ").append(kotlinLiteral(identityHash)).append("\n")
        generated.append("}\n")

        val out = destination.get().asFile.resolve("io/payanam/database/jvm/schema/PayanamDatabaseSchemaDdl.kt")
        out.parentFile.mkdirs()
        out.writeText(generated.toString())
        System.out.println(
            "generateDatabaseSchemaDdl: wrote ${out.relativeTo(buildDirFile)} " +
                "(${tables.size} tables x ${indexStatements.size} indices x ${setupQueries.size} " +
                "setup queries @ v$declaredVersion)",
        )
    }
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateDatabaseSchemaDdl)
    }
}

// The JVM half of the data layer: opens the SAME encrypted database Android writes, over JDBC +
// SQLCipher, through the shared :core:domain repository interfaces.
//
// Driver: the API artifact is `implementation` here; the per-OS NATIVE payload is the CONSUMER's
// responsibility (desktop/build.gradle.kts selects linux-x64 vs windows-x64 by host OS). A library
// module cannot conditionally declare OS-specific natives, so declaring one here would ship an
// unusable binary for the other platform. Omitting the native fails at runtime with
// UnsatisfiedLinkError, never at compile time — the opener logs that case explicitly.
//
// Version is pinned exactly (libs.versions.toml `sqlcipher-jvm`): a floating version could change the
// KDF defaults and orphan every existing exported database.
dependencies {
    api(project(":core:domain"))
    api(project(":core:database-model"))
    implementation(project(":core:shared"))
    implementation(libs.coroutines.core)
    implementation(libs.sqlcipher.jvm.jdbc)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // The test JVM needs a native payload to open anything at all.
    testRuntimeOnly(
        if (providers.systemProperty("os.name").get().lowercase().contains("linux")) {
            libs.sqlcipher.jvm.linux.x64
        } else {
            libs.sqlcipher.jvm.windows.x64
        },
    )
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.withType<Test>().configureEach {
    useJUnit()
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val coverageExclusions = listOf("**/*Test*.*")

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/classes/kotlin/main") {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include("jacoco/test.exec", "jacoco/test*.exec"),
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn("test")
    val kotlinClasses = fileTree("${layout.buildDirectory.get()}/classes/kotlin/main") {
        exclude(coverageExclusions)
    }
    classDirectories.setFrom(kotlinClasses)
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(
        fileTree(layout.buildDirectory.get()).include("jacoco/test.exec", "jacoco/test*.exec"),
    )
    violationRules {
        rule {
            limit {
                // Floor is the MEASURED value: 0.55 instructions / 0.46 branches. It is low, and the
                // reason is specific rather than laziness — the uncovered code is the ACCEPT path, which
                // no generated fixture can reach. SQLCipher JDBC 0.2.13 cannot write `PRAGMA
                // user_version` by any route measured (direct execute/executeUpdate/executeQuery, a
                // trailing semicolon, or against an ATTACHed database), so a generated fixture is always
                // version 0 and dies at the version guard before reaching the session, the read-only
                // enforcer, or the happy path.
                //
                // Those paths ARE asserted — in the env-driven tests
                // (`interchangeWithARealAndroidExport`, `readOnlyIsGenuinelyEnforcedOnARealExport`),
                // which run wherever a real export and its passphrase are supplied. Because a skipped
                // test contributes no coverage, this floor stays low precisely while that check is
                // skipped. It must RATCHET UP as soon as the env-driven run becomes routine; it must
                // never be lowered to make a build pass.
                minimum = "0.55".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}
