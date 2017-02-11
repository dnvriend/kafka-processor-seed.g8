package $package$

import java.util.UUID

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.{ CommittableMessage, CommittableOffset }
import akka.kafka.ProducerMessage.Message
import akka.kafka.scaladsl.{ Consumer, Producer }
import akka.kafka.{ ConsumerSettings, ProducerMessage, ProducerSettings, Subscriptions }
import akka.stream.scaladsl.Flow
import akka.stream.{ ActorMaterializer, Materializer }
import akka.{ Done, NotUsed }
import com.sksamuel.avro4s.RecordFormat
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.{ AhcWSClient, AhcWSClientConfig }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Main extends App {
  type Topic = String
  type Key = String
  implicit val system: ActorSystem = ActorSystem()
  implicit val mat: Materializer = ActorMaterializer()
  val ws = AhcWSClient(AhcWSClientConfig(maxRequestRetry = 0))(mat)

  val producerSettings: ProducerSettings[Key, GenericRecord] =
    ProducerSettings(system, None, None)

  val consumerSettings: ConsumerSettings[Key, GenericRecord] =
    ConsumerSettings(system, None, None)

  def mapToAvro[Value](implicit recordFormat: RecordFormat[Value]): Flow[(CommittableMessage[String, GenericRecord], (Topic, Key, Value)), Message[Key, GenericRecord, CommittableOffset], NotUsed] =
    Flow[(CommittableMessage[Key, GenericRecord], (Topic, Key, Value))].map {
      case (committable, (topic, key, value)) =>
        ProducerMessage.Message(new ProducerRecord[Key, GenericRecord](topic, key, recordFormat.to(value)), committable.committableOffset)
    }

  def parseFromAvro[Value](implicit recordFormat: RecordFormat[Value]): Flow[CommittableMessage[Key, GenericRecord], (CommittableMessage[Key, GenericRecord], Value), NotUsed] =
    Flow[CommittableMessage[Key, GenericRecord]].map(record => (record, recordFormat.from(record.record.value())))

  def randomId: String = UUID.randomUUID.toString

  final case class PersonCreated(id: String, name: String, age: Long, married: Option[Boolean] = None, children: Long = 0)

  object PersonCreated {
    implicit val recordFormat = RecordFormat[PersonCreated]
  }

  final case class GoogleResponse(response: String)
  object GoogleResponse {
    implicit val recordFormat = RecordFormat[GoogleResponse]
  }

  def callGoogle(ws: WSClient): Future[GoogleResponse] = {
    ws.url("https://www.google.nl").get().map(_.body).map(GoogleResponse.apply)
  }

  def process: Future[Done] = {
    Consumer.committableSource(consumerSettings, Subscriptions.topics("PersonCreatedAvro"))
      .via(parseFromAvro[PersonCreated])
      .mapAsync(1) {
        case (committable, personCreated) =>
          callGoogle(ws).map(resp => (committable, resp, personCreated.id))
      }.map {
      case (committable, response, id) =>
        (committable, ("GoogleResponsesAvro", id, response))
    }
      .via(mapToAvro)
      .runWith(Producer.commitableSink(producerSettings))
  }

  (for {
    _ <- process
    _ <- system.terminate()
  } yield println("done")).recoverWith {
    case t: Throwable =>
      t.printStackTrace()
      system.terminate()
  }
}