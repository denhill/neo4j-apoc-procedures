package apoc.nlp.azure

import apoc.nlp.NLPHelperFunctions
import apoc.result.NodeWithMapResult
import apoc.util.JsonUtil
import org.neo4j.graphdb.Node
import org.neo4j.logging.Log
import java.io.DataOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection


enum class AzureEndpoint(val method: String) {
    SENTIMENT("/text/analytics/v2.1/sentiment"),
    KEY_PHRASES("/text/analytics/v2.1/keyPhrases"),
    VISION("/vision/v2.1/analyze"),
    ENTITIES("/text/analytics/v2.1/entities")
}

class AzureClient(private val baseUrl: String, private val key: String, private val log: Log) {

    companion object {
        fun convertToBatch(nodes: List<Node>, nodeProperty: String): List<List<Map<String, Any>>> = NLPHelperFunctions
                .partition(nodes, 25)
                .map { nodeList -> nodeList
                        .map { node -> mapOf("id" to node.id, "text" to node.getProperty(nodeProperty)) } }

        fun responseToNodeWithMapResult(resp: Map<String, Any>, source: List<Node>): NodeWithMapResult {
            val nodeId = resp.getValue("id").toString().toLong()
            val node = source.find { it.id == nodeId }
            return NodeWithMapResult(node, resp, emptyMap())
        }
    }

    private fun postData(method: String, subscriptionKeyValue: String, data: List<List<Map<String, Any>>>, config: Map<String, Any> = emptyMap()): List<Map<String, Any>> {
        val fullUrl = baseUrl + method + config.map { "${it.key}=${it.value}" }
                .joinToString("&")
                .also { if (it.isNullOrBlank()) it else "?$it" }
        val url = URL(fullUrl)
        return postData(url, subscriptionKeyValue, data)
    }

    private fun postData(url: URL, subscriptionKeyValue: String, data: Any): List<Map<String, Any>> {
        val connection = url.openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Ocp-Apim-Subscription-Key", subscriptionKeyValue)
        connection.doOutput = true
        when (data) {
            is ByteArray -> {
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                DataOutputStream(connection.outputStream).use { it.write(data) }
            }
            else -> {
                connection.setRequestProperty("Content-Type", "text/json")
                DataOutputStream(connection.outputStream).use { it.write(JsonUtil.writeValueAsBytes(mapOf("documents" to convertInput(data)))) }
            }
        }
        return connection.inputStream
                .use { JsonUtil.OBJECT_MAPPER.readValue(it, Any::class.java) }
                .let { result ->
                    val documents = (result as Map<String, Any?>)["documents"] as List<Map<String, Any?>>
                    documents.map { it as Map<String, Any> }
                }
    }

    private fun convertInput(data: Any): List<Map<String, Any>> {
        return when (data) {
            is Map<*, *> -> listOf(data as Map<String, Any>)
            is Collection<*> -> data.filterNotNull().map { convertInput(it) }.flatten()
            is String -> convertInput(mapOf("id" to 1, "text" to data))
            else -> throw java.lang.RuntimeException("Class ${data::class.java.name} not supported")
        }
    }

    fun entities(data: List<List<Map<String, Any>>>, config: Map<String, Any?>): List<Map<String, Any>> = postData(AzureEndpoint.ENTITIES.method, key, data)

    fun sentiment(data: List<List<Map<String, Any>>>, config: Map<String, Any?>): List<Map<String, Any>> = postData(AzureEndpoint.SENTIMENT.method, key, data)//.map { item -> MapResult(item)}

    fun keyPhrases(data: List<List<Map<String, Any>>>, config: Map<String, Any?>): List<Map<String, Any>> = postData(AzureEndpoint.KEY_PHRASES.method, key, data)// .map { item -> MapResult(item)}
}