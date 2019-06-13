package com.example.osiris.core

import ws.osiris.aws.ApplicationConfig
import ws.osiris.aws.Stage
import java.time.Duration

/**
 * Configuration that controls how the application is deployed to AWS.
 */
val config = ApplicationConfig(
    applicationName = "osiris-e2e-test",
    lambdaMemorySizeMb = 512,
    lambdaTimeout = Duration.ofSeconds(10),
    bucketSuffix = "delete-me",
    stages = listOf(
        Stage(
            name = "dev",
            description = "Development stage",
            deployOnUpdate = true,
            variables = mapOf(
                "VAR1" to "devValue1",
                "VAR2" to "devValue2"
            )
        ),
        Stage(
            name = "prod",
            description = "Production stage",
            deployOnUpdate = false,
            variables = mapOf(
                "VAR1" to "prodValue1",
                "VAR2" to "prodValue2"
            )
        )
    )
)
