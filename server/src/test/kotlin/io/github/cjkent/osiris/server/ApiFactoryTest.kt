package io.github.cjkent.osiris.server

import io.github.cjkent.osiris.api.Api
import io.github.cjkent.osiris.api.ApiComponents
import io.github.cjkent.osiris.api.ApiComponentsFactory
import io.github.cjkent.osiris.api.ApiDefinition
import io.github.cjkent.osiris.api.api
import org.testng.annotations.Test
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Test
class ApiFactoryTest {

    interface Components1 : ApiComponents
    class ComponentsImpl1 : Components1
    class ApiDefinition1 : ApiDefinition<Components1> {
        override val api = api(Components1::class) {}
    }
    class ComponentsFactory1 : ApiComponentsFactory<Components1> {
        override val componentsClass: KClass<out Components1> = ComponentsImpl1::class
        override fun createComponents(): Components1 = ComponentsImpl1()
    }

    fun create() {
        val apiFactory = ApiFactory.create<Components1>(
            javaClass.classLoader,
            ComponentsImpl1::class.jvmName,
            ApiDefinition1::class.jvmName)

        assertEquals(ComponentsImpl1::class, apiFactory.componentsClass)
        assertEquals(api(Components1::class) {}, apiFactory.api)
        assertTrue(apiFactory.createComponents() is Components1)
    }

    fun createUsingComponentsFactory() {
        val apiFactory = ApiFactory.create<Components1>(
            javaClass.classLoader,
            ComponentsFactory1::class.jvmName,
            ApiDefinition1::class.jvmName)

        assertEquals(ComponentsFactory1::class, apiFactory.componentsClass)
        assertEquals(Components1::class, apiFactory.api.componentsClass)
        assertEquals(api(Components1::class) {}, apiFactory.api)
        assertTrue(apiFactory.createComponents() is Components1)
        assertTrue(apiFactory.createComponents() is ComponentsImpl1)
    }

    //--------------------------------------------------------------------------------------------------

    class Components2 : ApiComponents
    class ApiDefinition2(override val api: Api<Components2>) : ApiDefinition<Components2>

    @Test(
        expectedExceptions = arrayOf(RuntimeException::class),
        expectedExceptionsMessageRegExp = ".*Definition2 must have a no-args constructor")
    fun factoryMissingNoArgsConstructor() {
        ApiFactory.create<Components2>(
            javaClass.classLoader,
            Components2::class.jvmName,
            ApiDefinition2::class.jvmName)
    }

    //--------------------------------------------------------------------------------------------------

    class Components3(val foo: String) : ApiComponents
    class ApiDefinition3 : ApiDefinition<Components3> {
        override val api = api(Components3::class) {}
    }
    class ComponentsFactory3(val foo: String) : ApiComponentsFactory<Components3> {
        override val componentsClass: KClass<out Components3> = Components3::class
        override fun createComponents(): Components3 = Components3(foo)
    }

    @Test(
        expectedExceptions = arrayOf(RuntimeException::class),
        expectedExceptionsMessageRegExp = ".*Components3 must have a no-args constructor")
    fun componentsMissingNoArgsConstructor() {
        ApiFactory.create<Components3>(
            javaClass.classLoader,
            Components3::class.jvmName,
            ApiDefinition3::class.jvmName)
    }

    @Test(
        expectedExceptions = arrayOf(RuntimeException::class),
        expectedExceptionsMessageRegExp = ".*ComponentsFactory3 must have a no-args constructor")
    fun componentsFactoryMissingNoArgsConstructor() {
        ApiFactory.create<Components3>(
            javaClass.classLoader,
            ComponentsFactory3::class.jvmName,
            ApiDefinition3::class.jvmName)
    }

    //--------------------------------------------------------------------------------------------------

    @Test(
        expectedExceptions = arrayOf(RuntimeException::class),
        expectedExceptionsMessageRegExp = ".*Components2 must implement .*Components1.*")
    fun incompatibleTypes() {
        ApiFactory.create<Components3>(
            javaClass.classLoader,
            Components2::class.jvmName,
            ApiDefinition1::class.jvmName)
    }
}
