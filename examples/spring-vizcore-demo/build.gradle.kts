import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.jh.vizcore.demo"
version = "0.0.1-SNAPSHOT"

// The vizcore jars were compiled against kotlinx-coroutines 1.11.0. Spring Boot's dependency
// management otherwise pins a different coroutines version at runtime, causing a runBlocking
// NoSuchMethodError (compile-vs-runtime ABI mismatch). Override the managed version everywhere.
extra["kotlin-coroutines.version"] = "1.11.0"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

// The vizcore embeddable client + core are unpublished Gradle subprojects of ../../backend.
// We can't use a Gradle composite build (`includeBuild`) because that build also contains the
// IntelliJ-plugin module, whose IntelliJ-Platform plugin would download a full IDE SDK. Instead
// we depend on the prebuilt jars and declare the modules' EXTERNAL deps explicitly (flat-file
// jars carry no transitive metadata). Rebuild the jars with:
//   cd ../../backend && ./gradlew :coroutine-viz-client:jar :coroutine-viz-core:jar
val vizcoreJars =
	files(
		"../../backend/coroutine-viz-client/build/libs/coroutine-viz-client-0.1.0.jar",
		"../../backend/coroutine-viz-core/build/libs/coroutine-viz-core-0.1.0.jar",
	)

dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// vizcore client (prebuilt local jars)
	implementation(vizcoreJars)

	// External runtime deps of coroutine-viz-client + coroutine-viz-core. Versions match
	// backend/gradle.properties + the modules' build.gradle.kts. Maven resolves THEIR transitives.
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	implementation("io.ktor:ktor-client-core:3.3.2")
	implementation("io.ktor:ktor-client-cio:3.3.2")
	implementation("io.ktor:ktor-client-websockets:3.3.2")
	implementation("org.slf4j:slf4j-api:2.0.9")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

// Two main()s live here (the Spring app + the throwaway hierarchy spike), so pin the Boot entry.
springBoot {
	mainClass.set("com.jh.vizcore.demo.SpringVizcoreDemoApplicationKt")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// DebugProbesSource installs kotlinx-coroutines-debug via a dynamically-attached byte-buddy
// agent. On JDK 9+ a JVM self-attaching an agent requires this flag; EnableDynamicAgentLoading
// suppresses the JDK 21 dynamic-agent warning.
tasks.named<BootRun>("bootRun") {
	jvmArgs(
		"-Djdk.attach.allowAttachSelf=true",
		"-XX:+EnableDynamicAgentLoading",
	)
}

// Throwaway: ./gradlew spike  — proves DebugProbes Job.children reconstructs the parent/child tree.
tasks.register<JavaExec>("spike") {
	group = "verification"
	description = "Run the DebugProbes hierarchy-recovery spike."
	mainClass.set("com.jh.vizcore.demo.spike.HierarchySpikeKt")
	classpath = sourceSets["main"].runtimeClasspath
	jvmArgs("-Djdk.attach.allowAttachSelf=true", "-XX:+EnableDynamicAgentLoading")
}
