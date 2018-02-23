// Name of the project
name := "flandmark-demo"

// Project version
version := "1.2"

// Version of Scala used by the project
scalaVersion := "2.11.12"

val javacppVersion = "1.2"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-optimize", "-Xlint")

// Some dependencies like `javacpp` are packaged with maven-plugin packaging
classpathTypes += "maven-plugin"

// Platform classifier for native library dependencies
lazy val platform = org.bytedeco.javacpp.Loader.getPlatform

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.9",
  "org.bytedeco" % "javacpp" % javacppVersion,
  "org.bytedeco" % "javacv" % javacppVersion,
  "org.bytedeco.javacpp-presets" % "flandmark" % ("1.07-" + javacppVersion) classifier "",
  "org.bytedeco.javacpp-presets" % "flandmark" % ("1.07-" + javacppVersion) classifier platform,
  "org.bytedeco.javacpp-presets" % "opencv" % ("3.1.0-" + javacppVersion) classifier "",
  "org.bytedeco.javacpp-presets" % "opencv" % ("3.1.0-" + javacppVersion) classifier platform
)

// Used for testing local builds and snapshots of JavaCPP/JavaCV
//resolvers ++= Seq(
//  Resolver.sonatypeRepo("snapshots"),
//  // Use local maven repo for local javacv builds
//  "Local Maven Repository" at "file:///" + Path.userHome.absolutePath + "/.m2/repository"
//)

// set the main class for 'sbt run'
mainClass in (Compile, run) := Some("flandmark.Example1")

// Fork a new JVM for 'run' and 'test:run', to avoid JavaFX double initialization problems
fork := true

// Set the prompt (for this build) to include the project id.
shellPrompt in ThisBuild := { state => "sbt:" + Project.extract(state).currentRef.project + "> " }

// For Swing
libraryDependencies := {
  CrossVersion.partialVersion(scalaVersion.value) match {
    // if scala 2.11+ is used, add dependency on scala-xml module
    case Some((2, scalaMajor)) if scalaMajor >= 11 =>
      libraryDependencies.value ++ Seq(
        "org.scala-lang.modules" %% "scala-swing" % "2.0.2")
    case _ =>
      // or just libraryDependencies.value if you don't depend on scala-swing
      libraryDependencies.value :+ "org.scala-lang" % "scala-swing" % scalaVersion.value
  }
}
