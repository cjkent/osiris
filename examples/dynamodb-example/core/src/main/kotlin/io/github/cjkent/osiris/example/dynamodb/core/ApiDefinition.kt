package io.github.cjkent.osiris.example.dynamodb.core

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import io.github.cjkent.osiris.core.ComponentsProvider
import io.github.cjkent.osiris.core.HttpHeaders
import io.github.cjkent.osiris.core.api
import java.util.UUID

private const val ITEMS_TABLE: String = "Items"
private const val ID: String = "id"
private const val VALUE: String = "value"

/** The API. */
val api = api(DynamoExampleComponents::class) {
    get("/values") {
        val items = dynamoClient.scan(ITEMS_TABLE, listOf(ID, VALUE)).items
        items.map { item(it) }
    }
    post("/values") { req ->
        val id = UUID.randomUUID().toString()
        val value = req.requireBody(String::class)
        dynamoClient.putItem(ITEMS_TABLE, mapOf(ID to AttributeValue(id), VALUE to AttributeValue(value)))
        req.responseBuilder().status(201).header(HttpHeaders.LOCATION, "/values/$id").build()
    }
    get("/values/{id}") { req ->
        val id = req.pathParams[ID]
        val item = dynamoClient.getItem(ITEMS_TABLE, mapOf(ID to AttributeValue(id))).item
        item(item)
    }
    delete("/values/{id}") { req ->
        val id = req.pathParams[ID]
        dynamoClient.deleteItem(ITEMS_TABLE, mapOf(ID to AttributeValue(id)))
        req.responseBuilder().status(204).build()
    }
}

private fun item(item: MutableMap<String, AttributeValue>) =
    mapOf(ID to item.getValue(ID).s, VALUE to item.getValue(VALUE).s)

/**
 * Creates the components used by the test API.
 */
fun createComponents(): DynamoExampleComponents = DynamoExampleComponentsImpl()

/**
 * Components used in the DynamoDB example; contains a DynamoDB client.
 */
interface DynamoExampleComponents : ComponentsProvider {
    val dynamoClient: AmazonDynamoDB
}

/**
 * Components used in the DynamoDB example; contains a default DynamoDB client.
 */
class DynamoExampleComponentsImpl : DynamoExampleComponents {
    override val dynamoClient: AmazonDynamoDB = AmazonDynamoDBClientBuilder.defaultClient()
}
