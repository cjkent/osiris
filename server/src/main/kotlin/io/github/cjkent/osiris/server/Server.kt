package io.github.cjkent.osiris.server

import io.github.cjkent.osiris.api.Api
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.ApiDefinition
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
                throw RuntimeException("Error creating ApiDefinition of type $apiDefinitionClassName. ${e.message}", e)
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

/**
 * Creates the [ApiComponents] implementation used by the API code.
 *
 * There are two options when creating the API components:
 *   1) The `ApiComponents` implementation is created directly using a no-args constructor
 *   2) An `ApiComponentsFactory` is created using a no-args constructor and it creates the components
 *
 * Implementations of this interface must have a no-args constructor.
 *
 * TODO explain that the factory is created during deployment as well as at runtime so it shouldn't do any work
 * until createComponents is called
 */
interface ApiComponentsFactory<out T : ApiComponents> {
    val componentsClass: KClass<out T>
    fun createComponents(): T
}
