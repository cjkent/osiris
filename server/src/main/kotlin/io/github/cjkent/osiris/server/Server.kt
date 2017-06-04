package io.github.cjkent.osiris.server

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.cjkent.osiris.api.Api
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.ApiComponentsFactory
import io.github.cjkent.osiris.api.ApiDefinition
import io.github.cjkent.osiris.api.ContentTypes
import java.util.Base64
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.jvm.jvmName

// TODO I don't like the fact that componentsClass can be the class itself or the factory
// think of a neater way
class ApiFactory<T : ApiComponents> internal constructor(
    val api: Api<T>,
    val apiDefinitionClass: KClass<ApiDefinition<T>>,
    val componentsClass: KClass<*>
) {

    @Suppress("UNCHECKED_CAST")
    fun createComponents(): T {
        val instance = componentsClass.createInstance()
        if (componentsClass.isSubclassOf(api.componentsClass)) {
            return instance as T
        } else {
            val factory = instance as ApiComponentsFactory<T>
            return factory.createComponents()
        }
    }

    companion object {

        // TODO not sure about the type param. at the call site the type of T is unknown
        // we want to link the types of the components without actually having to state the concrete type
        @Suppress("UNCHECKED_CAST")
        fun <T : ApiComponents> create(
            classLoader: ClassLoader,
            apiComponentsClassName: String,
            apiDefinitionClassName: String
        ): ApiFactory<T> {

            val apiDefinition = createApiDefinition(classLoader, apiDefinitionClassName)
            val componentsClass = createComponentsClass(classLoader, apiComponentsClassName)
            val componentsImplClass = createComponentsImplClass<T>(componentsClass, apiDefinition)
            return ApiFactory(
                apiDefinition.api as Api<T>,
                apiDefinition::class as KClass<ApiDefinition<T>>,
                componentsImplClass)
        }

        //--------------------------------------------------------------------------------------------------

        @Suppress("UNCHECKED_CAST")
        private fun <T : ApiComponents> createComponentsImplClass(
            componentsClass: KClass<*>,
            apiDefinition: ApiDefinition<*>
        ): KClass<*> {

            val componentsImplClass = if (componentsClass.isSubclassOf(ApiComponents::class)) {
                checkSupertypeSubtype(apiDefinition.api.componentsClass, componentsClass)
                componentsClass
            } else if (componentsClass.isSubclassOf(ApiComponentsFactory::class)) {
                val componentsFactory = try {
                    componentsClass.createInstance() as ApiComponentsFactory<*>
                } catch (e: Exception) {
                    throw RuntimeException("Failed to create ApiComponentsFactory of type " +
                        "${componentsClass.jvmName}. ${e.message}", e)
                }
                checkSupertypeSubtype(apiDefinition.api.componentsClass, componentsFactory.componentsClass)
                componentsFactory::class
            } else {
                throw IllegalArgumentException("${componentsClass.jvmName} must implement ApiComponents " +
                    "or ApiComponentsFactory")
            }
            return componentsImplClass
        }

        private fun createApiDefinition(
            classLoader: ClassLoader,
            apiDefinitionClassName: String
        ): ApiDefinition<*> {

            val apiDefinition = try {
                val apiDefinitionClass = classLoader.loadClass(apiDefinitionClassName).kotlin
                apiDefinitionClass.checkNoArgsConstructor()
                apiDefinitionClass.createInstance() as? ApiDefinition<*> ?:
                    throw IllegalArgumentException("Class $apiDefinitionClassName does not implement ApiDefinition")
            } catch (e: Exception) {
                throw RuntimeException("Failed to create ApiDefinition of type $apiDefinitionClassName. ${e.message}",
                    e)
            }
            return apiDefinition
        }

        /**
         * Creates the `KClass` used to create the `ApiComponents` implementation.
         *
         * This can be an implementation of `ApiComponents` or an implementation of `ApiComponentsFactory`.
         */
        private fun createComponentsClass(
            classLoader: ClassLoader,
            componentsClassName: String
        ): KClass<out Any> {

            val componentsClass = try {
                classLoader.loadClass(componentsClassName).kotlin
            } catch (e: Exception) {
                throw RuntimeException("Failed to load components class $componentsClassName. ${e.message}", e)
            }
            componentsClass.checkNoArgsConstructor()
            return componentsClass
        }

        private fun checkSupertypeSubtype(superType: KClass<*>, subType: KClass<*>) {
            if (!superType.isSuperclassOf(subType)) {
                throw IllegalArgumentException("Components class ${subType.jvmName} must implement ${superType.jvmName}")
            }
        }

        private fun KClass<*>.checkNoArgsConstructor() {
            constructors.find { it.parameters.isEmpty() } ?:
                throw IllegalArgumentException("$jvmName must have a no-args constructor")
        }
    }
}

data class EncodedBody(val body: String?, val isBase64Encoded: Boolean)

/**
 * Encodes the response received from the request handler code into a string that can be serialised into
 * the response body.
 *
 * Handling of body types by content type:
 *   * content type = JSON
 *     * null - no body
 *     * string - assumed to be JSON, used as-is, no base64
 *     * ByteArray - base64 encoded
 *     * object - converted to a JSON string using Jackson
 *   * content type != JSON
 *     * null - no body
 *     * string - used as-is, no base64 - Jackson should handle escaping when AWS does the conversion
 *     * ByteArray - base64 encoded
 *     * any other type throws an exception
 */
fun encodeResponseBody(body: Any?, contentType: String, objectMapper: ObjectMapper): EncodedBody =
    if (contentType == ContentTypes.APPLICATION_JSON) {
        when (body) {
            null, is String -> EncodedBody(body as String?, false)
            is ByteArray -> EncodedBody(String(Base64.getMimeEncoder().encode(body), Charsets.UTF_8), true)
            else -> EncodedBody(objectMapper.writeValueAsString(body), false)
        }
    } else {
        when (body) {
            null, is String -> EncodedBody(body as String?, false)
            is ByteArray -> EncodedBody(String(Base64.getMimeEncoder().encode(body), Charsets.UTF_8), true)
            else -> throw RuntimeException("Cannot convert value of type ${body.javaClass.name} to response body")
        }
    }
