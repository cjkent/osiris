# Osiris - Simple Serverless Web Apps

Osiris is a Kotlin library that makes it easy to write and deploy serverless REST APIs. You can develop an API with Osiris and deploy it to AWS Lambda and API Gateway with a single command and without being an AWS expert. APIs written with Osiris can also be run in a local HTTP server or an in-memory server for development and testing.

The simplest possible API you can build with Osiris looks something like this.

```kotlin
val api = api(ComponentsProvider::class) {
    get("/helloworld") { req ->
        "hello, world!"
    }
}
```

An API can be deployed to AWS or run locally with a Maven command. It can also be run in an IDE with one method call.

Note: Osiris should be considered beta quality at the moment. It is a proof-of-concept and should be used with caution. It is probably buggy and it might set your hair on fire. 

For more details please see the [wiki](https://github.com/cjkent/osiris/wiki/Getting-Started).
