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

package fr.vsct.tock.nlp.front.shared.parser

import org.litote.kmongo.Data
import java.util.Locale

/**
 * A NLP parse result.
 */
@Data
data class ParseResult(
    /**
     * The intent selected.
     */
    val intent: String,
    /**
     * the namesapce of the selected intent.
     */
    val intentNamespace: String,
    /**
     * The language selected.
     */
    val language: Locale,
    /**
     * The entities found.
     */
    val entities: List<ParsedEntityValue>,
    /**
     * The intent evaluated probability.
     */
    val intentProbability: Double,
    /**
     * The average entity evaluation probability.
     */
    val entitiesProbability: Double,
    /**
     * The analysed query.
     */
    val retainedQuery: String,
    /**
     * Other intents with significant probabilities.
     */
    val otherIntentsProbabilities: Map<String, Double>
) {

    /**
     * Returns the first value for the specified entity role.
     */
    fun firstValue(role: String): ParsedEntityValue? = entities.firstOrNull { it.entity.role == role }
}