package no.fintlabs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.utils.KubernetesSerialization
import io.javaoperatorsdk.operator.Operator
import io.javaoperatorsdk.operator.api.config.ConfigurationService
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.monitoring.micrometer.MicrometerMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.fintlabs.operator.applicationReconcilerModule
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatform.getKoin


fun main() {
    startKoin {
        modules(
            applicationReconcilerModule(),
            baseModule
        )
    }

    startHttpServer()
    startOperator()
}

val baseModule = module {
    single(createdAtStart = true) { defaultConfig() }
    single {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).apply {
            JvmMemoryMetrics().bindTo(this)
            JvmGcMetrics().bindTo(this)
            ProcessorMetrics().bindTo(this)
        }
    }
    single {
        ObjectMapper().apply {
            registerKotlinModule()
        }
    }
    single {
        KubernetesClientBuilder()
            .withKubernetesSerialization(KubernetesSerialization(get(), true))
            .build()
    }
    single {
        OperatorPostConfigHandler { operator ->
            getAll<Reconciler<*>>().forEach { operator.register(it) }
        }
    }
    single {
        OperatorConfigHandler { config ->
            config.withKubernetesClient(get())
            get<PrometheusMeterRegistry>().let { registry ->
                config.withMetrics (MicrometerMetrics.withoutPerResourceMetrics(registry))
            }
        }
    }
    single {
        val config = ConfigurationService.newOverriddenConfigurationService { config ->
            getAll<OperatorConfigHandler>().reversed().forEach { it.accept(config) }
        }

        Operator(config).apply {
            get<OperatorPostConfigHandler>().accept(this)
        }
    }
    single<HttpHandler> {
        val prometheusRegistry: PrometheusMeterRegistry = get()
        routes(
            "/metrics" bind Method.GET to { Response(Status.OK).body(prometheusRegistry.scrape()) },
        )
    }
}

fun startHttpServer() {
    val service: HttpHandler = getKoin().get()
    val server = service.asServer(Jetty(8080)).start()
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
    })
}

fun startOperator() {
    val operator = getKoin().get<Operator>();

    Runtime.getRuntime().addShutdownHook(Thread {
        operator.stop()
    })

    operator.start()
}