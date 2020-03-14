# Main Project
`gpg2` needs to be on the path. 

From the root directory:

    export GPG_TTY=$(tty)
    mvn clean install
    mvn deploy -Prelease

# Gradle Plugin
From the `gradle-plugin` directory

    ./gradlew clean publishPlugins
