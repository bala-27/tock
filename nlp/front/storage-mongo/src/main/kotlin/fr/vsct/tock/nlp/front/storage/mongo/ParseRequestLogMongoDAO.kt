/*
 * Copyright (C) 2017 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.vsct.tock.nlp.front.storage.mongo

import com.mongodb.client.MongoCollection
import com.mongodb.client.model.IndexOptions
import fr.vsct.tock.nlp.front.service.storage.ParseRequestLogDAO
import fr.vsct.tock.nlp.front.shared.config.ApplicationDefinition
import fr.vsct.tock.nlp.front.shared.monitoring.ParseRequestLog
import fr.vsct.tock.nlp.front.shared.monitoring.ParseRequestLogQuery
import fr.vsct.tock.nlp.front.shared.monitoring.ParseRequestLogQueryResult
import fr.vsct.tock.nlp.front.shared.monitoring.ParseRequestLogStat
import fr.vsct.tock.nlp.front.shared.monitoring.ParseRequestLogStatQuery
import fr.vsct.tock.nlp.front.shared.parser.ParseQuery
import fr.vsct.tock.nlp.front.shared.parser.ParseResult
import fr.vsct.tock.nlp.front.storage.mongo.DayAndYear_.Companion.DayOfYear
import fr.vsct.tock.nlp.front.storage.mongo.DayAndYear_.Companion.Year
import fr.vsct.tock.nlp.front.storage.mongo.MongoFrontConfiguration.database
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogCol_.Companion.ApplicationId
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogCol_.Companion.Date
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogCol_.Companion.DurationInMS
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogCol_.Companion.Error
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogCol_.Companion.Query
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogCol_.Companion.Result
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogCol_.Companion.Text
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogStatResult_.Companion.Count
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogStatResult_.Companion.Duration
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogStatResult_.Companion.EntitiesProbability
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogStatResult_.Companion.IntentProbability
import fr.vsct.tock.nlp.front.storage.mongo.ParseRequestLogStatResult_.Companion._id
import fr.vsct.tock.shared.longProperty
import fr.vsct.tock.shared.security.StringObfuscatorService.obfuscate
import org.litote.kmongo.Data
import org.litote.kmongo.Id
import org.litote.kmongo.aggregate
import org.litote.kmongo.and
import org.litote.kmongo.ascending
import org.litote.kmongo.avg
import org.litote.kmongo.cond
import org.litote.kmongo.dayOfYear
import org.litote.kmongo.descendingSort
import org.litote.kmongo.document
import org.litote.kmongo.ensureIndex
import org.litote.kmongo.eq
import org.litote.kmongo.from
import org.litote.kmongo.getCollection
import org.litote.kmongo.group
import org.litote.kmongo.gte
import org.litote.kmongo.lte
import org.litote.kmongo.match
import org.litote.kmongo.project
import org.litote.kmongo.regex
import org.litote.kmongo.sort
import org.litote.kmongo.sum
import org.litote.kmongo.toList
import org.litote.kmongo.year
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 *
 */
object ParseRequestLogMongoDAO : ParseRequestLogDAO {

    @Data
    data class ParseRequestLogCol(
        val text: String,
        val applicationId: Id<ApplicationDefinition>,
        val query: ParseQuery,
        val result: ParseResult?,
        val durationInMS: Long = 0,
        val error: Boolean = false,
        val date: Instant = Instant.now()
    ) {

        constructor(request: ParseRequestLog) :
                this(
                    textKey(request.result?.retainedQuery ?: request.query.queries.firstOrNull() ?: ""),
                    request.applicationId,
                    request.query,
                    request.result,
                    request.durationInMS,
                    request.error,
                    request.date
                )

        fun toRequest(): ParseRequestLog =
            ParseRequestLog(
                applicationId,
                query,
                result,
                durationInMS,
                error,
                date
            )
    }

    @Data
    data class DayAndYear(val dayOfYear: Int, val year: Int)

    @Data
    data class ParseRequestLogStatResult(
        val _id: DayAndYear,
        val error: Int,
        val count: Int,
        val duration: Double,
        val intentProbability: Double,
        val entitiesProbability: Double
    ) {

        fun toStat(): ParseRequestLogStat = ParseRequestLogStat(
            LocalDate.ofYearDay(_id.year, _id.dayOfYear),
            error,
            count,
            duration,
            intentProbability,
            entitiesProbability
        )
    }

    private val col: MongoCollection<ParseRequestLogCol> by lazy {
        val c = database.getCollection<ParseRequestLogCol>("parse_request_log")
        c.ensureIndex(Query.context.language, ApplicationId)
        c.ensureIndex(Query.context.language, ApplicationId, Text)
        c.ensureIndex(
            Date,
            indexOptions = IndexOptions().expireAfter(longProperty("tock_nlp_log_index_ttl_days", 7), TimeUnit.DAYS)
        )
        c
    }

    override fun save(log: ParseRequestLog) {
        val savedLog = log.copy(
            query = log.query.copy(queries = obfuscate(log.query.queries)),
            result = log.result?.copy(retainedQuery = obfuscate(log.result?.retainedQuery) ?: "")
        )
        col.insertOne(ParseRequestLogCol(savedLog))
    }

    override fun search(query: ParseRequestLogQuery): ParseRequestLogQueryResult {
        with(query) {
            val baseFilter =
                and(
                    ApplicationId eq applicationId,
                    Query.context.language eq language,
                    if (search.isNullOrBlank()) null
                    else if (query.onlyExactMatch) Text eq search
                    else Text.regex(search!!.trim(), "i"),
                    if (searchMark == null) null else Date lte searchMark!!.date,
                    if (sinceDate == null) null else Date gte sinceDate,
                    if (clientDevice.isNullOrBlank()) null else Query.context.clientDevice eq clientDevice,
                    if (clientId.isNullOrBlank()) null else Query.context.clientId eq clientId
                )
            val count = col.count(baseFilter)
            if (count > start) {
                val list = col.find(baseFilter)
                    .descendingSort(Date)
                    .skip(start.toInt())
                    .limit(size)

                return ParseRequestLogQueryResult(count, list.map { it.toRequest() }.toList())
            } else {
                return ParseRequestLogQueryResult(0, emptyList())
            }
        }
    }

    override fun stats(query: ParseRequestLogStatQuery): List<ParseRequestLogStat> {
        return with(query) {
            col.aggregate<ParseRequestLogStatResult>(
                match(
                    and(
                        ApplicationId eq applicationId,
                        Query.context.language eq language,
                        if (intent == null) null else Result.intent eq intent
                    )
                ),
                project(
                    Error from cond(Error, 1, 0),
                    DayOfYear from dayOfYear(Date),
                    Year from year(Date),
                    Duration from DurationInMS,
                    IntentProbability from Result.intentProbability,
                    EntitiesProbability from Result.entitiesProbability
                ),
                group(
                    document(
                        DayOfYear from DayOfYear,
                        Year from Year
                    ),
                    Error sum Error,
                    Count sum 1,
                    Duration avg Duration,
                    IntentProbability avg IntentProbability,
                    EntitiesProbability avg EntitiesProbability
                ),
                sort(
                    ascending(
                        _id.year,
                        _id.dayOfYear
                    )
                )
            )
                .toList().map { it.toStat() }
        }
    }
}