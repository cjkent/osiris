# Osiris - Simple Serverless Web Apps

Osiris is a Kotlin library that makes it easy to write and deploy serverless REST APIs. You can develop an API with Osiris and deploy it to AWS Lambda and API Gateway without being an AWS expert. APIs written with Osiris can also be run in a local server for development and testing.

The simplest possible API you can build with Osiris looks something like this.

```kotlin
class MyApiDefinition : ApiDefinition<ApiComponents> {

    override val api = api(ApiComponents::class) {
        get("/helloworld") { req ->
            "hello, world!"
        }
    }
}
```

An API can be deployed to AWS or run locally with a single Maven command. It can also be run in an IDE with a single method call.

Note: Osiris should be considered alpha quality at the moment. It is a proof-of-concept and should be used with caution. It is probably buggy and insecure and it might set your hair on fire. 

## Getting Started

### Requirements

1) An AWS account
1) An AWS user with administrator permissions. See [here](http://docs.aws.amazon.com/IAM/latest/UserGuide/getting-started_create-admin-group.html)
1) AWS user credentials. See [here](http://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default)
1) Software
    1) git
    1) JDK8
    1) Maven

### Getting Osiris

Osiris is at a very early stage and not yet available in Maven Central. Therefore you need to clone it from GitHub and build it yourself. If you are not confident doing this then you should probably hold off using it for now :)

Open a terminal and clone the project:

```
 git clone https://github.com/cjkent/osiris.git
```

Change into the project directory:

```
cd osiris
```

Build and install using Maven:

```
mvn install
```

You should see a lot of output ending with a message something like this:

```shell
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: 46.090 s
[INFO] Finished at: 2017-06-04T19:04:31+01:00
[INFO] Final Memory: 42M/825M
[INFO] ------------------------------------------------------------------------
```

### Creating a Project

Osiris includes a Maven archetype - a template used to build projects that include an example API definition and the Maven configuration needed to build and deploy an API.

Execute the following command in a terminal:

```
mvn archetype:generate -DarchetypeGroupId=io.github.cjkent.osiris -DarchetypeArtifactId=osiris-archetype
```

You will be prompted to enter the `groupId` and `artifactId` for the new Maven project. Press enter when prompted to enter the package name, and then enter `y` and Maven will generate the project.

### Building and Deploying the Project

Change into the project directory (which has the same name as the `groupId` you entered above) and execute:

```
mvn deploy
```

This builds the project and deploys it to AWS. You should see output ending with something like this:

```
[INFO] Creating Lambda function 'example-api'
[INFO] Creating REST API 'example-api'
[INFO] Deploying REST API 'example-api' to stage 'dev'
[INFO] API 'example-api' deployed to https://sqonja0nh2.execute-api.eu-west-1.amazonaws.com/dev/
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: 43.445 s
[INFO] Finished at: 2017-06-04T19:53:18+01:00
[INFO] Final Memory: 95M/743M
[INFO] ------------------------------------------------------------------------
```

The API is now live at the URL shown in the output, `https://sqonja0nh2.execute-api.eu-west-1.amazonaws.com/dev/` in the example above. Paste this into a browser, append `helloworld` to the URL and press enter. You should see some JSON containing "hello, world!" in the browser.

### Running a Local Server

The API can also be run in a local server using Maven or in an IDE for debugging. The local server is only intended for development and testing as it has no support for authentication and authorisation.

#### Run a Local Server with Maven

Build the project and run the local server:

```
mvn compile exec:exec
```

You should see a message similar to:

```
22:13:07.338 INFO  [main] i.g.cjkent.osiris.localserver - Server started at http://localhost:8080/
```

Navigate to `http://localhost:8080/helloworld` in a browser and should should see a JSON message containing "hello, world!".

#### Run a Local Server in an IDE

Create a `main` method and call the function `runLocalServer` from the package `io.github.cjkent.osiris.localserver`

```kotlin
fun main(args: Array<String>) {
    runLocalServer(MyComponentsImpl::class, MyApiDefinition::class)
}
```
