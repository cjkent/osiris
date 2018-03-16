package ws.osiris.awsdeploy

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.regions.AwsProfileRegionProvider
import com.amazonaws.regions.DefaultAwsRegionProviderChain

/**
 * Provides the credentials provider and regions for the current AWS profile.
 */
class AwsProfile private constructor(val credentialsProvider: AWSCredentialsProvider, val region: String) {

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
