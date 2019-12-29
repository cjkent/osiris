package ws.osiris.awsdeploy

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.AwsProfileRegionProvider
import com.amazonaws.regions.DefaultAwsRegionProviderChain
import com.amazonaws.services.apigateway.AmazonApiGateway
import com.amazonaws.services.apigateway.AmazonApiGatewayClientBuilder
import com.amazonaws.services.cloudformation.AmazonCloudFormation
import com.amazonaws.services.cloudformation.AmazonCloudFormationClientBuilder
import com.amazonaws.services.lambda.AWSLambda
import com.amazonaws.services.lambda.AWSLambdaClientBuilder
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClientBuilder
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest

/**
 * Provides AWS clients and related values (region, account ID).
 */
class AwsProfile private constructor(private val credentialsProvider: AWSCredentialsProvider, val region: String) {

    private val clientConfiguration = ClientConfiguration()
        .withSocketTimeout(120_000)
        .withConnectionTimeout(20_000)
        .withMaxErrorRetry(20)

    val s3Client: AmazonS3 by lazy {
        AmazonS3ClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(region)
            .withClientConfiguration(clientConfiguration)
            .build()
    }

    val apiGatewayClient: AmazonApiGateway by lazy {
        AmazonApiGatewayClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(region)
            .withClientConfiguration(clientConfiguration)
            .build()
    }

    val cloudFormationClient: AmazonCloudFormation by lazy {
        AmazonCloudFormationClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(region)
            .withClientConfiguration(clientConfiguration)
            .build()
    }

    val lambdaClient: AWSLambda by lazy {
        AWSLambdaClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(region)
            .withClientConfiguration(clientConfiguration)
            .build()
    }

    val accountId: String by lazy {
        val stsClient = AWSSecurityTokenServiceClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(region)
            .build()
        stsClient.getCallerIdentity(GetCallerIdentityRequest()).account
    }

    companion object {

        /**
         * Returns a profile that gets the credentials and region using the default AWS mechanism.
         *
         * See [here](https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default)
         * for details.
         */
        fun default(): AwsProfile = AwsProfile(
            DefaultAWSCredentialsProviderChain(),
            DefaultAwsRegionProviderChain().region
        )

        /**
         * Returns a profile that takes the credentials and region from a named profile in the AWS credentials and
         * config files.
         */
        fun named(profileName: String): AwsProfile = AwsProfile(
            ProfileCredentialsProvider(profileName),
            AwsProfileRegionProvider(profileName).region
        )
    }
}
