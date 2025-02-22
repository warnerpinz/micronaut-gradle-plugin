package io.micronaut.gradle.lambda

import io.micronaut.gradle.AbstractGradleBuildSpec
import io.micronaut.gradle.fixtures.AbstractFunctionalTest
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.IgnoreIf
import spock.lang.Issue
import spock.lang.Requires

@Requires({ AbstractGradleBuildSpec.graalVmAvailable })
@IgnoreIf({ os.windows })
@Requires({ jvm.isJava11Compatible() })
class LambdaNativeImageSpec extends AbstractFunctionalTest {

    void 'mainclass is set correctly for an application deployed as GraalVM and Lambda'() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.docker"
                id "io.micronaut.graalvm"
            }
            
            micronaut {
                version "2.3.4"
                runtime "netty"
            }
            
            $repositoriesBlock
            
            application {
                mainClass.set("com.example.Application")
            }
            
            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }
        """

        when:
        def result = build('dockerfileNative', '-Pmicronaut.runtime=lambda')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFileNative.find() { it.contains('-H:Class=io.micronaut.function.aws.runtime.MicronautLambdaRuntime')}
        !dockerFileNative.find() { it.contains('com.example.Application')}
    }

    void 'it is possible to define the mainclass for a dockerfile native'() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.graalvm"
                id "io.micronaut.docker"
            }
            
            micronaut {
                version "2.3.4"
                runtime "netty"
            }
            
            $repositoriesBlock
            
            application {
                mainClass.set("com.example.Application")
            }
            
            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }
            
            graalvmNative {
                binaries {
                    main {
                        mainClass.set("my.own.main.class")
                    }
                }
            }
        """

        when:
        def result = build('dockerfileNative')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFileNative.find() { it.contains('my.own.main.class')}
        !dockerFileNative.find() { it.contains('com.example.Application')}
    }

    @Issue("https://github.com/micronaut-projects/micronaut-gradle-plugin/issues/171")
    void 'mainclass is set correctly for an AWS Lambda function using custom-runtime and GraalVM'() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.graalvm"
                id "io.micronaut.docker"
            }

            micronaut {
                version "2.3.4"
                runtime "netty"
            }

            $repositoriesBlock

            dependencies {
                implementation("io.micronaut:micronaut-validation")
                implementation("io.micronaut:micronaut-runtime")
                implementation("io.micronaut.aws:micronaut-function-aws")
                implementation("io.micronaut.aws:micronaut-function-aws-custom-runtime")
                runtimeOnly("ch.qos.logback:logback-classic")
                testImplementation("io.micronaut:micronaut-http-client")
            }

            application {
                mainClass.set("com.example.BookLambdaRuntime")
            }

            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }
        """

        when:
        def result = build('dockerfileNative', '-Pmicronaut.runtime=lambda')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        !dockerFileNative.find() { it.contains('-H:Class=io.micronaut.function.aws.runtime.MicronautLambdaRuntime')}
        dockerFileNative.find() { it.contains('-H:Class=com.example.BookLambdaRuntime')}
    }

    @Issue("https://github.com/micronaut-projects/micronaut-gradle-plugin/issues/279")
    void 'baseImage is overridden correctly for an AWS Lambda function using custom-runtime and GraalVM'() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.application"
            }

            micronaut {
                version "2.3.4"
                runtime "netty"
            }

            $repositoriesBlock

            dependencies {
                implementation("io.micronaut:micronaut-validation")
                implementation("io.micronaut:micronaut-runtime")
                implementation("io.micronaut.aws:micronaut-function-aws")
                implementation("io.micronaut.aws:micronaut-function-aws-custom-runtime")
                runtimeOnly("ch.qos.logback:logback-classic")
                testImplementation("io.micronaut:micronaut-http-client")
            }

            application {
                mainClass.set("com.example.BookLambdaRuntime")
            }

            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }

            dockerfileNative {
                baseImage('internal.proxy.com/amazonlinux:latest')
            }
        """

        when:
        def result = build('dockerfileNative', '-Pmicronaut.runtime=lambda')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFileNative.find() { it.contains('internal.proxy.com/amazonlinux:latest')}
    }

    @Issue("https://github.com/micronaut-projects/micronaut-gradle-plugin/issues/279")
    void 'baseImage is set correctly for an AWS Lambda function using custom-runtime and GraalVM'() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.application"
            }

            micronaut {
                version "2.3.4"
                runtime "netty"
            }

            $repositoriesBlock

            dependencies {
                implementation("io.micronaut:micronaut-validation")
                implementation("io.micronaut:micronaut-runtime")
                implementation("io.micronaut.aws:micronaut-function-aws")
                implementation("io.micronaut.aws:micronaut-function-aws-custom-runtime")
                runtimeOnly("ch.qos.logback:logback-classic")
                testImplementation("io.micronaut:micronaut-http-client")
            }

            application {
                mainClass.set("com.example.BookLambdaRuntime")
            }

            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }
        """

        when:
        def result = build('dockerfileNative', '-Pmicronaut.runtime=lambda')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFileNative.find() { it.contains('amazonlinux:latest')}
    }
}
