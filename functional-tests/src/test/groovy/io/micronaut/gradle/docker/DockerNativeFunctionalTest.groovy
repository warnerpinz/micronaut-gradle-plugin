package io.micronaut.gradle.docker

import io.micronaut.gradle.AbstractGradleBuildSpec
import io.micronaut.gradle.fixtures.AbstractFunctionalTest
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.IgnoreIf
import spock.lang.Requires

@Requires({ AbstractGradleBuildSpec.graalVmAvailable })
@IgnoreIf({ os.windows })
@Requires({ jvm.isJava11Compatible() })
class DockerNativeFunctionalTest extends AbstractFunctionalTest {

    def "test build docker native image for runtime #runtime"() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        println settingsFile.text
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.docker"
                id "io.micronaut.graalvm"
            }
            
            micronaut {
                version "2.3.4"
                runtime "$runtime"
            }
            
            $repositoriesBlock
            
            mainClassName="example.Application"
            
            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }
            
            dockerfileNative {
                args('-Xmx64m')
                instruction \"\"\"HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'\"\"\"
            }
            
        """
        testProjectDir.newFolder("src", "main", "java", "example")
        def resources = testProjectDir.newFolder("src", "main", "resources")
        resources.mkdirs()
        def javaFile = testProjectDir.newFile("src/main/java/example/Application.java")
        javaFile.parentFile.mkdirs()
        javaFile << """
package example;

class Application {
    public static void main(String... args) {
    
    }
}
"""
        def controllerFile = testProjectDir.newFile("src/main/java/example/TestController.java")
        controllerFile << """
package example;

import io.micronaut.http.annotation.*;

@Controller("/foo")
class TestController {
}
"""
        def config = testProjectDir.newFile("src/main/resources/application.yml")
        config.parentFile.mkdirs()
        config << """
micronaut:
   application:
        name: test
"""


        def result = build('dockerBuildNative')

        def task = result.task(":dockerBuildNative")
        def dockerFile = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        expect:
        dockerFile.first().startsWith(nativeImage)
        dockerFile.find { s -> s == """HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'""" }
        dockerFile.last().contains('ENTRYPOINT')
        dockerFile.find { s -> s.contains('-Xmx64m') }

        and:
        result.output.contains("Successfully tagged hello-world:latest")
        result.output.contains("Resources configuration written into")
        task.outcome == TaskOutcome.SUCCESS

        where:
        runtime  | nativeImage
        "netty"  | 'FROM ghcr.io/graalvm/native-image:java'
        "lambda" | 'FROM amazonlinux:latest AS graalvm'
        "jetty"  | 'FROM ghcr.io/graalvm/native-image:java'
    }

    void 'build mostly static native images when using distroless docker image for runtime=#runtime'() {
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

                runtime "$runtime"
            }

            $repositoriesBlock

            application {
                mainClass.set("com.example.Application")
            }

            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }

            dockerfileNative {
                baseImage("gcr.io/distroless/cc-debian10")
            }
        """

        when:
        def result = build('dockerfileNative')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFileNative.find { s -> s.contains('FROM gcr.io/distroless/cc-debian10') }
        dockerFileNative.find { s -> s.contains('-H:+StaticExecutableWithDynamicLibC') }

        where:
        runtime << ['netty', 'lambda']
    }

    void 'use alpine-glibc by default and do not build mostly static native images'() {
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
        def result = build('dockerfileNative')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFileNative.find { s -> s.contains('FROM frolvlad/alpine-glibc:alpine-3.12') }
        dockerFileNative.find { s -> !s.contains('-H:+StaticExecutableWithDynamicLibC') }
    }

    void 'do not use alpine-glibc for lambda runtime'() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.docker"
                id "io.micronaut.graalvm"
            }

            micronaut {
                version "2.3.0"
                runtime "lambda"
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
        def result = build('dockerfileNative')

        def dockerfileNativeTask = result.task(':dockerfileNative')
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')

        then:
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFileNative.find { s -> !s.contains('FROM frolvlad/alpine-glibc:alpine-3.12') }
        dockerFileNative.find { s -> !s.contains('-H:+StaticExecutableWithDynamicLibC') }
    }

    def "test build docker native image for lambda with custom main"() {
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
                runtime "lambda"
            }
            
            $repositoriesBlock
            
            graalvmNative {
                binaries {
                    main {
                        mainClass =  "other.Application"
                    }
                }
            }
            
            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }
            
            mainClassName="example.Application"
        """
        testProjectDir.newFolder("src", "main", "java", "other")
        def javaFile = testProjectDir.newFile("src/main/java/other/Application.java")
        javaFile.parentFile.mkdirs()
        javaFile << """
package other;

class Application {
    public static void main(String... args) {
    
    }
}
"""

        def result = build('dockerBuildNative')

        def task = result.task(":dockerBuildNative")

        expect:
        result.output.contains("Successfully tagged hello-world:latest")
        task.outcome == TaskOutcome.SUCCESS
    }

    def "test construct dockerfile and dockerfileNative custom entrypoint"() {
        setup:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.docker"
                id "io.micronaut.graalvm"
            }
            
            micronaut {
                version "2.3.4"
            }
            
            $repositoriesBlock
            
            graalvmNative {
                binaries {
                    main {
                        mainClass = "other.Application"
                    }
                }
            }
            
            java {
                sourceCompatibility = JavaVersion.toVersion('8')
                targetCompatibility = JavaVersion.toVersion('8')
            }
            
            dockerfile {
                args('-Xmx64m')
                baseImage('test_base_image_jvm')
                instruction \"\"\"HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'\"\"\"
                entryPoint('./entrypoint.sh')
            }
            dockerfileNative {
                args('-Xmx64m')
                baseImage('test_base_image_docker')
                instruction \"\"\"HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'\"\"\"
                entryPoint('./entrypoint.sh')
            }
            
            mainClassName="example.Application"
        """
        testProjectDir.newFolder("src", "main", "java", "other")
        def javaFile = testProjectDir.newFile("src/main/java/other/Application.java")
        javaFile.parentFile.mkdirs()
        javaFile << """
package other;

class Application {
    public static void main(String... args) {
    
    }
}
"""

        def result = build('dockerfile', 'dockerfileNative')

        def dockerfileTask = result.task(":dockerfile")
        def dockerfileNativeTask = result.task(":dockerfileNative")
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')
        def dockerFile = new File(testProjectDir.root, 'build/docker/main/Dockerfile').readLines('UTF-8')

        expect:
        dockerfileTask.outcome == TaskOutcome.SUCCESS
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFile.first() == ('FROM test_base_image_jvm')
        dockerFile.find { s -> s == """HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'""" }
        dockerFile.find { s -> s == 'ENTRYPOINT ["./entrypoint.sh"]'}

        and:
        dockerFileNative.find() { s -> s == 'FROM test_base_image_docker' }
        dockerFileNative.find { s -> s == """HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'""" }
        dockerFileNative.find { s -> s == 'ENTRYPOINT ["./entrypoint.sh"]'}
    }

    void 'test build native docker file'() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.docker"
                id "io.micronaut.graalvm"
            }
            
            micronaut {
                version "2.4.2"
                runtime "netty"
                testRuntime "junit5"
            }
            
            $repositoriesBlock
            
            dependencies {
                runtimeOnly("ch.qos.logback:logback-classic")
                testImplementation("io.micronaut:micronaut-http-client")
            }
            mainClassName="example.Application"
            
        """

        when:
        def result = build('javaToolchains', 'dockerfileNative')

        def task = result.task(":dockerfileNative")
        println(result.output)

        then:
        task.outcome == TaskOutcome.SUCCESS
        result.output.contains("Dockerfile written to")
        result.output.contains("build/docker/native-main/DockerfileNative")

        new File("$testProjectDir.root/build/docker/native-main/DockerfileNative").text.count("-cp") == 1
    }

    def "test construct dockerfile and dockerfileNative"() {
        setup:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            plugins {
                id "io.micronaut.minimal.application"
                id "io.micronaut.docker"
                id "io.micronaut.graalvm"
            }
            
            micronaut {
                version "2.3.4"
                runtime "$runtime"
            }
            
            $repositoriesBlock
            
            graalvmNative {
                binaries {
                    main {
                        mainClass =  "other.Application"
                    }
                }
            }
                    
            java {
                sourceCompatibility = JavaVersion.toVersion('11')
                targetCompatibility = JavaVersion.toVersion('11')
            }
            
            dockerfile {
                args('-Xmx64m')
                baseImage('test_base_image_jvm')
                instruction \"\"\"HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'\"\"\"
            }
            dockerfileNative {
                args('-Xmx64m')
                baseImage('test_base_image_docker')
                instruction \"\"\"HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'\"\"\"
            }
            
            mainClassName="example.Application"
        """
        testProjectDir.newFolder("src", "main", "java", "other")
        def javaFile = testProjectDir.newFile("src/main/java/other/Application.java")
        javaFile.parentFile.mkdirs()
        javaFile << """
package other;

class Application {
    public static void main(String... args) {
    
    }
}
"""

        def result = build('dockerfile', 'dockerfileNative')

        def dockerfileTask = result.task(":dockerfile")
        def dockerfileNativeTask = result.task(":dockerfileNative")
        def dockerFileNative = new File(testProjectDir.root, 'build/docker/native-main/DockerfileNative').readLines('UTF-8')
        def dockerFile = new File(testProjectDir.root, 'build/docker/main/Dockerfile').readLines('UTF-8')

        expect:
        dockerfileTask.outcome == TaskOutcome.SUCCESS
        dockerfileNativeTask.outcome == TaskOutcome.SUCCESS

        and:
        dockerFile.first() == ('FROM test_base_image_jvm')
        dockerFile.find { s -> s == """HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'""" }
        dockerFile.last().contains('ENTRYPOINT')
        dockerFile.find { s -> s.contains('-Xmx64m') }

        and:
        dockerFileNative.find() { s -> s == 'FROM test_base_image_docker' }
        dockerFileNative.find { s -> s == """HEALTHCHECK CMD curl -s localhost:8090/health | grep '"status":"UP"'""" }
        dockerFileNative.last().contains('ENTRYPOINT')
        dockerFileNative.find {s -> s.contains('-Xmx64m')}

        where:
        runtime  | nativeImage
        "netty"  | 'FROM ghcr.io/graalvm/native-image:java'
        "lambda" | 'FROM amazonlinux:latest AS graalvm'
        "jetty"  | 'FROM ghcr.io/graalvm/native-image:java'
    }
}
