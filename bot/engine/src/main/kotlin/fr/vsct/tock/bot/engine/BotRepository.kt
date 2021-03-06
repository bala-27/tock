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

package fr.vsct.tock.bot.engine

import fr.vsct.tock.bot.admin.bot.BotApplicationConfiguration
import fr.vsct.tock.bot.admin.bot.BotApplicationConfigurationDAO
import fr.vsct.tock.bot.connector.Connector
import fr.vsct.tock.bot.connector.ConnectorBase
import fr.vsct.tock.bot.connector.ConnectorCallback
import fr.vsct.tock.bot.connector.ConnectorConfiguration
import fr.vsct.tock.bot.connector.ConnectorProvider
import fr.vsct.tock.bot.connector.ConnectorType
import fr.vsct.tock.bot.definition.BotDefinition
import fr.vsct.tock.bot.definition.BotProvider
import fr.vsct.tock.bot.definition.StoryHandlerListener
import fr.vsct.tock.bot.engine.config.BotConfigurationSynchronizer
import fr.vsct.tock.bot.engine.event.Event
import fr.vsct.tock.bot.engine.monitoring.RequestTimer
import fr.vsct.tock.bot.engine.nlp.BuiltInKeywordListener
import fr.vsct.tock.bot.engine.nlp.NlpListener
import fr.vsct.tock.nlp.api.client.NlpClient
import fr.vsct.tock.shared.DEFAULT_APP_NAMESPACE
import fr.vsct.tock.shared.Executor
import fr.vsct.tock.shared.defaultLocale
import fr.vsct.tock.shared.error
import fr.vsct.tock.shared.injector
import fr.vsct.tock.shared.provide
import fr.vsct.tock.shared.tockAppDefaultNamespace
import fr.vsct.tock.shared.vertx.vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import mu.KotlinLogging

/**
 * Advanced bot configuration.
 *
 * [fr.vsct.tock.bot.registerAndInstallBot] method is the preferred way to start a bot in most use cases.
 */
object BotRepository {

    private val logger = KotlinLogging.logger {}

    private val botConfigurationDAO: BotApplicationConfigurationDAO get() = injector.provide()
    internal val botProviders: MutableSet<BotProvider> = mutableSetOf()
    internal val storyHandlerListeners: MutableList<StoryHandlerListener> = mutableListOf()
    internal val nlpListeners: MutableList<NlpListener> = mutableListOf(BuiltInKeywordListener)
    private val nlpClient: NlpClient get() = injector.provide()
    private val executor: Executor get() = injector.provide()

    internal val connectorProviders: MutableSet<ConnectorProvider> = mutableSetOf(
        object : ConnectorProvider {
            override val connectorType: ConnectorType = ConnectorType.none
            override fun connector(connectorConfiguration: ConnectorConfiguration): Connector =
                object : ConnectorBase(ConnectorType.none) {
                    override fun register(controller: ConnectorController) = Unit

                    override fun send(event: Event, callback: ConnectorCallback, delayInMs: Long) = Unit
                }
        }
    )


    /**
     * Request timer for connectors.
     */
    @Volatile
    var requestTimer: RequestTimer = object : RequestTimer {}

    /**
     * healthcheck handler to answer to GET /healthcheck.
     */
    @Volatile
    var healthcheckHandler: (RoutingContext) -> Unit = {
        executor.executeBlocking {
            it.response().setStatusCode(if (nlpClient.healthcheck()) 200 else 500).end()
        }
    }

    /**
     * Registers a new [ConnectorProvider].
     */
    fun registerConnectorProvider(connectorProvider: ConnectorProvider) {
        connectorProviders.add(connectorProvider)
    }

    /**
     * Registers a new [BotProvider].
     */
    fun registerBotProvider(bot: BotProvider) {
        botProviders.add(bot)
    }

    /**
     * Registers a new [StoryHandlerListener].
     */
    fun registerStoryHandlerListener(listener: StoryHandlerListener) {
        storyHandlerListeners.add(listener)
    }

    /**
     * Registers an new [NlpListener].
     */
    fun registerNlpListener(listener: NlpListener) {
        nlpListeners.add(listener)
    }

    /**
     * Installs the bot(s).
     *
     * @param routerHandlers the additional router handlers
     * @param adminRestConnectorInstaller the (optional) linked [fr.vsct.tock.bot.connector.rest.RestConnector] installer.
     */
    fun installBots(
        routerHandlers: List<(Router) -> Unit>,
        adminRestConnectorInstaller: (BotApplicationConfiguration) -> ConnectorConfiguration? = { null }
    ) {
        val verticle = BotVerticle()

        val connectorConfigurations = ConnectorConfigurationRepository.getConfigurations()
            .run {
                if (isEmpty()) {
                    listOf(
                        ConnectorConfiguration(
                            "",
                            "",
                            ConnectorType.none,
                            ConnectorType.none
                        )
                    )
                } else {
                    this
                }
            }

        //check connector id integrity
        connectorConfigurations
            .groupBy { it.connectorId }
            .values
            .firstOrNull { it.size != 1 }
            ?.apply {
                error("A least two configurations have the same connectorId: ${this}")
            }

        val bots = botProviders.map { it.botDefinition() }

        //install each bot
        bots.forEach {
            installBot(verticle, it, connectorConfigurations, adminRestConnectorInstaller)
        }

        //check that nlp applications exist
        bots.distinctBy { it.namespace to it.nlpModelName }
            .forEach { botDefinition ->
                try {
                    nlpClient.createApplication(
                        botDefinition.namespace,
                        botDefinition.nlpModelName,
                        defaultLocale
                    )?.apply {
                        logger.info { "nlp application initialized $namespace $name with locale $supportedLocales" }
                    }
                } catch (e: Exception) {
                    logger.error(e)
                }
            }

        //register services
        routerHandlers.forEachIndexed { index, handler ->
            verticle.registerServices("_handler_$index", handler)
        }

        //deploy verticle
        vertx.deployVerticle(verticle)
    }

    private fun installBot(
        verticle: BotVerticle,
        botDefinition: BotDefinition,
        connectorConfigurations: List<ConnectorConfiguration>,
        adminRestConnectorInstaller: (BotApplicationConfiguration) -> ConnectorConfiguration?
    ) {

        fun findConnectorProvider(connectorType: ConnectorType): ConnectorProvider {
            return connectorProviders.first { it.connectorType == connectorType }
        }

        val bot = Bot(botDefinition)
        val existingBotConfigurations =
            botConfigurationDAO
                .getConfigurationsByBotId(botDefinition.botId)
                .groupBy { it.applicationId }
                .map { (key, value) ->
                    if (value.size > 1) {
                        logger.warn { "more than one configuration in database: $value" }
                    }
                    key to value.first()
                }
                .toMap()

        connectorConfigurations.forEach { baseConf ->
            findConnectorProvider(baseConf.type)
                .apply {
                    //1 refresh connector conf
                    val conf = refreshBotConfiguration(baseConf, existingBotConfigurations)
                    //2 create and install connector
                    val connector = connector(conf)
                    //3 set default namespace to bot namespace if not already set
                    if (tockAppDefaultNamespace == DEFAULT_APP_NAMESPACE) {
                        tockAppDefaultNamespace = bot.botDefinition.namespace
                    }
                    //4 update bot conf
                    val appConf =
                        saveConfiguration(verticle, connector, conf, bot)

                    //5 monitor conf
                    BotConfigurationSynchronizer.monitor(bot)

                    //6 generate and install rest connector
                    adminRestConnectorInstaller.invoke(appConf)
                        ?.also {
                            val restConf = refreshBotConfiguration(it, existingBotConfigurations)
                            saveConfiguration(
                                verticle,
                                findConnectorProvider(restConf.type).connector(restConf),
                                restConf,
                                bot
                            )
                        }
                }
        }
    }

    private fun refreshBotConfiguration(
        configuration: ConnectorConfiguration,
        existingBotConfigurations: Map<String, BotApplicationConfiguration>
    ): ConnectorConfiguration =
        existingBotConfigurations[configuration.connectorId]?.run {
            ConnectorConfiguration(configuration, this)
        } ?: configuration

    private fun saveConfiguration(
        verticle: BotVerticle,
        connector: Connector,
        configuration: ConnectorConfiguration,
        bot: Bot
    ): BotApplicationConfiguration {

        return with(bot.botDefinition) {
            val conf = BotApplicationConfiguration(
                configuration.connectorId.run { if (isBlank()) botId else this },
                botId,
                namespace,
                nlpModelName,
                configuration.type,
                configuration.ownerConnectorType,
                configuration.getName().run { if (isBlank()) botId else this },
                configuration.getBaseUrl(),
                configuration.parametersWithoutDefaultKeys(),
                path = configuration.path
            )

            TockConnectorController.register(connector, bot, verticle)

            botConfigurationDAO.updateIfNotManuallyModified(conf)
        }
    }
}