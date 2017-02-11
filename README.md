# kafka-processor-seed.g8
A kafka processor template project.

# Introduction
A kafka producer template project using:

```scala
libraryDependencies += "io.confluent" % "kafka-avro-serializer" % "3.1.2"
libraryDependencies += "com.sksamuel.avro4s" %% "avro4s-core" % "1.6.4"
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.5.12"
libraryDependencies += "com.typesafe.play" %% "play-ws" % "2.5.12"
libraryDependencies += "com.typesafe.akka" %% "akka-stream-kafka" % "0.13"
```

# Usage
You need at least sbt v0.13.13 and then type:

```
sbt new dnvriend/kafka-processor-seed.g8
```

Have fun!