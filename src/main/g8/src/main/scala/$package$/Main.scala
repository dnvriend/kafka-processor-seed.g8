package $package$

import java.util.UUID

import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.{Done, NotUsed}
import com.sksamuel.avro4s.RecordFormat
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Main extends App {
  type Topic = String
  type Key = String
  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: Materializer = ActorMaterializer()
  val producerSettings: ProducerSettings[String, GenericRecord] =
    ProducerSettings[String, GenericRecord](system, None, None)
      .withBootstrapServers("localhost:9092")

  def recordFlow[Value](implicit recordFormat: RecordFormat[Value]): Flow[(Topic, Key, Value), ProducerRecord[Key, GenericRecord], NotUsed] =
    Flow[(Topic, Key, Value)].map {
      case (topic, key, value) =>
        new ProducerRecord(topic, key, recordFormat.to(value))
    }

  val sink: Sink[ProducerRecord[String, GenericRecord], Future[Done]] =
    Producer.plainSink(producerSettings)

  def randomId: String = UUID.randomUUID.toString

  final case class PersonCreated(id: String, name: String, age: Long, married: Option[Boolean] = None, children: Long = 0)

  object PersonCreated {
    implicit val jsonFormat = Json.format[PersonCreated]
    implicit val recordFormat = RecordFormat[PersonCreated]
  }

  val done =
    Source.repeat(1)
      .take(1000000)
      .zipWithIndex
      .map {
        case (_, index) =>
          if (index % 10000 == 0) println("processed: " + index)
          PersonCreated(randomId, s"foo-\$index", index, None, index)
      }
      .map(value => ("PersonCreatedAvro", value.id, value))
      .via(recordFlow)
      .runWith(sink)

  (for {
    _ <- done
    _ <- system.terminate()
  } yield println("done")).recoverWith {
    case t: Throwable =>
      t.printStackTrace()
      system.terminate()
  }
}
