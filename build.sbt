name := "AntsApplet"

version := "0.1"

scalaVersion := "2.11.0"

scalacOptions += "-deprecation"

scalacOptions += "-feature"

fork := true

// seq(ProguardPlugin.proguardSettings :_*)

// proguardOptions ++= Seq(
//   "-keep class akka.actor.Actor",
//   "-keep public class akka.actor.LocalActorRefProvider { public <init>(...); }",
//   "-keep public class akka.remote.RemoteActorRefProvider { public <init>(...); }",
//   "-keep class akka.actor.DefaultSupervisorStrategy { *; }",
//   "-keep class akka.actor.SerializedActorRef { *; }",
//   "-keep class akka.remote.netty.NettyRemoteTransport { *; }",
//   "-keep class akka.serialization.JavaSerializer { *; }",
//   "-keep class akka.serialization.ProtobufSerializer { *; }",
//   "-keep class com.google.protobuf.GeneratedMessage { *; }",
//   "-keep class akka.event.Logging*",
//   "-keep class akka.event.Logging$LogExt{ *; }",
//   keepMain("org.graphstream.ants.AntsApplet")
// )

proguardSettings

ProguardKeys.options in Proguard ++= Seq(
  "-keep class akka.actor.Actor",
  "-keep public class akka.actor.LocalActorRefProvider { public <init>(...); }",
  "-keep public class akka.remote.RemoteActorRefProvider { public <init>(...); }",
  "-keep class akka.actor.DefaultSupervisorStrategy { *; }",
  "-keep class akka.actor.SerializedActorRef { *; }",
  "-keep class akka.remote.netty.NettyRemoteTransport { *; }",
  "-keep class akka.serialization.JavaSerializer { *; }",
  "-keep class akka.serialization.ProtobufSerializer { *; }",
  "-keep class com.google.protobuf.GeneratedMessage { *; }",
  "-keep class akka.event.Logging*",
  "-keep class akka.event.Logging$LogExt{ *; }"
)

ProguardKeys.options in Proguard += ProguardOptions.keepMain("org.graphstream.ants.AntsApplet")

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies += "junit" % "junit" % "4.10"

libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.3.4"

libraryDependencies += "com.typesafe.akka" %% "akka-remote" % "2.3.4"
