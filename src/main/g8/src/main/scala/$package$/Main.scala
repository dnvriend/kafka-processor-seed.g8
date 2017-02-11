package $package$

import java.util.UUID

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions}
import akka.stream.{ActorMaterializer, Materializer}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{Deserializer, Serializer}
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.{AhcWSClient, AhcWSClientConfig}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Main extends App {
  type Topic = String
  type Key = String
  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: Materializer = ActorMaterializer()

  def producerSettings[K, V](system: ActorSystem, keySerializer: Option[Serializer[K]], valueSerializer: Option[Serializer[V]]): ProducerSettings[K, V] =
    ProducerSettings(system, keySerializer, valueSerializer)
      .withBootstrapServers("localhost:9092")

  def consumerSettings[K, V](system: ActorSystem, keySerializer: Option[Deserializer[K]], valueSerializer: Option[Deserializer[V]]) = {
    ConsumerSettings(system, keySerializer, valueSerializer)
      .withBootstrapServers("localhost:9092")
  }

  def recordFlow[Value](implicit recordFormat: RecordFormat[Value]): Flow[(Topic, Key, Value), ProducerRecord[Key, GenericRecord], NotUsed] =
    Flow[(Topic, Key, Value)].map {
      case (topic, key, value) =>
        new ProducerRecord(topic, key, recordFormat.to(value))
    }

  def randomId: String = UUID.randomUUID.toString

  final case class PersonCreated(id: String, name: String, age: Long, married: Option[Boolean] = None, children: Long = 0)

  object PersonCreated {
    implicit val recordFormat = RecordFormat[PersonCreated]
  }

  def callGoogle(ws: WSClient): Future[String] = {
    ws.url("https://www.google.nl").get().map(_.body)
  }

  def getGoogleData: Future[Done] = {
    Consumer.committableSource(consumerSettings[String, String](system, None, None), Subscriptions.topics("PersonCreatedAvro"))
      .mapAsync(1) { msg =>
        callGoogle(ws)
          .map(response => (msg, response))
      }.map {
      case (msg, response) => ProducerMessage.Message(new ProducerRecord[String, String]("GoogleResponsesAvro", response), msg.committableOffset)
    }.runWith(Producer.commitableSink(producerSettings(system, None, None)))
  }

  (for {
    _ <- getGoogleData
    _ <- system.terminate()
  } yield println("done")).recoverWith {
    case t: Throwable =>
      t.printStackTrace()
      system.terminate()
  }
}
